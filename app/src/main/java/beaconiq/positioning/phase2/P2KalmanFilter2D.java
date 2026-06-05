package beaconiq.positioning.phase2;

/**
 * 2-D position smoother: two independent 1-D Kalman filters (x and y), matching
 * the position filter (KF_POS) of the original Python prototype. Applied to the
 * trilateration estimate once per evaluation tick to reduce jitter.
 */
public class P2KalmanFilter2D {

    private final P2KalmanFilter1D fx;
    private final P2KalmanFilter1D fy;

    public P2KalmanFilter2D(double q, double r) {
        this.fx = new P2KalmanFilter1D(q, r);
        this.fy = new P2KalmanFilter1D(q, r);
    }

    /** Advances both axes by one step and returns the smoothed {x, y}. */
    public double[] update(double x, double y) {
        return new double[]{fx.update(x), fy.update(y)};
    }

    /** Current smoothed estimate without advancing; null before the first update. */
    public double[] current() {
        Double cx = fx.current(), cy = fy.current();
        return (cx == null || cy == null) ? null : new double[]{cx, cy};
    }
}
