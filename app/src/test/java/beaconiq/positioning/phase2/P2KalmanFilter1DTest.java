package beaconiq.positioning.phase2;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/** Pure-JVM tests for the 1-D Kalman filter used for RSSI/distance smoothing. */
public class P2KalmanFilter1DTest {

    @Test
    public void currentIsNullBeforeFirstUpdate() {
        P2KalmanFilter1D f = new P2KalmanFilter1D(0.05, 0.25);
        assertThat(f.current()).isNull();
    }

    @Test
    public void firstUpdateReturnsTheMeasurement() {
        P2KalmanFilter1D f = new P2KalmanFilter1D(0.05, 0.25);
        assertThat(f.update(5.0)).isEqualTo(5.0);
        assertThat(f.current()).isEqualTo(5.0);
    }

    @Test
    public void currentDoesNotAdvanceTheFilter() {
        P2KalmanFilter1D f = new P2KalmanFilter1D(0.05, 0.25);
        f.update(5.0);
        double a = f.current();
        double b = f.current();
        assertThat(a).isEqualTo(b).isEqualTo(5.0);
    }

    @Test
    public void constantInputStaysConstant() {
        P2KalmanFilter1D f = new P2KalmanFilter1D(0.05, 0.25);
        f.update(7.0);
        assertThat(f.update(7.0)).isEqualTo(7.0);
        assertThat(f.update(7.0)).isEqualTo(7.0);
    }

    @Test
    public void convergesTowardANewMeasurementWithoutOvershooting() {
        P2KalmanFilter1D f = new P2KalmanFilter1D(0.05, 0.25);
        f.update(10.0);
        double afterOne = f.update(20.0);
        // A single step moves part-way, never past the measurement.
        assertThat(afterOne).isGreaterThan(10.0).isLessThan(20.0);
        // Repeated identical measurements drive the estimate close to 20.
        for (int i = 0; i < 50; i++) f.update(20.0);
        assertThat(f.current()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.5));
    }
}
