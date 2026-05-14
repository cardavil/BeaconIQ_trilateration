/**
 * Extraido de: BeaconsIQ_Project/TEDtour/app/src/main/java/
 *   com/ited/org/ec/tedtour/model/BeaconSample.java
 * Lineas: 1-93
 * Fecha de extraccion: 2026-04-28
 * Proposito: Phase I — modelo original de TEDtour, fiel 1:1
 */
package com.beaconiq.trilateration.positioning.phase1;

import java.util.ArrayDeque;
import java.util.Deque;

public class BeaconSample {

    private final String uid;
    private double x;
    private double y;

    // Track RSSI samples for smoothing
    private final Deque<RssiSample> rssiBuffer = new ArrayDeque<>();
    public long lastSeen;

    private final KalmanFilter1D distanceFilter = new KalmanFilter1D(0.05, 0.25);
    private final KalmanFilter1D rssiFilter = new KalmanFilter1D(0.05, 0.25);

    private int lastRawRssi;

    public BeaconSample(String uid, double x, double y) {
        this.uid = uid;
        this.x = x;
        this.y = y;
        this.lastSeen = System.currentTimeMillis();
    }


    public void addRssi(double rssi) {
        rssiBuffer.addLast(new RssiSample(rssi));
        if (rssiBuffer.size() > 20) rssiBuffer.removeFirst();
        lastRawRssi = (int) rssi;
        lastSeen = System.currentTimeMillis();
    }

    public int getLastRawRssi() { return lastRawRssi; }

    public Double getAverageRssi() {
        long now = System.currentTimeMillis();
        double sum = 0;
        int count = 0;
        for (RssiSample s : rssiBuffer) {
            if (now - s.timestamp <= 8000) { // last 8 seconds
                sum += s.rssi;
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    // Adjust getFilteredDistance to scale distances (for testing)
    /*public Double getFilteredDistance() {
        Double avgRssi = getAverageRssi();
        if (avgRssi == null) return null;

        // Convert RSSI to distance in meters (example)
        double rawDistance = Math.pow(10.0, (-59 - avgRssi) / (10.0 * 2.0));

        // Scale to match your coordinate system (0-10 range)
        double scaleFactor = 5.0; // tune this experimentally
        double scaledDistance = rawDistance * scaleFactor;

        // Clamp max distance to avoid huge outliers
     //   if (scaledDistance > 10.0) scaledDistance = 10.0;

        return distanceFilter.update(scaledDistance);
    }*/

    public Double getFilteredDistance() {
        Double avgRssi = getAverageRssi();
        if (avgRssi == null) return null;

        double rawDistance =
                Math.pow(10.0, (-59 - avgRssi) / (10.0 * 2.0));

        double scaleFactor = 5.0;
        return rawDistance * scaleFactor;
    }



    public int getRssiSampleCount() {
        return rssiBuffer.size();
    }
    public String getUid() { return uid; }
    public double getX() { return x; }
    public double getY() { return y; }
    public void setCoordinates(double x, double y) { this.x = x; this.y = y; }

    public void updateKalmanParams(double q, double r) {
        rssiFilter.reset(q, r);
        distanceFilter.reset(q, r);
    }

    public Double getKalmanFilteredRssi() {
        Double avgRssi = getAverageRssi();
        if (avgRssi == null) return null;
        return rssiFilter.update(avgRssi);
    }

    public Double getKalmanFilteredDistance(double txPwr, double n, double scale) {
        Double avgRssi = getAverageRssi();
        if (avgRssi == null) return null;
        double rawDistance = Math.pow(10.0, (txPwr - avgRssi) / (10.0 * n));
        double scaledDistance = rawDistance * scale;
        return distanceFilter.update(scaledDistance);
    }

    private static class RssiSample {
        final double rssi;
        final long timestamp;

        RssiSample(double rssi) {
            this.rssi = rssi;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
