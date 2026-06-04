/**
 * Extraido de: BeaconsIQ_Project/TEDtour/app/src/main/java/
 *   com/ited/org/ec/tedtour/model/KalmanFilter1D.java
 * Lineas: 1-25
 * Fecha de extraccion: 2026-04-28
 * Proposito: Phase II — duplicado independiente del original TEDtour
 */
package beaconiq.positioning.phase2;

public class P2KalmanFilter1D {
    private final double q;
    private final double r;
    private Double x = null;
    private double p = 1.0;

    public P2KalmanFilter1D(double q, double r) {
        this.q = q;
        this.r = r;
    }

    public double update(double z) {
        if (x == null) {
            x = z;
            return z;
        }
        p += q;
        double k = p / (p + r);
        x = x + k * (z - x);
        p = (1 - k) * p;
        return x;
    }

    /** Current estimate without advancing the filter; null before the first update. */
    public Double current() {
        return x;
    }
}
