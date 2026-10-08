package kz.prounbound.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Closed, distance-indexed track. Coordinates are latitude, longitude. */
public final class LoopRoute {
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private final double[][] points;
    private final double[] distances;
    private final boolean closed;

    public LoopRoute(double[][] coordinates) {
        this(coordinates, true);
    }

    public LoopRoute(double[][] coordinates, boolean closed) {
        this.closed = closed;
        List<double[]> unique = new ArrayList<>();
        for (double[] point : coordinates) {
            if (point.length != 2 || Double.isNaN(point[0]) || Double.isInfinite(point[0])
                    || Double.isNaN(point[1]) || Double.isInfinite(point[1])
                    || Math.abs(point[0]) > 90 || Math.abs(point[1]) > 180) {
                throw new IllegalArgumentException("Invalid route coordinate");
            }
            if (unique.isEmpty() || distance(unique.get(unique.size() - 1), point) > 0.001) {
                unique.add(point.clone());
            }
        }
        if (unique.size() < (closed ? 3 : 2)
                || closed && distance(unique.get(0), unique.get(unique.size() - 1)) > 0.01) {
            throw new IllegalArgumentException("Track must be a closed loop");
        }
        points = unique.toArray(new double[0][]);
        if (closed) points[points.length - 1] = points[0].clone();
        distances = new double[points.length];
        for (int i = 1; i < points.length; i++) {
            distances[i] = distances[i - 1] + distance(points[i - 1], points[i]);
        }
    }

    public double lengthMeters() {
        return distances[distances.length - 1];
    }

    public boolean isClosed() { return closed; }

    public static Position stationaryAt(double latitude, double longitude) {
        if (Double.isNaN(latitude) || Double.isInfinite(latitude)
                || Double.isNaN(longitude) || Double.isInfinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw new IllegalArgumentException("Invalid stationary coordinate");
        }
        return new Position(latitude, longitude, 0, 0);
    }

    public Position positionAt(double traveledMeters) {
        if (Double.isNaN(traveledMeters) || Double.isInfinite(traveledMeters) || traveledMeters < 0) {
            throw new IllegalArgumentException("Invalid traveled distance");
        }
        double offset = closed ? traveledMeters % lengthMeters() : Math.min(traveledMeters, lengthMeters());
        int found = Arrays.binarySearch(distances, offset);
        int segment = found >= 0 ? found : -found - 2;
        segment = Math.min(segment, points.length - 2);
        double fraction = (offset - distances[segment])
                / (distances[segment + 1] - distances[segment]);
        double[] a = points[segment];
        double[] b = points[segment + 1];
        double lat1 = Math.toRadians(a[0]);
        double lon1 = Math.toRadians(a[1]);
        double lat2 = Math.toRadians(b[0]);
        double lon2 = Math.toRadians(b[1]);
        double angle = (distances[segment + 1] - distances[segment]) / EARTH_RADIUS_METERS;
        double weightA = Math.sin((1 - fraction) * angle) / Math.sin(angle);
        double weightB = Math.sin(fraction * angle) / Math.sin(angle);
        double x = weightA * Math.cos(lat1) * Math.cos(lon1)
                + weightB * Math.cos(lat2) * Math.cos(lon2);
        double y = weightA * Math.cos(lat1) * Math.sin(lon1)
                + weightB * Math.cos(lat2) * Math.sin(lon2);
        double z = weightA * Math.sin(lat1) + weightB * Math.sin(lat2);
        double latitude = Math.toDegrees(Math.atan2(z, Math.hypot(x, y)));
        double longitude = Math.toDegrees(Math.atan2(y, x));
        double bearing = Math.toDegrees(Math.atan2(
                Math.sin(lon2 - lon1) * Math.cos(lat2),
                Math.cos(lat1) * Math.sin(lat2)
                        - Math.sin(lat1) * Math.cos(lat2) * Math.cos(lon2 - lon1)));
        return new Position(latitude, longitude, (float) ((bearing + 360) % 360), offset);
    }

    private static double distance(double[] a, double[] b) {
        double lat1 = Math.toRadians(a[0]);
        double lat2 = Math.toRadians(b[0]);
        double sinLat = Math.sin((lat2 - lat1) / 2);
        double sinLon = Math.sin(Math.toRadians(b[1] - a[1]) / 2);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1, h)));
    }

    public static final class Position {
        public final double latitude;
        public final double longitude;
        public final float bearing;
        public final double offsetMeters;

        private Position(double latitude, double longitude, float bearing, double offsetMeters) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.bearing = bearing;
            this.offsetMeters = offsetMeters;
        }
    }
}
