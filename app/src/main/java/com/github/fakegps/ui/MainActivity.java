package com.github.fakegps.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.github.fakegps.FakeLocationService;
import com.github.fakegps.R;
import com.github.fakegps.route.LoopRoute;
import com.github.fakegps.route.RoutePlayback;

/** One screen, one route, one speed control. */
public final class MainActivity extends Activity {
    private static final int REQUEST_LOCATION = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FakeLocationService service;
    private boolean bound;
    private boolean pendingStart;
    private SeekBar speed;
    private TextView speedLabel;
    private TextView status;
    private TextView progressLabel;
    private TextView coordinates;
    private ProgressBar routeProgress;
    private Button startStop;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((FakeLocationService.LocalBinder) binder).getService();
            speed.setProgress(service.getSpeedKmh());
            updateScreen();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            startStop.setEnabled(false);
            status.setText(R.string.service_error);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        speed = findViewById(R.id.speed);
        speedLabel = findViewById(R.id.speed_label);
        status = findViewById(R.id.status);
        progressLabel = findViewById(R.id.progress_label);
        coordinates = findViewById(R.id.coordinates);
        routeProgress = findViewById(R.id.route_progress);
        startStop = findViewById(R.id.start_stop);
        int savedSpeed = getSharedPreferences(FakeLocationService.PREFS, MODE_PRIVATE)
                .getInt(FakeLocationService.PREF_SPEED, RoutePlayback.DEFAULT_SPEED_KMH);
        speed.setProgress(savedSpeed);
        speedLabel.setText(getString(R.string.speed_label, speed.getProgress()));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                speedLabel.setText(getString(R.string.speed_label, value));
                if (fromUser) {
                    if (service != null) service.setSpeedKmh(value);
                    else getSharedPreferences(FakeLocationService.PREFS, MODE_PRIVATE)
                            .edit().putInt(FakeLocationService.PREF_SPEED, value).apply();
                    updateScreen();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        startStop.setOnClickListener(view -> {
            if (service == null) return;
            if (service.isRunning()) {
                pendingStart = false;
                service.stopRoute();
                updateScreen();
            } else {
                pendingStart = true;
                checkPermissionsAndStart();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, FakeLocationService.class), connection, BIND_AUTO_CREATE);
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refresh);
        if (bound) unbindService(connection);
        bound = false;
        service = null;
        super.onStop();
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateScreen();
            handler.postDelayed(this, 500);
        }
    };

    private void updateScreen() {
        if (service == null) return;
        boolean running = service.isRunning();
        startStop.setEnabled(service.isRouteLoaded());
        startStop.setText(running ? R.string.stop : R.string.start);
        String error = service.getError();
        if (error != null) status.setText(error);
        else status.setText(!running ? R.string.idle
                : service.getSpeedKmh() == 0 ? R.string.paused : R.string.running);
        LoopRoute.Position position = service.getPosition();
        if (position != null) {
            double length = service.getRouteLengthMeters();
            routeProgress.setProgress((int) (position.offsetMeters / length * 1000));
            progressLabel.setText(getString(R.string.progress, service.getCompletedLaps() + 1,
                    position.offsetMeters / 1000, length / 1000));
            coordinates.setText(getString(R.string.coordinates, position.latitude, position.longitude));
        }
    }

    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissionsAndStart() {
        if (!pendingStart) return;
        SharedPreferences prefs = getSharedPreferences(FakeLocationService.PREFS, MODE_PRIVATE);
        if (!hasLocationPermission()) {
            if (prefs.getBoolean("location_asked", false)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                pendingStart = false;
                showSettingsDialog(R.string.location_denied, R.string.permission_settings,
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                return;
            }
            prefs.edit().putBoolean("location_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        LocationManager locations = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean locationEnabled = Build.VERSION.SDK_INT >= 28 ? locations.isLocationEnabled()
                : locations.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (!locationEnabled) {
            pendingStart = false;
            showSettingsDialog(R.string.location_title, R.string.location_hint,
                    new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        if (appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(),
                getPackageName()) != AppOpsManager.MODE_ALLOWED) {
            pendingStart = false;
            showSettingsDialog(R.string.mock_title, R.string.setup_hint,
                    new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("notifications_asked", false)) {
            prefs.edit().putBoolean("notifications_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        pendingStart = false;
        Intent intent = new Intent(this, FakeLocationService.class)
                .setAction(FakeLocationService.ACTION_START)
                .putExtra(FakeLocationService.EXTRA_SPEED, speed.getProgress());
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.service_error, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_LOCATION && !hasLocationPermission()) {
            pendingStart = false;
            Toast.makeText(this, R.string.location_denied, Toast.LENGTH_LONG).show();
        } else if (requestCode == REQUEST_LOCATION || requestCode == REQUEST_NOTIFICATIONS) {
            // Denying notifications must not loop the permission dialog or block playback.
            checkPermissionsAndStart();
        }
    }

    private void showSettingsDialog(int title, int message, Intent settings) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton(R.string.settings, (dialog, which) -> {
                    try {
                        startActivity(settings);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }
}
