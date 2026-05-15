/**
 * Extraido de: BeaconsIQ_Project/TEDtour/app/src/main/java/
 *   com/ited/org/ec/tedtour/model/TrilaterationJavaSolver.java
 * Lineas: 1-87
 * Fecha de extraccion: 2026-04-28
 * Proposito: Phase II — duplicado independiente del original TEDtour
 */
package com.beaconiq.trilateration.positioning.phase2;

import android.util.Log;

import java.util.Collection;
import java.util.Map;

/**
 * Trilateration solver for 3+ beacons.
 *
 * Behavior:
 * - Estimate phone position using 3+ beacons.
 * - Find closest beacon whose influence radius contains the phone.
 * - Return UUID of that beacon or null if none in range.
 */
public class TrilaterationJavaSolver {

    private static final double INFLUENCE_RADIUS = 2.5; // meters

    /**
     * Estimate the phone position as a simple centroid of beacon coordinates.
     */
    public static double[] estimatePosition(Collection<BeaconSample> beacons) {
        if (beacons == null || beacons.size() < 3) return null;

        double sumX = 0;
        double sumY = 0;
        int count = 0;

        for (BeaconSample b : beacons) {
            sumX += b.getX();
            sumY += b.getY();
            count++;
        }

        return new double[]{sumX / count, sumY / count};
    }

    /**
     * Return the UUID of the closest beacon that contains the phone in its influence radius.
     */
    public static String findBeaconInInfluence(Collection<BeaconSample> beacons) {
        if (beacons == null || beacons.size() < 3) return null;

        double[] phonePos = estimatePosition(beacons);
        if (phonePos == null || phonePos.length != 2) return null;

        double estX = phonePos[0];
        double estY = phonePos[1];

        BeaconSample closestInRange = null;
        double minDist = Double.MAX_VALUE;

        for (BeaconSample b : beacons) {
            double dx = estX - b.getX();
            double dy = estY - b.getY();
            double distance = Math.sqrt(dx * dx + dy * dy);

            // Check if inside influence radius
            if (distance <= INFLUENCE_RADIUS && distance < minDist) {
                minDist = distance;
                closestInRange = b;
            }
        }

        return (closestInRange != null) ? closestInRange.getUid() : null;
    }

    public static String findClosestBeacon(Collection<BeaconSample> beacons) {
        BeaconSample closest = null;
        double minDist = Double.MAX_VALUE;

        for (BeaconSample b : beacons) {
            Double d = b.getFilteredDistance();
            if (d == null) continue;

            Log.d("Radar", "DIST CHECK -> " + b.getUid() + " = " + d);

            if (d < minDist) {
                minDist = d;
                closest = b;
            }
        }

        return closest != null ? closest.getUid() : null;
    }

    public static double[] estimatePositionWCL(
            Collection<BeaconSample> beacons,
            double txPower, double pathLossN, double scaleFactor) {
        if (beacons == null || beacons.size() < 3) return null;

        double sumWx = 0, sumWy = 0, sumW = 0;
        for (BeaconSample b : beacons) {
            Double d = b.getKalmanFilteredDistance(txPower, pathLossN, scaleFactor);
            if (d == null || d <= 0) continue;
            double w = 1.0 / (d * d);
            sumWx += w * b.getX();
            sumWy += w * b.getY();
            sumW += w;
        }
        if (sumW == 0) return null;
        return new double[]{sumWx / sumW, sumWy / sumW};
    }

    public static String findClosestToPosition(
            double[] pos, Map<String, BeaconSample> beaconMap) {
        if (pos == null || beaconMap == null) return null;
        String closest = null;
        double minDist = Double.MAX_VALUE;
        for (Map.Entry<String, BeaconSample> entry : beaconMap.entrySet()) {
            BeaconSample b = entry.getValue();
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
