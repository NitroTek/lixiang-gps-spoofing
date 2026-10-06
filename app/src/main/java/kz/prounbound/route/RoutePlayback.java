package kz.prounbound.route;

/** Movement uses monotonic elapsed time, independent of the GPS update interval. */
public final class RoutePlayback {
    public static final int DEFAULT_SPEED_KMH = 90;
    public static final int MAX_SPEED_KMH = 200;
    private final LoopRoute route;
    private long previousMillis;
    private double traveledMeters;
    private int speedKmh;

    public RoutePlayback(LoopRoute route, int speedKmh, long nowMillis) {
        validateSpeed(speedKmh);
        this.route = route;
        this.speedKmh = speedKmh;
        previousMillis = nowMillis;
    }

    public void advanceTo(long nowMillis) {
        if (nowMillis < previousMillis) {
            throw new IllegalArgumentException("Time must be monotonic");
        }
        traveledMeters += (nowMillis - previousMillis) / 1000.0 * speedKmh / 3.6;
        previousMillis = nowMillis;
    }

    public void setSpeedKmh(int speedKmh, long nowMillis) {
        validateSpeed(speedKmh);
        advanceTo(nowMillis);
        this.speedKmh = speedKmh;
    }

    public int speedKmh() {
        return speedKmh;
    }

    public long completedLaps() {
        return (long) Math.floor(traveledMeters / route.lengthMeters());
    }

    public LoopRoute.Position position() {
        return route.positionAt(traveledMeters);
    }

    private static void validateSpeed(int speedKmh) {
        if (speedKmh < 0 || speedKmh > MAX_SPEED_KMH) {
            throw new IllegalArgumentException("Speed must be between 0 and 200 km/h");
        }
    }
}
