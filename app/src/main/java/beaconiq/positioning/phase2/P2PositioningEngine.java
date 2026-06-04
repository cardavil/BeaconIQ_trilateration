package beaconiq.positioning.phase2;

import beaconiq.storage.CalibrationStore;

import org.altbeacon.beacon.Beacon;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the live set of Phase II beacon samples and the positioning math.
 *
 * Both ScanFragment (Explore) and PhaseTwoTestFragment used to keep their own
 * beacon map plus near-identical copies of getBeaconPosition / getOrCreate /
 * prune / solver-call logic. That all lives here now; the fragments keep only
 * their own orchestration (when to evaluate, UI state, recording).
 */
public class P2PositioningEngine {

    private final Map<String, P2BeaconSample> beacons = new ConcurrentHashMap<>();
    private final CalibrationStore calibrationStore;
    private final P2ProximityClassifier proximityClassifier = new P2ProximityClassifier();
    private int autoPositionCounter = 0;

    public P2PositioningEngine(CalibrationStore calibrationStore) {
        this.calibrationStore = calibrationStore;
    }

    /** Live map — used for canvas snapshots, calibration UI, and status lines. */
    public Map<String, P2BeaconSample> beacons() {
        return beacons;
    }

    /** Defensive copy for handing to the canvas. */
    public Map<String, P2BeaconSample> snapshot() {
        return new HashMap<>(beacons);
    }

    public void clear() {
        beacons.clear();
        proximityClassifier.reset();
    }

    /**
     * Proximity mode: classify the active zone (nearest beacon) with hysteresis,
     * dwell, confidence and edge-triggered actions. {@code now} is injected for
     * testability. Does not touch the distance filter.
     */
    public P2ProximityClassifier.ZoneResult classifyProximity(P2ModelConfig cfg, long now) {
        return proximityClassifier.evaluate(beacons, cfg, now);
    }

    /** Composite ids of the currently-seen beacons that have a saved calibration. */
    public Set<String> calibratedKeys() {
        Set<String> keys = new HashSet<>();
        for (String id : beacons.keySet()) {
            if (calibrationStore.getBeacon(id) != null) keys.add(id);
        }
        return keys;
    }

    public void resetAutoPositionCounter() {
        autoPositionCounter = 0;
    }

    /**
     * Creates the sample on first sight (seeding coordinates and any calibrated
     * TX power) then feeds it the new RSSI reading.
     *
     * @return true if the beacon was newly added (first time seen since clear)
     */
    public boolean ingest(Beacon beacon, double kalmanQ, double kalmanR,
                          int rssiBufferSize, long rssiTimeWindowMs) {
        String compositeId = P2BeaconIds.buildCompositeId(beacon);
        P2BeaconSample sample = beacons.get(compositeId);
        boolean isNew = false;
        if (sample == null) {
            double[] pos = getBeaconPosition(beacon);
            sample = new P2BeaconSample(compositeId, pos[0], pos[1],
                    kalmanQ, kalmanR, rssiBufferSize, rssiTimeWindowMs);
            beaconiq.model.Beacon cal = calibrationStore.getBeacon(compositeId);
            if (cal != null) {
                sample.setTxPowerOverride(cal.getTxPower());
            }
            beacons.put(compositeId, sample);
            isNew = true;
        }
        sample.addRssi(beacon.getRssi());
        return isNew;
    }

    /** Drops beacons not seen within the timeout window. */
    public void pruneStale(long now, long beaconTimeoutMs) {
        beacons.entrySet().removeIf(e -> now - e.getValue().lastSeen > beaconTimeoutMs);
    }

    /**
     * Advances every beacon's distance Kalman filter exactly once for this tick
     * and returns how many beacons are currently live. This is the single point
     * where the distance filter is stepped; the solver and recording then read
     * the resulting value non-mutatingly. Call once per evaluation tick before
     * {@link #estimatePosition(int)}.
     */
    public int updateDistances(int txPower, double pathLossN, double scaleFactor) {
        int count = 0;
        for (P2BeaconSample b : beacons.values()) {
            b.advanceDistanceFilter(txPower, pathLossN, scaleFactor);
            if (b.isLive()) count++;
        }
        return count;
    }

    /** Count of currently live beacons (non-mutating). */
    public int countActive() {
        int count = 0;
        for (P2BeaconSample b : beacons.values()) {
            if (b.isLive()) count++;
        }
        return count;
    }

    /**
     * Trilateration mode: WCL (x,y) with weighting exponent g, on the distances
     * last produced by {@link #updateDistances(int, double, double)}.
     */
    public double[] estimatePosition(double g) {
        return P2TrilaterationJavaSolver.estimatePositionWCL(beacons.values(), g);
    }

    /** Composite id of the beacon nearest to the given estimate, or null. */
    public String closestTo(double[] pos) {
        return P2TrilaterationJavaSolver.findClosestToPosition(pos, beacons);
    }

    /**
     * Coordinates for a beacon: its calibrated position if known, otherwise an
     * auto-assigned point on a fallback ring.
     */
    private double[] getBeaconPosition(Beacon beacon) {
        String uuid = beacon.getId1().toString();
        int major = beacon.getIdentifiers().size() >= 2 ? beacon.getId2().toInt() : 0;
        int minor = beacon.getIdentifiers().size() >= 3 ? beacon.getId3().toInt() : 0;

        beaconiq.model.Beacon calibrated =
                calibrationStore.getBeacon(uuid, major, minor);
        if (calibrated != null) {
            return new double[]{calibrated.getX(), calibrated.getY()};
        }

        double cx = 5.0, cy = 5.0, r = 3.5;
        double angle = 2 * Math.PI * autoPositionCounter / 6.0;
        autoPositionCounter++;
        return new double[]{cx + r * Math.cos(angle), cy + r * Math.sin(angle)};
    }
}
