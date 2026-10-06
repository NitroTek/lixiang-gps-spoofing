package kz.prounbound.spoofing;

import kz.prounbound.route.LoopRoute;
import kz.prounbound.route.RoutePlayback;

import java.util.Random;

/** A single run. Mode, stationary point and elapsed-time deadline never change mid-run. */
public final class SpoofingSession {
    public static final int MODE_ROUTE = 0;
    public static final int MODE_STATIONARY_CHINA = 1;
    public static final int MAX_TIMER_SECONDS = 300;
    private final RoutePlayback playback;
    private final LoopRoute.Position stationaryPosition;
    private final long stopAtMillis;
    private final boolean timerEnabled;

    public SpoofingSession(LoopRoute route, ChinaRegion china, int mode, int speedKmh,
                           int timerSeconds, Random random, long nowMillis) {
        if (!isValidMode(mode)) throw new IllegalArgumentException("Unknown spoofing mode");
        if (speedKmh < 0 || speedKmh > RoutePlayback.MAX_SPEED_KMH) {
            throw new IllegalArgumentException("Invalid speed");
        }
        if (timerSeconds < 0 || timerSeconds > MAX_TIMER_SECONDS) {
            throw new IllegalArgumentException("Timer must be 0 to 300 seconds");
        }
        timerEnabled = timerSeconds > 0;
        stopAtMillis = nowMillis + timerSeconds * 1000L;
        if (mode == MODE_ROUTE) {
            if (route == null) throw new IllegalArgumentException("Route is unavailable");
            playback = new RoutePlayback(route, speedKmh, nowMillis);
            stationaryPosition = null;
        } else {
            if (china == null) throw new IllegalArgumentException("China boundary is unavailable");
            playback = null;
            stationaryPosition = china.randomPosition(random);
        }
    }

    public static boolean isValidMode(int mode) {
        return mode == MODE_ROUTE || mode == MODE_STATIONARY_CHINA;
    }

    public void advanceTo(long nowMillis) {
        if (playback != null) playback.advanceTo(nowMillis);
    }

    public void setSpeedKmh(int speedKmh, long nowMillis) {
        if (playback != null) playback.setSpeedKmh(speedKmh, nowMillis);
    }

    public int speedKmh() { return playback == null ? 0 : playback.speedKmh(); }
    public long completedLaps() { return playback == null ? 0 : playback.completedLaps(); }
    public LoopRoute.Position position() { return playback == null ? stationaryPosition : playback.position(); }
    public boolean hasTimer() { return timerEnabled; }
    public boolean isExpired(long nowMillis) { return timerEnabled && nowMillis >= stopAtMillis; }
    public long remainingMillis(long nowMillis) {
        return timerEnabled ? Math.max(0, stopAtMillis - nowMillis) : 0;
    }
}
