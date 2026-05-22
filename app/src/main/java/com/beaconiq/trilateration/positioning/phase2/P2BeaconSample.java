/**
 * Based on: BeaconsIQ_Project/TEDtour/app/src/main/java/
 *   com/ited/org/ec/tedtour/model/BeaconSample.java
 * Modified for Phase II: 7-arg configurable constructor,
 *   active Kalman, rssiFilter, configurable bufferSize/timeWindow
 */
package com.beaconiq.trilateration.positioning.phase2;

import java.util.ArrayDeque;
import java.util.Deque;

public class P2BeaconSample {

    private final String uid;
    private double x;
    private double y;

    private final Deque<RssiSample> rssiBuffer = new ArrayDeque<>();
    public long lastSeen;

    private final P2KalmanFilter1D distanceFilter;
    private final P2KalmanFilter1D rssiFilter;
    private final int rssiBufferSize;
    private final long rssiTimeWindowMs;

    // Phase I constructor — hardcoded defaults matching original TEDtour
    public P2BeaconSample(String uid, double x, double y) {
        this(uid, x, y, 0.05, 0.25, 20, 8000);
    }

    // Phase II constructor — all parameters configurable
    public P2BeaconSample(String uid, double x, double y,
                        double kalmanQ, double kalmanR,
                        int rssiBufferSize, long rssiTimeWindowMs) {
        this.uid = uid;
        this.x = x;
        this.y = y;
        this.rssiBufferSize = rssiBufferSize;
        this.rssiTimeWindowMs = rssiTimeWindowMs;
        this.distanceFilter = new P2KalmanFilter1D(kalmanQ, kalmanR);
        this.rssiFilter = new P2KalmanFilter1D(kalmanQ, kalmanR);
        this.lastSeen = System.currentTimeMillis();
    }

    private double txPowerOverride = Double.NaN;

    private int lastRawRssi;

    public void addRssi(double rssi) {
        rssiBuffer.addLast(new RssiSample(rssi));
        if (rssiBuffer.size() > rssiBufferSize) rssiBuffer.removeFirst();
        lastRawRssi = (int) rssi;
        lastSeen = System.currentTimeMillis();
    }

    public int getLastRawRssi() { return lastRawRssi; }

    public Double getAverageRssi() {
        long now = System.currentTimeMillis();
        double sum = 0;
        int count = 0;
        for (RssiSample s : rssiBuffer) {
            if (now - s.timestamp <= rssiTimeWindowMs) {
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
    public void setTxPowerOverride(double txPower) { this.txPowerOverride = txPower; }
    public boolean hasTxPowerOverride() { return !Double.isNaN(txPowerOverride); }
    public double getTxPowerOverride() { return txPowerOverride; }

    public void copyRssiFrom(P2BeaconSample other) {
        rssiBuffer.clear();
        rssiBuffer.addAll(other.rssiBuffer);
        lastRawRssi = other.lastRawRssi;
        lastSeen = other.lastSeen;
    }

    public Double getKalmanFilteredRssi() {
        Double avgRssi = getAverageRssi();
        if (avgRssi == null) return null;
        return rssiFilter.update(avgRssi);
    }

    public Double getKalmanFilteredDistance(double txPwr, double n, double scale) {
        Double avgRssi = getAverageRssi();
        if (avgRssi == null) return null;
        double effectiveTxPwr = hasTxPowerOverride() ? txPowerOverride : txPwr;
        double rawDistance = Math.pow(10.0, (effectiveTxPwr - avgRssi) / (10.0 * n));
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
