package com.github.fakegps;

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

import com.github.fakegps.route.LoopRoute;
import com.github.fakegps.route.RouteLoader;
import com.github.fakegps.route.RoutePlayback;
import com.github.fakegps.ui.MainActivity;

import java.io.IOException;

/** The service owns playback; closing or rotating the activity never stops the route. */
public final class FakeLocationService extends Service {
    public static final String ACTION_START = "com.github.fakegps.START";
    public static final String ACTION_STOP = "com.github.fakegps.STOP";
    public static final String EXTRA_SPEED = "speed_kmh";
    public static final String PREFS = "g30";
    public static final String PREF_SPEED = "speed_kmh";
    private static final String CHANNEL_ID = "g30_route";
    private static final int NOTIFICATION_ID = 1001;
    private static final long UPDATE_INTERVAL_MS = 250;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private LoopRoute route;
    private RoutePlayback playback;
    private MockLocationPublisher publisher;
    private PowerManager.WakeLock wakeLock;
    private long wakeLockRenewedAt;
    private boolean running;
    private int speedKmh;
    private String error;

    public final class LocalBinder extends Binder {
        public FakeLocationService getService() {
            return FakeLocationService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        speedKmh = Math.max(0, Math.min(RoutePlayback.MAX_SPEED_KMH,
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getInt(PREF_SPEED, RoutePlayback.DEFAULT_SPEED_KMH)));
        publisher = new MockLocationPublisher(this);
        try {
            route = RouteLoader.load(getAssets());
        } catch (IOException e) {
            error = getString(R.string.route_error);
            Log.e("FakeGPS", error, e);
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
            startRoute(intent.getIntExtra(EXTRA_SPEED, speedKmh));
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

    private void startRoute(int requestedSpeed) {
        if (running) {
            setSpeedKmh(requestedSpeed);
            return;
        }
        error = null;
        try {
            setSpeedKmh(requestedSpeed);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            if (route == null) throw new IOException("Embedded route is unavailable");
            publisher.start();
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FakeGPS:G30");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(60_000);
            wakeLockRenewedAt = SystemClock.elapsedRealtime();
            playback = new RoutePlayback(route, speedKmh, SystemClock.elapsedRealtime());
            running = true;
            handler.post(updateLocation);
        } catch (IOException e) {
            fail(R.string.route_error, e);
        } catch (SecurityException e) {
            fail(R.string.mock_error, e);
        } catch (RuntimeException e) {
            fail(R.string.service_error, e);
        }
    }

    public void setSpeedKmh(int value) {
        if (value < 0 || value > RoutePlayback.MAX_SPEED_KMH) return;
        if (running) playback.setSpeedKmh(value, SystemClock.elapsedRealtime());
        speedKmh = value;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_SPEED, value).apply();
        if (running) {
            try {
                // The reported GPS speed changes immediately, without resetting position.
                publisher.publish(playback.position(), speedKmh);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                        .notify(NOTIFICATION_ID, buildNotification());
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
                long now = SystemClock.elapsedRealtime();
                // Renew during playback; a stalled update loop releases the lock automatically.
                if (now - wakeLockRenewedAt >= 30_000) {
                    wakeLock.acquire(60_000);
                    wakeLockRenewedAt = now;
                }
                playback.advanceTo(now);
                publisher.publish(playback.position(), speedKmh);
                handler.postDelayed(this, UPDATE_INTERVAL_MS);
            } catch (RuntimeException e) {
                fail(R.string.mock_error, e);
            }
        }
    };

    public void stopRoute() {
        running = false;
        handler.removeCallbacks(updateLocation);
        if (publisher != null) publisher.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        stopForeground(true);
        stopSelf();
    }

    private void fail(int message, Exception exception) {
        error = getString(message);
        Log.e("FakeGPS", error, exception);
        stopRoute();
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isRouteLoaded() {
        return route != null;
    }

    public int getSpeedKmh() {
        return speedKmh;
    }

    public String getError() {
        return error;
    }

    public double getRouteLengthMeters() {
        return route == null ? 0 : route.lengthMeters();
    }

    public long getCompletedLaps() {
        return playback == null ? 0 : playback.completedLaps();
    }

    public LoopRoute.Position getPosition() {
        return playback != null ? playback.position() : route != null ? route.positionAt(0) : null;
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
        return builder.setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_speed, speedKmh))
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
