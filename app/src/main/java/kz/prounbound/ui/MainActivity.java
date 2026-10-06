package kz.prounbound.ui;

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
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import kz.prounbound.FakeLocationService;
import kz.prounbound.R;
import kz.prounbound.route.LoopRoute;
import kz.prounbound.route.RoutePlayback;
import kz.prounbound.spoofing.SpoofingSession;

/** Mode and optional auto-stop settings on one screen. */
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
    private Spinner mode;
    private Switch timerEnabled;
    private SeekBar timerDuration;
    private TextView timerLabel;
    private TextView timerCountdown;
    private TextView modeDescription;
    private View speedControls;
    private View timerControls;
    private boolean syncingControls;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((FakeLocationService.LocalBinder) binder).getService();
            syncingControls = true;
            speed.setProgress(service.getSpeedKmh());
            mode.setSelection(service.getMode());
            timerEnabled.setChecked(service.isTimerEnabled());
            timerDuration.setProgress(service.getTimerSeconds() - 1);
            syncingControls = false;
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
        mode = findViewById(R.id.mode);
        modeDescription = findViewById(R.id.mode_description);
        speedControls = findViewById(R.id.speed_controls);
        timerEnabled = findViewById(R.id.timer_enabled);
        timerDuration = findViewById(R.id.timer_duration);
        timerLabel = findViewById(R.id.timer_label);
        timerCountdown = findViewById(R.id.timer_countdown);
        timerControls = findViewById(R.id.timer_controls);
        ArrayAdapter<CharSequence> modes = ArrayAdapter.createFromResource(this,
                R.array.spoofing_modes, android.R.layout.simple_spinner_item);
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(modes);
        SharedPreferences prefs = getSharedPreferences(FakeLocationService.PREFS, MODE_PRIVATE);
        int savedMode = prefs.getInt(FakeLocationService.PREF_MODE, SpoofingSession.MODE_ROUTE);
        mode.setSelection(SpoofingSession.isValidMode(savedMode) ? savedMode : SpoofingSession.MODE_ROUTE);
        timerEnabled.setChecked(prefs.getBoolean(FakeLocationService.PREF_TIMER_ENABLED, false));
        timerDuration.setProgress(Math.max(1, Math.min(SpoofingSession.MAX_TIMER_SECONDS,
                prefs.getInt(FakeLocationService.PREF_TIMER_SECONDS, 60))) - 1);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (syncingControls || service != null && service.isRunning()) return;
                if (service != null) service.configureMode(position);
                else prefs.edit().putInt(FakeLocationService.PREF_MODE, position).apply();
                updateScreen();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        timerEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (!syncingControls) saveTimerOptions();
            updateScreen();
        });
        timerDuration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) saveTimerOptions();
                updateTimerControls();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        updateScreen();
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

    private void saveTimerOptions() {
        int seconds = timerDuration.getProgress() + 1;
        if (service != null) service.configureTimer(timerEnabled.isChecked(), seconds);
        else getSharedPreferences(FakeLocationService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(FakeLocationService.PREF_TIMER_ENABLED, timerEnabled.isChecked())
                .putInt(FakeLocationService.PREF_TIMER_SECONDS, seconds).apply();
    }

    private void updateTimerControls() {
        int seconds = timerDuration.getProgress() + 1;
        timerLabel.setText(getString(R.string.timer_duration, seconds / 60, seconds % 60));
        timerControls.setVisibility(timerEnabled.isChecked() ? View.VISIBLE : View.GONE);
    }

    private void updateScreen() {
        int selectedMode = service == null ? mode.getSelectedItemPosition() : service.getMode();
        boolean stationary = selectedMode == SpoofingSession.MODE_STATIONARY_CHINA;
        speedControls.setVisibility(stationary ? View.GONE : View.VISIBLE);
        routeProgress.setVisibility(stationary ? View.GONE : View.VISIBLE);
        progressLabel.setVisibility(stationary ? View.GONE : View.VISIBLE);
        modeDescription.setText(stationary ? R.string.stationary_description : R.string.route_description);
        updateTimerControls();
        if (service == null) return;
        boolean running = service.isRunning();
        mode.setEnabled(!running);
        timerEnabled.setEnabled(!running);
        timerDuration.setEnabled(!running);
        if (mode.getSelectedItemPosition() != selectedMode) mode.setSelection(selectedMode);
        startStop.setEnabled(service.isReady());
        startStop.setText(running ? R.string.stop : R.string.start);
        String error = service.getError();
        if (error != null) status.setText(error);
        else status.setText(!running
                ? service.isTimerFinished() ? R.string.timer_finished : R.string.idle
                : stationary ? R.string.stationary_active
                : service.getSpeedKmh() == 0 ? R.string.paused : R.string.running);
        int remaining = service.getRemainingTimerSeconds();
        timerCountdown.setVisibility(remaining >= 0 ? View.VISIBLE : View.GONE);
        if (remaining >= 0) timerCountdown.setText(getString(R.string.timer_remaining,
                remaining / 60, remaining % 60));
        LoopRoute.Position position = service.getPosition();
        if (position != null) {
            if (!stationary) {
                double length = service.getRouteLengthMeters();
                if (length > 0) {
                    routeProgress.setProgress((int) (position.offsetMeters / length * 1000));
                    progressLabel.setText(getString(R.string.progress, service.getCompletedLaps() + 1,
                            position.offsetMeters / 1000, length / 1000));
                }
            }
            coordinates.setText(getString(R.string.coordinates, position.latitude, position.longitude));
        } else coordinates.setText(stationary ? R.string.stationary_pending : R.string.loading);
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
                .putExtra(FakeLocationService.EXTRA_SPEED, speed.getProgress())
                .putExtra(FakeLocationService.EXTRA_MODE, mode.getSelectedItemPosition())
                .putExtra(FakeLocationService.EXTRA_TIMER_SECONDS,
                        timerEnabled.isChecked() ? timerDuration.getProgress() + 1 : 0);
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
