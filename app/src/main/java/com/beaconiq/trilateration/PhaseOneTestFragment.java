package com.beaconiq.trilateration;

import android.util.Log;
import android.view.View;

import com.beaconiq.trilateration.positioning.phase1.BeaconSample;
import com.beaconiq.trilateration.positioning.phase1.ProximityEngine;
import com.beaconiq.trilateration.positioning.phase1.TrilaterationJavaSolver;

import org.altbeacon.beacon.Beacon;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PhaseOneTestFragment extends BaseTestFragment {

    // Original RadarScanActivity scan modes (only REAL is active)
    @SuppressWarnings("unused")
    private enum ScanMode { SIMULATED, JAVA, PYTHON, REAL }
    private static final ScanMode SCAN_MODE = ScanMode.REAL;

    // Original RadarScanActivity.PositionState
    private enum PositionState { SEARCHING, POSITIONING, INSIDE_ZONE }

    private final Map<String, BeaconSample> p1BeaconMap = new ConcurrentHashMap<>();

    private PositionState currentPositionState = PositionState.SEARCHING;
    private ProximityEngine proximityEngine;
    private final Map<String, Integer> legacyBeaconRSSIMap = new ConcurrentHashMap<>();
    private String legacyNearestBeacon;
    private String currentNearestBeacon;
    private String radarClosestBeacon;
    private String serviceClosestBeacon;
    private String lastNotifiedBeaconUuid;
    private String activeStandUuid;
    private boolean isDetailActivityOpen;
    private String currentClosestUuid;

    @Override
    protected void saveBeaconConfig() {
        // Phase I uses hardcoded TEDtour values — never overwrite Phase II prefs
    }

    // --- Abstract hook implementations ---

    @Override
    protected void applyPhaseConfig() {
        modelParamsSection.setVisibility(View.GONE);

        tvKalmanStatus.setText("Kalman: filter exists (q=0.05, r=0.25), not called by getFilteredDistance()");
        tvSolverStatus.setText("Solver: findClosestBeacon (no x,y calculated)");
        tvEngineStatus.setText("ProximityEngine: instantiated, never called");
        tvCalibrationStatus.setVisibility(View.GONE);
        btnCalibrate.setVisibility(View.GONE);
    }

    @Override
    protected String getPhaseLabel() {
        return "phase_1";
    }

    @Override
    protected void readSessionParams() {
        txPower = -59;
        pathLossN = 2.0;
        rssiThreshold = -100;
        kalmanQ = DEFAULT_KALMAN_Q;
        kalmanR = DEFAULT_KALMAN_R;
        rssiBufferSize = DEFAULT_RSSI_BUFFER_SIZE;
        rssiTimeWindowMs = DEFAULT_RSSI_TIME_WINDOW_MS;
        scaleFactor = 5.0; // TEDtour hardcodes rawDistance * 5.0 in BeaconSample.getFilteredDistance()
        beaconTimeoutMs = DEFAULT_BEACON_TIMEOUT_MS;
        modelEvalIntervalMs = DEFAULT_MODEL_EVAL_INTERVAL_MS;
    }

    @Override
    protected void clearPhaseState() {
        p1BeaconMap.clear();
        legacyBeaconRSSIMap.clear();
        legacyNearestBeacon = null;
        currentNearestBeacon = null;
        radarClosestBeacon = null;
        serviceClosestBeacon = null;
        lastNotifiedBeaconUuid = null;
        activeStandUuid = null;
        isDetailActivityOpen = false;
        currentClosestUuid = null;
        proximityEngine = null;
        currentPositionState = PositionState.SEARCHING;
        btnCalibrate.setVisibility(View.GONE);
    }

    @Override
    protected void runModelEvaluation() {
        long now = System.currentTimeMillis();
        p1BeaconMap.entrySet().removeIf(e -> now - e.getValue().lastSeen > beaconTimeoutMs);

        int validBeaconCount = p1BeaconMap.size();
        Log.d(TAG, "P1 Beacon count: " + validBeaconCount);

        if (validBeaconCount >= MIN_BEACONS_REQUIRED
                && hasValidCoordinates(p1BeaconMap.values())
                && hasEnoughStableBeacons(p1BeaconMap.values())) {
            evaluatePhaseOne();
        } else if (validBeaconCount > 0) {
            transitionTo(PositionState.POSITIONING);
            estimatedPosition = null;
        } else {
            exitZone();
            transitionTo(PositionState.SEARCHING);
        }

        updateModelStatusPanel();
        positioningCanvas.update(new HashMap<>(p1BeaconMap), estimatedPosition, closestBeaconUid);
        positioningCanvas.updatePhaseOneGhosts(radarClosestBeacon, legacyNearestBeacon, serviceClosestBeacon);
    }

    @Override
    protected Double processBeacon(Beacon beacon, String compositeId) {
        BeaconSample sample = p1BeaconMap.get(compositeId);
        if (sample == null) {
            double[] pos = getBeaconPosition(compositeId, beacon);
            sample = new BeaconSample(compositeId, pos[0], pos[1]);
            p1BeaconMap.put(compositeId, sample);
        }
        sample.addRssi(beacon.getRssi());

        if (!legacyBeaconRSSIMap.containsKey(compositeId)) {
            legacyBeaconRSSIMap.put(compositeId, beacon.getRssi());
        }

        return sample.getAverageRssi();
    }

    @Override
    protected Map<String, Object> buildPhaseReadingFields(String compositeId) {
        Map<String, Object> fields = new HashMap<>();
        BeaconSample p1sample = p1BeaconMap.get(compositeId);
        Double unfilteredDist = p1sample != null ? p1sample.getFilteredDistance() : null;
        fields.put("dist_no_kalman", unfilteredDist != null
                ? Math.round(unfilteredDist * 100.0) / 100.0 : "");
        fields.put("radar_closest", radarClosestBeacon != null ? radarClosestBeacon : "");
        fields.put("scan_nearest_rssi", legacyNearestBeacon != null ? legacyNearestBeacon : "");
        fields.put("service_closest", serviceClosestBeacon != null ? serviceClosestBeacon : "");
        fields.put("dual_conflict",
                radarClosestBeacon != null && legacyNearestBeacon != null
                        && !radarClosestBeacon.equals(legacyNearestBeacon) ? "YES" : "NO");
        return fields;
    }

    @Override
    protected void onResumePhase() {
        if (isRecording) {
            lastNotifiedBeaconUuid = null;
            activeStandUuid = null;
            isDetailActivityOpen = false;
            closestBeaconUid = null;
            currentPositionState = PositionState.SEARCHING;
            modelState = "SEARCHING";
            estimatedPosition = null;
            Log.d(TAG, "P1 onResume: state reset (original behavior)");
        }
    }

    @Override
    protected int countActiveBeacons() {
        int count = 0;
        for (BeaconSample b : p1BeaconMap.values()) {
            if (b.getFilteredDistance() != null) count++;
        }
        return count;
    }

    @Override
    protected void updatePhaseStatusLabels() {
        String radarUid = radarClosestBeacon != null ? shortUid(radarClosestBeacon) : "---";
        String scanUid = legacyNearestBeacon != null ? shortUid(legacyNearestBeacon) : "---";
        String svcUid = serviceClosestBeacon != null ? shortUid(serviceClosestBeacon) : "---";

        tvKalmanStatus.setText("Radar(noKalman): " + radarUid
                + " | Solver: findClosestBeacon");
        tvSolverStatus.setText("Scan(minRSSI): " + scanUid
                + " | Svc(rawRSSI): " + svcUid);
        tvEngineStatus.setText("Engine(r=" + MIN_BEACONS_REQUIRED
                + "): instantiated, not called"
                + " | findBeaconInInfluence: commented");
    }

    // --- Phase I model evaluation ---

    private void evaluatePhaseOne() {
        // =================================================================
        // 1:1 reproduction of original TEDtour — three independent systems
        // running simultaneously, each with its own selection logic.
        // =================================================================

        // --- SYSTEM A: RadarScanActivity.runModel() [DRIVES MODEL OUTPUT] ---
        for (BeaconSample b : p1BeaconMap.values()) {
            Log.d(TAG, "P1 MODEL INPUT -> uid=" + shortUid(b.getUid())
                    + " x=" + b.getX() + " y=" + b.getY()
                    + " dist=" + b.getFilteredDistance()
                    + " rssiAvg=" + b.getAverageRssi());
        }

        radarClosestBeacon = runModel(p1BeaconMap.values());
        Log.d(TAG, "P1 runModel -> closest beacon: " + radarClosestBeacon);

        if (radarClosestBeacon != null && !radarClosestBeacon.equals(lastNotifiedBeaconUuid)) {
            enterZone(radarClosestBeacon);
        }

        transitionTo(PositionState.INSIDE_ZONE);

        if (closestBeaconUid != null) {
            BeaconSample bs = p1BeaconMap.get(closestBeaconUid);
            if (bs != null) {
                estimatedPosition = new double[]{bs.getX(), bs.getY()};
            }
        }

        // --- SYSTEM B: ProximityEngine [BUG #2: instantiated, never evaluated] ---
        if (proximityEngine == null) {
            proximityEngine = new ProximityEngine(p1BeaconMap, MIN_BEACONS_REQUIRED);
            Log.d(TAG, "P1 ProximityEngine instantiated with influenceRadius="
                    + MIN_BEACONS_REQUIRED + " (BUG: should be meters)");
        }

        // --- SYSTEM C: ScanActivity.findNearestBeacon() [BUG #3: inverted] ---
        legacyNearestBeacon = findNearestBeaconByRssi(legacyBeaconRSSIMap);
        legacyBeaconRSSIMap.clear();
        Log.d(TAG, "P1 ScanActivity -> nearestBeacon (min RSSI = farthest): "
                + legacyNearestBeacon);

        if (legacyNearestBeacon != null && !legacyNearestBeacon.equals(currentNearestBeacon)) {
            currentNearestBeacon = legacyNearestBeacon;
            Log.d(TAG, "P1 ScanActivity -> beaconNotificationActivity: "
                    + shortUid(legacyNearestBeacon));
        }

        // --- SYSTEM D: BeaconScanService [strongest raw RSSI = correct logic] ---
        serviceClosestBeacon = findStrongestBeaconByRawRssi();
        if (serviceClosestBeacon != null
                && !serviceClosestBeacon.equals(currentClosestUuid)) {
            currentClosestUuid = serviceClosestBeacon;
            Log.d(TAG, "P1 BeaconScanService -> ACTION_NEW_STAND: "
                    + shortUid(serviceClosestBeacon));
        }

        // --- DIAGNOSTIC: log when systems disagree ---
        if (radarClosestBeacon != null && legacyNearestBeacon != null
                && !radarClosestBeacon.equals(legacyNearestBeacon)) {
            Log.w(TAG, "DUAL-DETECTION: Radar=" + shortUid(radarClosestBeacon)
                    + " ScanActivity=" + shortUid(legacyNearestBeacon)
                    + " Service=" + (serviceClosestBeacon != null
                    ? shortUid(serviceClosestBeacon) : "---"));
        }

        // Gap 8 (Firebase race): Original created BeaconSample at (0,0), then
        // async-fetched real coords from Firebase. Model could run with (0,0)
        // before Firebase responded. Not reproduced — coords come from
        // CalibrationStore immediately.
    }

    private String runModel(java.util.Collection<BeaconSample> beacons) {
        if (beacons.size() < MIN_BEACONS_REQUIRED) return null;
        return TrilaterationJavaSolver.findClosestBeacon(beacons);
    }

    private void enterZone(String uuid) {
        if (uuid == null) return;
        if (uuid.equals(lastNotifiedBeaconUuid)) return;

        lastNotifiedBeaconUuid = uuid;
        activeStandUuid = uuid;
        closestBeaconUid = uuid;

        Log.d(TAG, "P1 enterZone: ACTION_CLOSEST_BEACON_CHANGED uuid=" + shortUid(uuid));
        if (!isDetailActivityOpen) {
            Log.d(TAG, "P1 enterZone: would open BeaconDetailActivityComplete for " + shortUid(uuid));
            isDetailActivityOpen = true;
        }
    }

    private void exitZone() {
        if (activeStandUuid != null) {
            activeStandUuid = null;
            lastNotifiedBeaconUuid = null;
        }
        closestBeaconUid = null;
        estimatedPosition = null;
    }

    private void transitionTo(PositionState state) {
        if (currentPositionState == state) return;
        currentPositionState = state;
        modelState = state.name();
        Log.d(TAG, "P1 transitionTo: " + state.name());
    }

    // BUG: picks MINIMUM RSSI (most negative = farthest beacon)
    private static String findNearestBeaconByRssi(Map<String, Integer> beaconRSSIMap) {
        String nearestBeacon = null;
        int minRSSI = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : beaconRSSIMap.entrySet()) {
            if (entry.getValue() < minRSSI) {
                minRSSI = entry.getValue();
                nearestBeacon = entry.getKey();
            }
        }
        return nearestBeacon;
    }

    private String findStrongestBeaconByRawRssi() {
        String strongest = null;
        int maxRssi = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> entry : legacyBeaconRSSIMap.entrySet()) {
            int rssi = entry.getValue();
            if (rssi > maxRssi) {
                maxRssi = rssi;
                strongest = entry.getKey();
            }
        }
        return strongest;
    }

    private boolean hasValidCoordinates(java.util.Collection<BeaconSample> beacons) {
        for (BeaconSample b : beacons) {
            if (Double.isNaN(b.getX()) || Double.isNaN(b.getY())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasEnoughStableBeacons(java.util.Collection<BeaconSample> beacons) {
        int count = 0;
        for (BeaconSample b : beacons) {
            if (b.getFilteredDistance() != null) count++;
        }
        return count >= MIN_BEACONS_REQUIRED;
    }
}
