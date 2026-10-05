package com.github.fakegps.route;

import org.junit.Test;

import java.io.InputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.Assert.*;

public class RoutePlaybackTest {
    private LoopRoute square() {
        return new LoopRoute(new double[][]{
                {0, 0}, {0, 0.01}, {0.01, 0.01}, {0.01, 0}, {0, 0}});
    }

    @Test
    public void kilometersPerHourDetermineDistanceNotTickCount() {
        RoutePlayback sparse = new RoutePlayback(square(), 36, 1000);
        RoutePlayback frequent = new RoutePlayback(square(), 36, 1000);
        sparse.advanceTo(11_000);
        for (int t = 1250; t <= 11_000; t += 250) frequent.advanceTo(t);
        assertEquals(100, sparse.position().offsetMeters, 0.000001);
        assertEquals(sparse.position().offsetMeters, frequent.position().offsetMeters, 0.000001);
    }

    @Test
    public void speedChangeAccountsForOldSpeedWithoutTeleporting() {
        RoutePlayback run = new RoutePlayback(square(), 36, 0);
        run.setSpeedKmh(72, 5000);
        assertEquals(50, run.position().offsetMeters, 0.000001);
        run.advanceTo(10_000);
        assertEquals(150, run.position().offsetMeters, 0.000001);
        assertEquals(72, run.speedKmh());
    }

    @Test
    public void zeroSpeedHoldsPositionAndResumeContinuesThere() {
        RoutePlayback run = new RoutePlayback(square(), 36, 0);
        run.setSpeedKmh(0, 5000);
        run.advanceTo(3_605_000);
        assertEquals(50, run.position().offsetMeters, 0.000001);
        run.setSpeedKmh(36, 3_605_000);
        run.advanceTo(3_610_000);
        assertEquals(100, run.position().offsetMeters, 0.000001);
    }

    @Test
    public void exactLoopBoundaryReturnsToSameCoordinate() {
        LoopRoute route = square();
        LoopRoute.Position start = route.positionAt(0);
        LoopRoute.Position end = route.positionAt(route.lengthMeters());
        assertEquals(start.latitude, end.latitude, 0.000000001);
        assertEquals(start.longitude, end.longitude, 0.000000001);
        LoopRoute.Position before = route.positionAt(route.lengthMeters() - 1);
        LoopRoute.Position after = route.positionAt(route.lengthMeters() + 1);
        assertEquals(0, before.longitude, 0.000001);
        assertEquals(0, after.latitude, 0.000001);
        assertTrue(before.latitude < 0.00002);
        assertTrue(after.longitude < 0.00002);
    }

    @Test
    public void longUpdateGapCanCrossSeveralLaps() {
        LoopRoute route = square();
        RoutePlayback run = new RoutePlayback(route, 36, 0);
        long elapsed = (long) Math.ceil((route.lengthMeters() * 3 + 100) / 10 * 1000);
        run.advanceTo(elapsed);
        assertEquals(3, run.completedLaps());
        assertEquals(100, run.position().offsetMeters, 0.011);
    }

    @Test
    public void midpointAndBearingFollowTrackSegment() {
        LoopRoute route = square();
        LoopRoute.Position position = route.positionAt(555.974633);
        assertEquals(0, position.latitude, 0.000001);
        assertEquals(0.005, position.longitude, 0.000001);
        assertEquals(90, position.bearing, 0.001);
    }

    @Test
    public void duplicatePointsDoNotProduceInvalidCoordinates() {
        LoopRoute route = new LoopRoute(new double[][]{
                {0, 0}, {0, 0}, {0, 0.01}, {0.01, 0}, {0, 0}, {0, 0}});
        assertFalse(Double.isNaN(route.positionAt(0).latitude));
        assertFalse(Double.isNaN(route.positionAt(route.lengthMeters() - 1).longitude));
    }

    @Test(expected = IllegalArgumentException.class)
    public void openTrackIsRejectedInsteadOfTeleportingAtEnd() {
        new LoopRoute(new double[][]{{0, 0}, {0, 0.01}, {0.01, 0}});
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeSpeedIsRejected() {
        new RoutePlayback(square(), -1, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void speedAboveLimitIsRejected() {
        new RoutePlayback(square(), 201, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void backwardsClockIsRejected() {
        new RoutePlayback(square(), 90, 1000).advanceTo(999);
    }

    @Test
    public void bundledG30TrackIsClosedAndTraversable() throws Exception {
        double[][] coordinates;
        try (InputStream input = getClass().getResourceAsStream("/g30-loop.gpx")) {
            assertNotNull("Bundled GPX is missing", input);
            NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(input).getElementsByTagName("trkpt");
            assertEquals(173, nodes.getLength());
            coordinates = new double[nodes.getLength()][2];
            for (int i = 0; i < nodes.getLength(); i++) {
                Element point = (Element) nodes.item(i);
                coordinates[i][0] = Double.parseDouble(point.getAttribute("lat"));
                coordinates[i][1] = Double.parseDouble(point.getAttribute("lon"));
            }
        }
        LoopRoute route = new LoopRoute(coordinates);
        assertEquals(57_069.8, route.lengthMeters(), 200);
        assertEquals(43.039292, route.positionAt(0).latitude, 0.000001);
        assertEquals(90.718589, route.positionAt(0).longitude, 0.000001);
        for (double distance = 0; distance < route.lengthMeters() * 2; distance += 10) {
            LoopRoute.Position p = route.positionAt(distance);
            assertTrue(p.latitude > 43.02 && p.latitude < 43.15);
            assertTrue(p.longitude > 90.59 && p.longitude < 90.91);
            assertTrue(p.bearing >= 0 && p.bearing < 360);
        }
        assertEquals(route.positionAt(25).longitude,
                route.positionAt(route.lengthMeters() + 25).longitude, 0.00000001);
    }
}
