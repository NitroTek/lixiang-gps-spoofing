package kz.prounbound;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import kz.prounbound.route.LoopRoute;
import kz.prounbound.route.RouteLoader;
import kz.prounbound.route.RouteCatalog;
import kz.prounbound.route.RoutePlayback;
import kz.prounbound.ui.MainActivity;
import kz.prounbound.spoofing.ChinaRegion;
import kz.prounbound.spoofing.SpoofingSession;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Random;

/** The service owns session; closing or rotating the activity never stops the route. */
public final class FakeLocationService extends Service {
    public static final String ACTION_START = "kz.prounbound.START";
    public static final String ACTION_STOP = "kz.prounbound.STOP";
    public static final String EXTRA_SPEED = "speed_kmh";
    public static final String EXTRA_ACCURACY = "accuracy_meters";
    public static final String PREF_ACCURACY = "accuracy_meters";
    public static final int DEFAULT_ACCURACY_METERS = 5;
    public static final int MAX_ACCURACY_METERS = 5;
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_ROUTE_ID = "route_id";
    public static final String PREF_ROUTE_ID = "route_id";
    public static final String EXTRA_TIMER_SECONDS = "timer_seconds";
    public static final String PREF_MODE = "mode";
    public static final String PREF_TIMER_ENABLED = "timer_enabled";
    public static final String PREF_TIMER_SECONDS = "timer_seconds";
    public static final String PREFS = "g30";
    public static final String PREF_SPEED = "speed_kmh";
    private static final String CHANNEL_ID = "g30_route";
    private static final int NOTIFICATION_ID = 1001;
    private static final long UPDATE_INTERVAL_MS = 250;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private LoopRoute route;
    private RouteCatalog.Entry selectedRoute;
    private boolean routeFinished;
    private SpoofingSession session;
    private ChinaRegion china;
    private int mode;
    private boolean timerEnabled;
    private int timerSeconds;
    private boolean timerFinished;
    private int lastNotificationSeconds = -2;
    private MockLocationPublisher publisher;
    private PowerManager.WakeLock wakeLock;
    private long wakeLockRenewedAt;
    private boolean running;
    private int speedKmh;
    private int accuracyMeters;
    private final int[] extraAccuracy = new int[GpsAccuracy.values().length];
    private String error;

    public final class LocalBinder extends Binder {
        public FakeLocationService getService() {
            return FakeLocationService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        for (GpsAccuracy setting : GpsAccuracy.values()) {
            extraAccuracy[setting.ordinal()] = setting.clamp(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getInt(setting.key, setting.defaultProgress));
        }
        accuracyMeters = Math.max(0, Math.min(MAX_ACCURACY_METERS,
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getInt(PREF_ACCURACY, DEFAULT_ACCURACY_METERS)));
        speedKmh = Math.max(0, Math.min(RoutePlayback.MAX_SPEED_KMH,
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getInt(PREF_SPEED, RoutePlayback.DEFAULT_SPEED_KMH)));
        mode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_MODE, SpoofingSession.MODE_ROUTE);
        if (!SpoofingSession.isValidMode(mode)) mode = SpoofingSession.MODE_ROUTE;
        timerEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_TIMER_ENABLED, false);
        timerSeconds = Math.max(1, Math.min(SpoofingSession.MAX_TIMER_SECONDS,
                getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_TIMER_SECONDS, 60)));
        publisher = new MockLocationPublisher(this);
        configureRoute(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_ROUTE_ID, "g30-loop"));
        try (InputStreamReader input = new InputStreamReader(
                getAssets().open("china-mainland.csv"), "UTF-8")) {
            china = ChinaRegion.load(input);
        } catch (IOException e) {
            Log.e("ProUnbound", "Cannot load China boundary", e);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopRoute();
        } else if (ACTION_START.equals(intent.getAction())) {
            for (GpsAccuracy setting : GpsAccuracy.values()) {
                setAccuracyProgress(setting, intent.getIntExtra(setting.key, getAccuracyProgress(setting)));
            }
            setAccuracyMeters(intent.getIntExtra(EXTRA_ACCURACY, accuracyMeters));
            configureRoute(intent.getStringExtra(EXTRA_ROUTE_ID) == null ? selectedRoute.id
                    : intent.getStringExtra(EXTRA_ROUTE_ID));
            startRoute(intent.getIntExtra(EXTRA_SPEED, speedKmh),
                    intent.getIntExtra(EXTRA_MODE, mode),
                    intent.getIntExtra(EXTRA_TIMER_SECONDS, timerEnabled ? timerSeconds : 0));
        } else {
            stopSelf(startId);
        }
        // A process killed by Android must not silently resume or restart at the origin.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void startRoute(int requestedSpeed, int requestedMode, int requestedTimerSeconds) {
        if (running) {
            setSpeedKmh(requestedSpeed);
            return;
        }
        configureMode(requestedMode);
        configureTimer(requestedTimerSeconds > 0,
                requestedTimerSeconds > 0 ? requestedTimerSeconds : timerSeconds);
        error = null;
        timerFinished = false;
        routeFinished = false;
        try {
            setSpeedKmh(requestedSpeed);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            if (!isReady()) throw new IOException("Selected mode data is unavailable");
            publisher.start();
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProUnbound:Spoofing");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(60_000);
            wakeLockRenewedAt = SystemClock.elapsedRealtime();
            long now = SystemClock.elapsedRealtime();
            session = new SpoofingSession(route, china, mode, speedKmh,
                    timerEnabled ? timerSeconds : 0, new Random(), now);
            running = true;
            lastNotificationSeconds = -2;
            handler.post(updateLocation);
            if (session.hasTimer()) handler.postDelayed(autoStop, session.remainingMillis(now));
        } catch (IOException e) {
            fail(mode == SpoofingSession.MODE_ROUTE ? R.string.route_error : R.string.china_error, e);
        } catch (SecurityException e) {
            fail(R.string.mock_error, e);
        } catch (RuntimeException e) {
            fail(R.string.service_error, e);
        }
    }

    public void setSpeedKmh(int value) {
        if (value < 0 || value > RoutePlayback.MAX_SPEED_KMH) return;
        if (running) checkAutoStop();
        if (running) session.setSpeedKmh(value, SystemClock.elapsedRealtime());
        speedKmh = value;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_SPEED, value).apply();
        if (running) {
            try {
                // The reported GPS speed changes immediately, without resetting position.
                publishPosition();
                if (checkRouteFinished()) return;
                updateNotification();
            } catch (RuntimeException e) {
                fail(R.string.mock_error, e);
            }
        }
    }

    public int getAccuracyMeters() { return accuracyMeters; }

    public int getAccuracyProgress(GpsAccuracy setting) { return extraAccuracy[setting.ordinal()]; }

    public void setAccuracyProgress(GpsAccuracy setting, int progress) {
        if (progress < 0 || progress > setting.maxProgress) return;
        extraAccuracy[setting.ordinal()] = progress;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(setting.key, progress).apply();
        if (running) checkAutoStop();
        if (running) {
            try {
                publishPosition();
            } catch (RuntimeException e) {
                fail(R.string.mock_error, e);
            }
        }
    }

    private void publishPosition() {
        publisher.publish(session.position(), session.speedKmh(), accuracyMeters,
                GpsAccuracy.ALTITUDE.value(getAccuracyProgress(GpsAccuracy.ALTITUDE)),
                GpsAccuracy.SPEED.value(getAccuracyProgress(GpsAccuracy.SPEED)),
                GpsAccuracy.BEARING.value(getAccuracyProgress(GpsAccuracy.BEARING)));
    }

    public void setAccuracyMeters(int value) {
        if (value < 0 || value > MAX_ACCURACY_METERS) return;
        accuracyMeters = value;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_ACCURACY, value).apply();
        if (running) checkAutoStop();
        if (running) {
            try {
                publishPosition();
            } catch (RuntimeException e) {
                fail(R.string.mock_error, e);
            }
        }
    }

    private final Runnable updateLocation = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                if (checkAutoStop()) return;
                long now = SystemClock.elapsedRealtime();
                // Renew during session; a stalled update loop releases the lock automatically.
                if (now - wakeLockRenewedAt >= 30_000) {
                    wakeLock.acquire(60_000);
                    wakeLockRenewedAt = now;
                }
                session.advanceTo(now);
                publishPosition();
                if (checkRouteFinished()) return;
                if (getRemainingTimerSeconds() != lastNotificationSeconds) updateNotification();
                handler.postDelayed(this, UPDATE_INTERVAL_MS);
            } catch (RuntimeException e) {
                fail(R.string.mock_error, e);
            }
        }
    };

    private boolean checkAutoStop() {
        if (running && session.isExpired(SystemClock.elapsedRealtime())) {
            stopRoute();
            timerFinished = true;
            return true;
        }
        return false;
    }

    private boolean checkRouteFinished() {
        if (running && session.isRouteFinished()) {
            stopRoute();
            routeFinished = true;
            return true;
        }
        return false;
    }

    private final Runnable autoStop = new Runnable() {
        @Override public void run() {
            if (!running || !session.hasTimer()) return;
            if (!checkAutoStop()) {
                handler.postDelayed(this, Math.max(1, session.remainingMillis(SystemClock.elapsedRealtime())));
            }
        }
    };

    private void updateNotification() {
        lastNotificationSeconds = getRemainingTimerSeconds();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, buildNotification());
    }

    public void configureMode(int value) {
        if (running || !SpoofingSession.isValidMode(value)) return;
        if (mode != value) {
            session = null;
            timerFinished = false;
            routeFinished = false;
            error = null;
        }
        mode = value;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_MODE, value).apply();
    }

    public void configureRoute(String id) {
        if (running) return;
        RouteCatalog.Entry next = RouteCatalog.find(id);
        if (selectedRoute == next && route != null) return;
        selectedRoute = next;
        session = null;
        timerFinished = false;
        routeFinished = false;
        error = null;
        route = null;
        try {
            route = RouteLoader.load(getAssets(), selectedRoute);
        } catch (IOException e) {
            Log.e("ProUnbound", "Cannot load selected route", e);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_ROUTE_ID, next.id).apply();
    }

    public String getRouteId() { return selectedRoute.id; }
    public boolean isRouteFinished() { return routeFinished; }

    public void configureTimer(boolean enabled, int seconds) {
        if (running || seconds < 1 || seconds > SpoofingSession.MAX_TIMER_SECONDS) return;
        timerEnabled = enabled;
        timerSeconds = seconds;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_TIMER_ENABLED, enabled).putInt(PREF_TIMER_SECONDS, seconds).apply();
    }

    public int getMode() { return mode; }
    public boolean isTimerEnabled() { return timerEnabled; }
    public int getTimerSeconds() { return timerSeconds; }
    public boolean isTimerFinished() { return timerFinished; }
    public int getRemainingTimerSeconds() {
        return running && session.hasTimer()
                ? (int) ((session.remainingMillis(SystemClock.elapsedRealtime()) + 999) / 1000) : -1;
    }

    public void stopRoute() {
        running = false;
        timerFinished = false;
        routeFinished = false;
        handler.removeCallbacks(updateLocation);
        handler.removeCallbacks(autoStop);
        if (publisher != null) publisher.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        stopForeground(true);
        stopSelf();
    }

    private void fail(int message, Exception exception) {
        error = getString(message);
        Log.e("ProUnbound", error, exception);
        stopRoute();
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isReady() {
        return mode == SpoofingSession.MODE_ROUTE ? route != null : china != null;
    }

    public int getSpeedKmh() {
        return speedKmh;
    }

    public String getError() {
        if (error != null) return error;
        return isReady() ? null : getString(mode == SpoofingSession.MODE_ROUTE
                ? R.string.route_error : R.string.china_error);
    }

    public double getRouteLengthMeters() {
        return route == null ? 0 : route.lengthMeters();
    }

    public long getCompletedLaps() {
        return session == null ? 0 : session.completedLaps();
    }

    public LoopRoute.Position getPosition() {
        return session != null ? session.position() : mode == SpoofingSession.MODE_ROUTE && route != null
                ? route.positionAt(0) : null;
    }

    private Notification buildNotification() {
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FakeLocationService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        String text = mode == SpoofingSession.MODE_ROUTE
                ? selectedRoute.title + " · " + getString(R.string.notification_speed, speedKmh)
                : getString(R.string.stationary_active);
        int remaining = getRemainingTimerSeconds();
        if (remaining >= 0) text += " · " + getString(R.string.timer_remaining, remaining / 60, remaining % 60);
        return builder.setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_route_notification)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, getString(R.string.stop), stop).build())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @Override
    public void onDestroy() {
        stopRoute();
        super.onDestroy();
    }
}
