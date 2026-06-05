package beaconiq.positioning.phase2;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Pure-JVM tests for the per-beacon sample: RSSI window, liveness, and the
 * single-advance contract of the distance Kalman filter (regression for the
 * Phase II double/triple-update bug).
 */
public class P2BeaconSampleTest {

    private static P2BeaconSample sample() {
        return new P2BeaconSample("uuid:1:1", 0.0, 0.0);
    }

    @Test
    public void freshSampleIsNotLiveAndHasNoDistance() {
        P2BeaconSample s = sample();
        assertThat(s.isLive()).isFalse();
        assertThat(s.getAverageRssi()).isNull();
        assertThat(s.advanceDistanceFilter(-59, 2.0, 1.0)).isNull();
        assertThat(s.getLastFilteredDistance()).isNull();
    }

    @Test
    public void averageRssiReflectsBufferedReadings() {
        P2BeaconSample s = sample();
        s.addRssi(-60);
        s.addRssi(-70);
        assertThat(s.isLive()).isTrue();
        assertThat(s.getAverageRssi()).isEqualTo(-65.0);
        assertThat(s.getLastRawRssi()).isEqualTo(-70);
    }

    @Test
    public void distanceFollowsLogDistanceFormula() {
        P2BeaconSample s = sample();
        s.addRssi(-59);
        // avg == txPower -> raw distance 10^0 == 1.0; scale 1.0; first filter
        // step returns the measurement unchanged.
        assertThat(s.advanceDistanceFilter(-59, 2.0, 1.0)).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.getLastFilteredDistance()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    public void getLastFilteredDistanceDoesNotAdvanceTheFilter() {
        P2BeaconSample s = sample();
        s.addRssi(-59);
        s.advanceDistanceFilter(-59, 2.0, 1.0);
        double first = s.getLastFilteredDistance();
        // Many non-mutating reads must not change the filtered value.
        for (int i = 0; i < 10; i++) {
            assertThat(s.getLastFilteredDistance()).isEqualTo(first);
        }
    }

    @Test
    public void rssiFilterAdvancesOnceAndReadsAreNonMutating() {
        P2BeaconSample s = sample();
        assertThat(s.getLastFilteredRssi()).isNull();   // before first tick
        s.addRssi(-60);
        // First advance returns the measurement unchanged (filter seeds to z).
        assertThat(s.advanceRssiFilter()).isCloseTo(-60.0,
                org.assertj.core.data.Offset.offset(1e-9));
        double first = s.getLastFilteredRssi();
        // Many non-mutating reads must not change the filtered value.
        for (int i = 0; i < 10; i++) {
            assertThat(s.getLastFilteredRssi()).isEqualTo(first);
        }
    }

    @Test
    public void distanceUsesTheSuppliedGlobalTxPower() {
        P2BeaconSample s = sample();
        s.addRssi(-79);
        // Distance uses the supplied (global) txPwr -59: 10^((-59 - -79)/20) = 10.
        assertThat(s.advanceDistanceFilter(-59, 2.0, 1.0)).isCloseTo(10.0,
                org.assertj.core.data.Offset.offset(1e-9));
    }
}
