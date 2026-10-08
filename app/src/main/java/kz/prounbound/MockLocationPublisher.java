package kz.prounbound;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import kz.prounbound.route.LoopRoute;

import java.util.ArrayList;
import java.util.List;

/** Owns and removes only the test providers created by this run. */
final class MockLocationPublisher {
    private final LocationManager manager;
    private final List<String> providers = new ArrayList<>();

    MockLocationPublisher(Context context) {
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    void start() {
        try {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER}) {
                if (Build.VERSION.SDK_INT >= 31) {
                    manager.addTestProvider(provider, new ProviderProperties.Builder()
                            .setHasBearingSupport(true)
                            .setHasSpeedSupport(true)
                            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                            .setAccuracy(ProviderProperties.ACCURACY_FINE)
                            .build());
                } else {
                    addLegacyProvider(provider);
                }
                providers.add(provider);
                manager.setTestProviderEnabled(provider, true);
            }
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    // Before API 31 this overload uses Criteria constants, with identical integer values.
    @SuppressLint("WrongConstant")
    @SuppressWarnings("deprecation")
    private void addLegacyProvider(String provider) {
        manager.addTestProvider(provider, false, false, false, false,
                false, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
    }

    void publish(LoopRoute.Position position, int speedKmh, int accuracyMeters) {
        long now = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtimeNanos();
        for (String provider : providers) {
            Location location = new Location(provider);
            location.setLatitude(position.latitude);
            location.setLongitude(position.longitude);
            location.setAccuracy(accuracyMeters);
            location.setSpeed(speedKmh / 3.6f);
            location.setBearing(position.bearing);
            location.setTime(now);
            location.setElapsedRealtimeNanos(elapsed);
            manager.setTestProviderLocation(provider, location);
        }
    }

    void stop() {
        for (String provider : providers) {
            try {
                manager.removeTestProvider(provider);
            } catch (RuntimeException e) {
                Log.w("FakeGPS", "Cannot remove test provider: " + provider, e);
            }
        }
        providers.clear();
    }
}
