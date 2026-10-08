package kz.prounbound.route;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Offline elevations indexed by distance along the unchanged GPX track. */
public final class TerrainProfile {
    private final double[] offsets;
    private final double[] msl;
    private final double[] ellipsoid;

    private TerrainProfile(List<double[]> rows) throws IOException {
        if (rows.size() < 2 || rows.get(0)[0] != 0) throw new IOException("Invalid terrain profile start");
        offsets = new double[rows.size()];
        msl = new double[rows.size()];
        ellipsoid = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            double[] row = rows.get(i);
            for (double value : row) {
                if (!Double.isFinite(value)) throw new IOException("Invalid terrain value");
            }
            if (i > 0 && row[0] <= offsets[i-1]) throw new IOException("Unordered terrain profile");
            offsets[i] = row[0];
            msl[i] = row[1];
            ellipsoid[i] = row[2];
        }
    }

    public static TerrainProfile load(Reader input) throws IOException {
        BufferedReader reader = new BufferedReader(input);
        List<double[]> rows = new ArrayList<>();
        for (String line; (line = reader.readLine()) != null;) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] cells = line.split(",", -1);
            if (cells.length != 3) throw new IOException("Expected distance, MSL and ellipsoid height");
            try {
                rows.add(new double[]{Double.parseDouble(cells[0]), Double.parseDouble(cells[1]),
                        Double.parseDouble(cells[2])});
            } catch (NumberFormatException e) {
                throw new IOException("Invalid terrain number", e);
            }
        }
        return new TerrainProfile(rows);
    }

    public double lengthMeters() { return offsets[offsets.length-1]; }

    public Elevation elevationAt(double offsetMeters) {
        if (!Double.isFinite(offsetMeters) || offsetMeters < 0) {
            throw new IllegalArgumentException("Invalid terrain distance");
        }
        double offset = Math.min(offsetMeters, lengthMeters());
        int found = Arrays.binarySearch(offsets, offset);
        int segment = Math.min(found >= 0 ? found : -found-2, offsets.length-2);
        double fraction = (offset-offsets[segment]) / (offsets[segment+1]-offsets[segment]);
        return new Elevation(msl[segment] + fraction*(msl[segment+1]-msl[segment]),
                ellipsoid[segment] + fraction*(ellipsoid[segment+1]-ellipsoid[segment]));
    }

    public static final class Elevation {
        public final double mslMeters;
        public final double ellipsoidMeters;
        private Elevation(double mslMeters, double ellipsoidMeters) {
            this.mslMeters = mslMeters;
            this.ellipsoidMeters = ellipsoidMeters;
        }
    }
}
