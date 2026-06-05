package beaconiq.positioning.phase2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Real trilateration (multilateration) solver: estimates the receiver position
 * (x,y) that best fits the per-beacon Kalman-filtered distances treated as RADII,
 * by least squares — a linear closed-form initialization followed by a few
 * Gauss-Newton iterations minimizing Σ(‖p − bᵢ‖ − dᵢ)².
 *
 * This replaces the previous Weighted-Centroid (WCL) estimate, which only used
 * the distances as weights (so it could never leave the beacon hull and was
 * invariant to distance scale). Unlike WCL, this solver uses distances as actual
 * circle radii, so the estimate can fall outside the beacon polygon and DOES
 * depend on the absolute distances (and thus on scale_factor / path-loss n).
 *
 * Reads {@link P2BeaconSample#getLastFilteredDistance()} (non-mutating): the
 * caller must have advanced the filters this tick via
 * {@code P2PositioningEngine.updateDistances(...)}.
 */
public class P2TrilaterationJavaSolver {

    private static final int GN_ITERS = 6;

    /** Least-squares position from 3+ beacons with a positive filtered distance. */
    public static double[] estimatePosition(Collection<P2BeaconSample> beacons) {
        if (beacons == null) return null;
        List<double[]> pts = new ArrayList<>(); // {x, y, d}
        for (P2BeaconSample b : beacons) {
            Double d = b.getLastFilteredDistance();
            if (d == null || d <= 0) continue;
            pts.add(new double[]{b.getX(), b.getY(), d});
        }
        if (pts.size() < 3) return null;

        double[] lin = linearLeastSquares(pts);
        if (lin == null) return null;
        double[] gn = gaussNewton(pts, lin);
        // Guard against a divergent refinement.
        if (gn == null || !isFinite(gn[0]) || !isFinite(gn[1])) return lin;
        return gn;
    }

    /**
     * Linear LSQ: subtract a reference circle equation to linearize, then solve
     * the 2×2 normal equations. Returns null if the geometry is degenerate.
     */
    private static double[] linearLeastSquares(List<double[]> pts) {
        int n = pts.size();
        double[] ref = pts.get(n - 1);
        double xr = ref[0], yr = ref[1], dr = ref[2];

        double a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0;
        for (int i = 0; i < n - 1; i++) {
            double xi = pts.get(i)[0], yi = pts.get(i)[1], di = pts.get(i)[2];
            double ax = 2 * (xi - xr);
            double ay = 2 * (yi - yr);
            double bb = (xi * xi - xr * xr) + (yi * yi - yr * yr) + (dr * dr - di * di);
            a11 += ax * ax; a12 += ax * ay; a22 += ay * ay;
            b1 += ax * bb; b2 += ay * bb;
        }
        double det = a11 * a22 - a12 * a12;
        if (Math.abs(det) < 1e-9) return null;
        double x = (b1 * a22 - b2 * a12) / det;
        double y = (a11 * b2 - a12 * b1) / det;
        return new double[]{x, y};
    }

    /** Gauss-Newton refinement minimizing Σ(‖p − bᵢ‖ − dᵢ)². */
    private static double[] gaussNewton(List<double[]> pts, double[] start) {
        double x = start[0], y = start[1];
        for (int it = 0; it < GN_ITERS; it++) {
            double a11 = 0, a12 = 0, a22 = 0, g1 = 0, g2 = 0;
            for (double[] p : pts) {
                double dx = x - p[0], dy = y - p[1];
                double r = Math.hypot(dx, dy);
                if (r < 1e-6) continue;
                double res = r - p[2];      // residual: modeled minus measured radius
                double jx = dx / r, jy = dy / r; // ∂r/∂x, ∂r/∂y
                a11 += jx * jx; a12 += jx * jy; a22 += jy * jy;
                g1 += jx * res; g2 += jy * res;
            }
            double det = a11 * a22 - a12 * a12;
            if (Math.abs(det) < 1e-12) break;
            double stepX = (g1 * a22 - g2 * a12) / det;
            double stepY = (a11 * g2 - a12 * g1) / det;
            x -= stepX; y -= stepY;
            if (Math.hypot(stepX, stepY) < 1e-6) break;
        }
        return new double[]{x, y};
    }

    /** RMS of the radius residuals at {@code pos} — a fit-quality metric (meters). */
    public static double residualRms(Collection<P2BeaconSample> beacons, double[] pos) {
        if (beacons == null || pos == null) return Double.NaN;
        double sum = 0; int n = 0;
        for (P2BeaconSample b : beacons) {
            Double d = b.getLastFilteredDistance();
            if (d == null || d <= 0) continue;
            double r = Math.hypot(pos[0] - b.getX(), pos[1] - b.getY());
            double res = r - d;
            sum += res * res; n++;
        }
        return n == 0 ? Double.NaN : Math.sqrt(sum / n);
    }

    /** Composite id of the beacon nearest to {@code pos}; skips beacons without a
     *  filtered distance (same live set the solver used). */
    public static String findClosestToPosition(
            double[] pos, Map<String, P2BeaconSample> beaconMap) {
        if (pos == null || beaconMap == null) return null;
        String closest = null;
        double minDist = Double.MAX_VALUE;
        for (Map.Entry<String, P2BeaconSample> entry : beaconMap.entrySet()) {
            P2BeaconSample b = entry.getValue();
            if (b.getLastFilteredDistance() == null) continue;
            double dx = pos[0] - b.getX();
            double dy = pos[1] - b.getY();
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < minDist) {
                minDist = d;
                closest = entry.getKey();
            }
        }
        return closest;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
