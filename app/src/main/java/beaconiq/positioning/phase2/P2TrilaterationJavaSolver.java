/**
 * Extraido de: BeaconsIQ_Project/TEDtour/app/src/main/java/
 *   com/ited/org/ec/tedtour/model/TrilaterationJavaSolver.java
 * Lineas: 1-87
 * Fecha de extraccion: 2026-04-28
 * Proposito: Phase II — duplicado independiente del original TEDtour
 */
package beaconiq.positioning.phase2;

import java.util.Collection;
import java.util.Map;

/**
 * Trilateration solver for 3+ beacons: weighted-centroid (WCL) position estimate
 * and a nearest-beacon lookup; the orchestration lives in P2PositioningEngine.
 */
public class P2TrilaterationJavaSolver {

    /**
     * Weighted-centroid estimate using the current Kalman-filtered distances.
     * Reads {@link P2BeaconSample#getLastFilteredDistance()} (non-mutating): the
     * caller must have advanced the filters once this tick via
     * {@code P2PositioningEngine.updateDistances(...)}.
     */
    public static double[] estimatePositionWCL(Collection<P2BeaconSample> beacons) {
        return estimatePositionWCL(beacons, 2.0);
    }

    /** Weighted centroid with a tunable weighting exponent: weight = 1/d^g. */
    public static double[] estimatePositionWCL(Collection<P2BeaconSample> beacons, double g) {
        if (beacons == null || beacons.size() < 3) return null;

        double sumWx = 0, sumWy = 0, sumW = 0;
        for (P2BeaconSample b : beacons) {
            Double d = b.getLastFilteredDistance();
            if (d == null || d <= 0) continue;
            double w = 1.0 / Math.pow(d, g);
            sumWx += w * b.getX();
            sumWy += w * b.getY();
            sumW += w;
        }
        if (sumW == 0) return null;
        return new double[]{sumWx / sumW, sumWy / sumW};
    }

    public static String findClosestToPosition(
            double[] pos, Map<String, P2BeaconSample> beaconMap) {
        if (pos == null || beaconMap == null) return null;
        String closest = null;
        double minDist = Double.MAX_VALUE;
        for (Map.Entry<String, P2BeaconSample> entry : beaconMap.entrySet()) {
            P2BeaconSample b = entry.getValue();
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

}
