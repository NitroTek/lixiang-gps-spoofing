package kz.prounbound.spoofing;

import kz.prounbound.route.LoopRoute;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Offline mainland polygon from Natural Earth. Coordinates are longitude, latitude. */
public final class ChinaRegion {
    private static final double BORDER_MARGIN_DEGREES = 0.5;
    private final double[][] ring;
    private double minLon = 180, maxLon = -180, minLat = 90, maxLat = -90;

    private ChinaRegion(double[][] ring) {
        this.ring = ring;
        for (double[] p : ring) {
            minLon = Math.min(minLon, p[0]); maxLon = Math.max(maxLon, p[0]);
            minLat = Math.min(minLat, p[1]); maxLat = Math.max(maxLat, p[1]);
        }
    }

    public static ChinaRegion load(Reader input) throws IOException {
        BufferedReader reader = new BufferedReader(input);
        List<double[]> points = new ArrayList<>();
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] values = line.split(",");
                if (values.length != 2) throw new IOException("Invalid country boundary");
                double lon = Double.parseDouble(values[0]), lat = Double.parseDouble(values[1]);
                if (Double.isNaN(lon) || Double.isInfinite(lon) || Math.abs(lon) > 180
                        || Double.isNaN(lat) || Double.isInfinite(lat) || Math.abs(lat) > 90) {
                    throw new IOException("Invalid boundary coordinate");
                }
                points.add(new double[]{lon, lat});
            }
        } catch (NumberFormatException e) {
            throw new IOException("Invalid country boundary", e);
        }
        if (points.size() < 4) throw new IOException("Country boundary is empty");
        double[] a = points.get(0), b = points.get(points.size() - 1);
        if (a[0] != b[0] || a[1] != b[1]) throw new IOException("Country boundary is open");
        return new ChinaRegion(points.toArray(new double[0][]));
    }

    public boolean contains(double latitude, double longitude) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double[] a = ring[i], b = ring[j];
            if ((a[1] > latitude) != (b[1] > latitude)
                    && longitude < (b[0] - a[0]) * (latitude - a[1]) / (b[1] - a[1]) + a[0]) {
                inside = !inside;
            }
        }
        return inside;
    }

    public boolean isInterior(double latitude, double longitude) {
        if (!contains(latitude, longitude)) return false;
        for (int i = 1; i < ring.length; i++) {
            double[] a = ring[i - 1], b = ring[i];
            double dx = b[0] - a[0], dy = b[1] - a[1];
            double squared = dx * dx + dy * dy;
            double t = squared == 0 ? 0 : Math.max(0, Math.min(1,
                    ((longitude - a[0]) * dx + (latitude - a[1]) * dy) / squared));
            double x = longitude - (a[0] + t * dx), y = latitude - (a[1] + t * dy);
            if (x * x + y * y < BORDER_MARGIN_DEGREES * BORDER_MARGIN_DEGREES) return false;
        }
        return true;
    }

    public LoopRoute.Position randomPosition(Random random) {
        double sinMin = Math.sin(Math.toRadians(minLat)), sinMax = Math.sin(Math.toRadians(maxLat));
        for (int attempt = 0; attempt < 10_000; attempt++) {
            double lon = minLon + random.nextDouble() * (maxLon - minLon);
            double lat = Math.toDegrees(Math.asin(sinMin + random.nextDouble() * (sinMax - sinMin)));
            if (isInterior(lat, lon)) return LoopRoute.stationaryAt(lat, lon);
        }
        throw new IllegalStateException("Cannot select an interior point");
    }
}
