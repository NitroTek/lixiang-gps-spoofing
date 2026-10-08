package kz.prounbound.route;

import org.junit.Test;
import java.io.IOException;
import java.io.StringReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.Assert.*;

public class TerrainProfileTest {
    private TerrainProfile profile(String csv) throws IOException {
        return TerrainProfile.load(new StringReader(csv));
    }

    @Test public void interpolatesBothHeightDatumsAndClampsAtFinish() throws Exception {
        TerrainProfile p = profile("# profile\n\n0,-10,20\n100,30,70\n200,50,100\n");
        assertEquals(10, p.elevationAt(50).mslMeters, 1e-9);
        assertEquals(45, p.elevationAt(50).ellipsoidMeters, 1e-9);
        assertEquals(30, p.elevationAt(100).mslMeters, 1e-9);
        assertEquals(100, p.elevationAt(300).ellipsoidMeters, 1e-9);
    }

    @Test public void rejectsCorruptProfiles() throws Exception {
        for (String csv : new String[]{"", "0,1,2", "1,1,2\n2,2,3",
                "0,1,2\n0,2,3", "0,1,2\n-1,2,3", "0,1,2\n10,NaN,3",
                "0,1,2\n10,2,Infinity", "0,1,2\n10,2", "0,1,2\n10,no,3"}) {
            try { profile(csv); fail("Accepted corrupt profile: " + csv); }
            catch (IOException expected) { }
        }
    }

    @Test public void openRouteRetainsGeometryAndStopsAtFinalHeight() throws Exception {
        double[][] coordinates = {{43, 90}, {43, 90.01}};
        LoopRoute plain = new LoopRoute(coordinates, false);
        LoopRoute route = new LoopRoute(coordinates, false,
                profile("0,100,120\n" + plain.lengthMeters() + ",200,230"));
        LoopRoute.Position middle = route.positionAt(route.lengthMeters()/2);
        assertEquals(plain.positionAt(plain.lengthMeters()/2).longitude, middle.longitude, 1e-12);
        assertEquals(150, middle.altitudeMslMeters, 1e-9);
        assertEquals(175, middle.altitudeEllipsoidMeters, 1e-9);
        assertEquals(230, route.positionAt(route.lengthMeters()*2).altitudeEllipsoidMeters, 1e-9);
    }

    @Test public void closedLoopHasContinuousHeightAcrossLaps() throws Exception {
        double[][] coordinates = {{43,90}, {43,90.01}, {43.01,90}, {43,90}};
        LoopRoute plain = new LoopRoute(coordinates);
        double length = plain.lengthMeters();
        LoopRoute route = new LoopRoute(coordinates, true,
                profile("0,100,120\n" + length/2 + ",200,230\n" + length + ",100,120"));
        assertEquals(120, route.positionAt(length).altitudeEllipsoidMeters, 1e-9);
        assertEquals(route.positionAt(50).altitudeEllipsoidMeters,
                route.positionAt(length+50).altitudeEllipsoidMeters, 1e-9);
        assertEquals(route.positionAt(length-0.01).altitudeMslMeters,
                route.positionAt(length+0.01).altitudeMslMeters, 1e-5);
    }

    @Test public void rejectsProfileForDifferentTrackOrUnclosedHeights() throws Exception {
        double[][] coordinates = {{0,0}, {0,0.01}, {0.01,0}, {0,0}};
        double length = new LoopRoute(coordinates).lengthMeters();
        for (String csv : new String[]{"0,100,120\n10,100,120",
                "0,100,120\n" + length + ",101,120",
                "0,100,120\n" + length + ",100,121"}) {
            try { new LoopRoute(coordinates, true, profile(csv)); fail("Accepted mismatched profile"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void absentTerrainRemainsUnknown() {
        assertFalse(LoopRoute.stationaryAt(43,90).hasAltitude());
        assertFalse(new LoopRoute(new double[][]{{43,90}, {43,90.01}}, false)
                .positionAt(50).hasAltitude());
    }

    @Test public void everyBundledRouteHasMatchingTraversableTerrain() throws Exception {
        for (int index = 0; index < RouteCatalog.size(); index++) {
            RouteCatalog.Entry entry = RouteCatalog.at(index);
            TerrainProfile terrain;
            try (InputStream input = getClass().getResourceAsStream("/" + entry.id + "-altitude.csv")) {
                assertNotNull("Missing terrain: " + entry.id, input);
                terrain = TerrainProfile.load(new InputStreamReader(input, "UTF-8"));
            }
            double[][] coordinates;
            try (InputStream input = getClass().getResourceAsStream("/" + entry.assetName())) {
                assertNotNull(input);
                NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(input).getElementsByTagName("trkpt");
                coordinates = new double[nodes.getLength()][2];
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element point = (Element) nodes.item(i);
                    coordinates[i][0] = Double.parseDouble(point.getAttribute("lat"));
                    coordinates[i][1] = Double.parseDouble(point.getAttribute("lon"));
                }
            }
            LoopRoute route = new LoopRoute(coordinates, entry.closed, terrain);
            for (double d = 0; d <= route.lengthMeters(); d += 25) {
                LoopRoute.Position p = route.positionAt(d);
                assertTrue(p.hasAltitude());
                assertTrue(Double.isFinite(p.altitudeMslMeters));
                assertTrue(Math.abs(p.altitudeEllipsoidMeters-p.altitudeMslMeters) < 110);
            }
        }
    }
}
