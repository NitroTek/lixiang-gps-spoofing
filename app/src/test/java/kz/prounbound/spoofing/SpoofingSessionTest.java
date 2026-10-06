package kz.prounbound.spoofing;

import kz.prounbound.route.LoopRoute;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.Random;

import static org.junit.Assert.*;

public class SpoofingSessionTest {
    private ChinaRegion china() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/china-mainland.csv")) {
            assertNotNull(input);
            return ChinaRegion.load(new InputStreamReader(input, "UTF-8"));
        }
    }

    private LoopRoute route() {
        return new LoopRoute(new double[][]{{0,0},{0,0.01},{0.01,0.01},{0.01,0},{0,0}});
    }

    private SpoofingSession run(int mode, int seconds, long now) throws Exception {
        return new SpoofingSession(route(), china(), mode, 90, seconds, new Random(42), now);
    }

    @Test public void knownInteriorAndForeignPointsAreClassified() throws Exception {
        ChinaRegion china = china();
        assertTrue(china.isInterior(39.9042, 116.4074));
        assertTrue(china.isInterior(30.5728, 104.0668));
        assertFalse(china.contains(55.7558, 37.6173));
        assertFalse(china.contains(37.7749, -122.4194));
        assertFalse(china.contains(34, 140));
    }

    @Test public void randomPointsAreInlandAndDistributedAcrossChina() throws Exception {
        ChinaRegion china = china();
        Random random = new Random(123);
        double minLon = 180, maxLon = -180, minLat = 90, maxLat = -90;
        for (int i = 0; i < 1000; i++) {
            LoopRoute.Position point = china.randomPosition(random);
            assertTrue(china.isInterior(point.latitude, point.longitude));
            assertEquals(0, point.bearing, 0);
            minLon = Math.min(minLon, point.longitude); maxLon = Math.max(maxLon, point.longitude);
            minLat = Math.min(minLat, point.latitude); maxLat = Math.max(maxLat, point.latitude);
        }
        assertTrue(minLon < 90 && maxLon > 125);
        assertTrue(minLat < 25 && maxLat > 45);
    }

    @Test public void stationaryPositionNeverDriftsEvenIfSpeedIsChanged() throws Exception {
        SpoofingSession session = run(SpoofingSession.MODE_STATIONARY_CHINA, 0, 0);
        LoopRoute.Position point = session.position();
        for (int t = 1000; t <= 600_000; t += 1000) {
            session.setSpeedKmh(200, t);
            session.advanceTo(t);
            assertSame(point, session.position());
            assertEquals(0, session.speedKmh());
        }
    }

    @Test public void nextStationaryRunChoosesAnotherPoint() throws Exception {
        ChinaRegion china = china();
        Random random = new Random(24);
        SpoofingSession a = new SpoofingSession(null, china, 1, 90, 0, random, 0);
        SpoofingSession b = new SpoofingSession(null, china, 1, 90, 0, random, 0);
        assertNotEquals(a.position().longitude, b.position().longitude, 0.000001);
    }

    @Test public void routeModeRetainsMovementAndLiveSpeedChanges() throws Exception {
        SpoofingSession session = run(SpoofingSession.MODE_ROUTE, 0, 0);
        session.advanceTo(1000);
        assertEquals(25, session.position().offsetMeters, 0.000001);
        session.setSpeedKmh(180, 1000);
        session.advanceTo(2000);
        assertEquals(75, session.position().offsetMeters, 0.000001);
    }

    @Test public void disabledTimerDoesNotExpireInEitherMode() throws Exception {
        for (int mode = 0; mode <= 1; mode++) {
            SpoofingSession session = run(mode, 0, 0);
            assertFalse(session.hasTimer());
            assertFalse(session.isExpired(10_000_000));
        }
    }

    @Test public void fiveMinuteTimerExpiresAtExactElapsedTimeInEitherMode() throws Exception {
        for (int mode = 0; mode <= 1; mode++) {
            SpoofingSession session = run(mode, 300, 123_456);
            assertEquals(300_000, session.remainingMillis(123_456));
            assertFalse(session.isExpired(423_455));
            assertEquals(1, session.remainingMillis(423_455));
            assertTrue(session.isExpired(423_456));
            assertEquals(0, session.remainingMillis(500_000));
        }
    }

    @Test public void speedChangeDoesNotRestartTimer() throws Exception {
        SpoofingSession session = run(0, 60, 0);
        session.setSpeedKmh(0, 30_000);
        assertEquals(30_000, session.remainingMillis(30_000));
        assertTrue(session.isExpired(60_000));
    }

    @Test public void restartingCreatesNewDeadline() throws Exception {
        SpoofingSession first = run(1, 1, 0);
        assertTrue(first.isExpired(1000));
        SpoofingSession second = run(1, 1, 2000);
        assertFalse(second.isExpired(2000));
        assertEquals(1000, second.remainingMillis(2000));
    }

    @Test(expected = IllegalArgumentException.class)
    public void timerAboveFiveMinutesIsRejected() throws Exception { run(0, 301, 0); }

    @Test(expected = IllegalArgumentException.class)
    public void negativeTimerIsRejected() throws Exception { run(0, -1, 0); }

    @Test(expected = IllegalArgumentException.class)
    public void unknownModeIsRejected() throws Exception { run(2, 60, 0); }

    @Test(expected = java.io.IOException.class)
    public void brokenCountryAssetIsRejected() throws Exception {
        ChinaRegion.load(new StringReader("0,0\n0,10\n10,10\n10,0\n"));
    }
}
