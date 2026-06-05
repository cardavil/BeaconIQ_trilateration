/**
 * Based on: BeaconsIQ_Project/TEDtour/app/src/main/java/
 *   com/ited/org/ec/tedtour/model/BeaconSample.java
 * Modified for Phase II: 7-arg configurable constructor,
 *   active Kalman, rssiFilter, configurable bufferSize/timeWindow
 */
package beaconiq.positioning.phase2;

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

    /** True when there is a recent RSSI average — i.e. the beacon is live and
     * contributes to positioning. Non-mutating (does not advance any filter). */
    public boolean isLive() {
        return getAverageRssi() != null;
    }

    public int getRssiSampleCount() {
        return rssiBuffer.size();
    }
    public String getUid() { return uid; }
    public double getX() { return x; }
    public double getY() { return y; }
    public void setCoordinates(double x, double y) { this.x = x; this.y = y; }

    /**
     * Advances the RSSI Kalman filter by one step from the current windowed RSSI
     * average and returns the new filtered value (null if no RSSI yet).
     *
     * Like {@link #advanceDistanceFilter}, this MUST be called exactly once per
     * evaluation tick. Per-reading logging reads {@link #getLastFilteredRssi()}
     * (non-mutating) so it does not perturb the filter.
     */
    public Double advanceRssiFilter() {
        Double avgRssi = getAverageRssi();
        if (avgRssi == null) return null;
        return rssiFilter.update(avgRssi);
    }

    /**
     * The most recent Kalman-filtered RSSI the model produced, without advancing
     * the filter. Null before the first evaluation tick.
     */
    public Double getLastFilteredRssi() {
        return rssiFilter.current();
    }

    /**
     * Advances the distance Kalman filter by one step from the current windowed
     * RSSI average and returns the new filtered distance (null if no RSSI yet).
     *
     * MUST be called exactly once per evaluation tick. Readers that only need the
     * current value (recording, solver, canvas) use {@link #getLastFilteredDistance()}.
     * {@code txPwr} is the global TX power for all beacons (no per-beacon override).
     */
    public Double advanceDistanceFilter(double txPwr, double n, double scale) {
        Double avgRssi = getAverageRssi();
        if (avgRssi == null) return null;
        double rawDistance = Math.pow(10.0, (txPwr - avgRssi) / (10.0 * n));
        double scaledDistance = rawDistance * scale;
        return distanceFilter.update(scaledDistance);
    }

    /**
     * The most recent Kalman-filtered (scaled, calibration-aware) distance the
     * model produced, without advancing the filter. Null before the first
     * evaluation. Use this for logging so recording doesn't perturb the filter.
     */
    public Double getLastFilteredDistance() {
        return distanceFilter.current();
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
