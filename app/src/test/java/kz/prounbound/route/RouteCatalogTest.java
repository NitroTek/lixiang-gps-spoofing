package kz.prounbound.route;

import kz.prounbound.spoofing.SpoofingSession;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.Assert.*;

public class RouteCatalogTest {
    private LoopRoute load(RouteCatalog.Entry entry) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/" + entry.assetName())) {
            assertNotNull(entry.assetName(), input);
            NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(input).getElementsByTagName("trkpt");
            double[][] points = new double[nodes.getLength()][2];
            for (int i = 0; i < points.length; i++) {
                Element p = (Element) nodes.item(i);
                points[i][0] = Double.parseDouble(p.getAttribute("lat"));
                points[i][1] = Double.parseDouble(p.getAttribute("lon"));
            }
            return new LoopRoute(points, entry.closed);
        }
    }

    @Test public void tenDistinctThirtyKilometerSprintsFinishAfterFifteenMinutesAt120() throws Exception {
        assertEquals(11, RouteCatalog.size());
        Set<String> roads = new HashSet<>(), starts = new HashSet<>();
        for (int i = 1; i < RouteCatalog.size(); i++) {
            RouteCatalog.Entry entry = RouteCatalog.at(i);
            LoopRoute route = load(entry);
            assertFalse(route.isClosed());
            assertEquals(30_000, route.lengthMeters(), .001);
            assertTrue(roads.add(entry.road));
            LoopRoute.Position start = route.positionAt(0);
            assertTrue(starts.add(start.latitude + "," + start.longitude));
            RoutePlayback run = new RoutePlayback(route, 120, 0);
            run.advanceTo(899_000);
            assertFalse(run.isFinished());
            run.advanceTo(900_001);
            assertTrue(run.isFinished());
            LoopRoute.Position finish = run.position();
            assertEquals(route.lengthMeters(), finish.offsetMeters, .00001);
            assertEquals(0, run.completedLaps());
            run.advanceTo(3_600_000);
            assertEquals(finish.latitude, run.position().latitude, 0);
            assertEquals(finish.longitude, run.position().longitude, 0);
            for (double d = 0; d <= route.lengthMeters(); d += 25) {
                LoopRoute.Position p = route.positionAt(d);
                assertTrue(p.latitude > 27 && p.latitude < 36);
                assertTrue(p.longitude > 115 && p.longitude < 123);
                assertTrue(p.bearing >= 0 && p.bearing < 360);
            }
        }
    }

    @Test public void timerCanStopBeforeSprintFinishesAndSpeedChangeDoesNotResetIt() throws Exception {
        SpoofingSession run = new SpoofingSession(load(RouteCatalog.at(1)), null,
                SpoofingSession.MODE_ROUTE, 120, 300, new Random(1), 0);
        run.advanceTo(150_000);
        assertEquals(5000, run.position().offsetMeters, .001);
        run.setSpeedKmh(60, 150_000);
        run.advanceTo(300_000);
        assertEquals(7500, run.position().offsetMeters, .001);
        assertTrue(run.isExpired(300_000));
        assertFalse(run.isRouteFinished());
    }

    @Test public void originalG30StillLoops() throws Exception {
        LoopRoute route = load(RouteCatalog.find("g30-loop"));
        assertTrue(route.isClosed());
        RoutePlayback run = new RoutePlayback(route, 120, 0);
        run.advanceTo(10_000_000);
        assertFalse(run.isFinished());
        assertTrue(run.completedLaps() > 1);
    }

    @Test public void randomSelectionCoversAllSprintsAndNeverPicksG30OrCurrentSprint() {
        Random random = new Random(17);
        Set<String> chosen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            RouteCatalog.Entry entry = RouteCatalog.randomSprint(random, "g30-loop");
            assertFalse(entry.closed);
            chosen.add(entry.id);
            assertNotEquals(entry.id, RouteCatalog.randomSprint(random, entry.id).id);
        }
        assertEquals(10, chosen.size());
    }

    @Test public void missingSavedIdFallsBackToG30() {
        assertSame(RouteCatalog.at(0), RouteCatalog.find("removed-route"));
        assertSame(RouteCatalog.at(0), RouteCatalog.find(null));
    }

    @Test public void zeroSpeedHoldsAnOpenTrackAndResumeContinuesFromThere() throws Exception {
        RoutePlayback run = new RoutePlayback(load(RouteCatalog.at(1)), 120, 0);
        run.setSpeedKmh(0, 30_000);
        run.advanceTo(300_000);
        assertEquals(1000, run.position().offsetMeters, .00001);
        assertFalse(run.isFinished());
        run.setSpeedKmh(60, 300_000);
        run.advanceTo(360_000);
        assertEquals(2000, run.position().offsetMeters, .00001);
    }
}
