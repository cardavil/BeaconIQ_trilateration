package beaconiq.positioning.phase2;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.test.core.app.ApplicationProvider;

import beaconiq.storage.CalibrationStore;

import org.altbeacon.beacon.Beacon;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests the positioning engine orchestration, including the regression for the
 * Phase II bug where the distance Kalman filter was advanced multiple times per
 * evaluation tick (now: advanced only by {@link P2PositioningEngine#updateDistances}).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class P2PositioningEngineTest {

    private static final String UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final double Q = 0.05, R = 0.25;
    private static final int BUF = 20;
    private static final long WINDOW = 8000;

    private CalibrationStore store;
    private P2PositioningEngine engine;

    @Before
    public void setUp() {
        store = new CalibrationStore(ApplicationProvider.getApplicationContext());
        store.clear();
        engine = new P2PositioningEngine(store);
    }

    private static Beacon beacon(int major, int minor, int rssi) {
        return new Beacon.Builder()
                .setId1(UUID)
                .setId2(String.valueOf(major))
                .setId3(String.valueOf(minor))
                .setRssi(rssi)
                .setTxPower(-59)
                .build();
    }

    private void ingest(int major, int minor, int rssi) {
        engine.ingest(beacon(major, minor, rssi), Q, R, BUF, WINDOW);
    }

    @Test
    public void ingestReturnsTrueOnFirstSightThenFalse() {
        assertThat(engine.ingest(beacon(2, 1, -60), Q, R, BUF, WINDOW)).isTrue();
        assertThat(engine.ingest(beacon(2, 1, -62), Q, R, BUF, WINDOW)).isFalse();
        assertThat(engine.beacons()).hasSize(1);
    }

    @Test
    public void pruneStaleRemovesBeaconsPastTheTimeout() {
        ingest(2, 1, -60);
        String id = UUID + ":2:1";
        long now = System.currentTimeMillis();
        engine.beacons().get(id).lastSeen = now - 10_000;
        engine.pruneStale(now, 4_000);
        assertThat(engine.beacons()).isEmpty();
    }

    @Test
    public void updateDistancesAdvancesFiltersOnceAndCountsLiveBeacons() {
        ingest(2, 1, -59);
        ingest(2, 2, -65);
        ingest(2, 3, -70);
        int live = engine.updateDistances(-59, 2.0, 1.0);
        assertThat(live).isEqualTo(3);
        for (P2BeaconSample s : engine.beacons().values()) {
            assertThat(s.getLastFilteredDistance()).isNotNull();
        }
    }

    @Test
    public void estimateAndCountActiveDoNotAdvanceTheFilter() {
        ingest(2, 1, -59);
        ingest(2, 2, -65);
        ingest(2, 3, -70);
        engine.updateDistances(-59, 2.0, 1.0);

        Map<String, Double> before = new HashMap<>();
        for (Map.Entry<String, P2BeaconSample> e : engine.beacons().entrySet()) {
            before.put(e.getKey(), e.getValue().getLastFilteredDistance());
        }

        // The reads that used to double/triple-step the filter must now be pure.
        engine.estimatePosition(2.0);   // WCL
        engine.countActive();
        engine.countActive();

        for (Map.Entry<String, P2BeaconSample> e : engine.beacons().entrySet()) {
            assertThat(e.getValue().getLastFilteredDistance())
                    .isEqualTo(before.get(e.getKey()));
        }
    }

    @Test
    public void estimatePositionReturnsNullBelowThreeBeacons() {
        ingest(2, 1, -59);
        ingest(2, 2, -65);
        engine.updateDistances(-59, 2.0, 1.0);
        assertThat(engine.estimatePosition(2.0)).isNull();
    }

    @Test
    public void calibratedKeysReflectStoredCalibrations() {
        ingest(2, 1, -59);
        ingest(2, 2, -65);
        String calibratedId = UUID + ":2:1";
        store.saveBeacon(new beaconiq.model.Beacon(
                UUID, 2, 1, 1.0, 2.0, -59, 2.0));

        assertThat(engine.calibratedKeys()).containsExactly(calibratedId);
    }
}
