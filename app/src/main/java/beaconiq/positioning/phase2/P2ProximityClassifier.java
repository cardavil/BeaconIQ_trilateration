package beaconiq.positioning.phase2;

import java.util.HashMap;
import java.util.Map;

/**
 * Map-free proximity model: decides which beacon "zone" the user is in from the
 * filtered, calibration-normalized signal, with hysteresis + dwell to avoid
 * rapid false switching, a confidence score, and edge-triggered actions with a
 * cooldown.
 *
 * Pure logic and time-injected ({@code now} is passed in, like
 * {@link P2PositioningEngine#pruneStale}) so it is fully unit-testable.
 *
 * Signal per beacon = {@code avgRssi - effectiveTxPower} (dB): higher = closer.
 * This normalizes beacons of different TX power via per-beacon calibration, and
 * is monotonically equivalent to ranking by the Kalman-filtered distance.
 */
public class P2ProximityClassifier {

    /** Margin reported when there is no runner-up (single eligible beacon). */
    private static final double NO_RIVAL_MARGIN_DB = 99.0;
    private static final double CONFIDENCE_SCALE_DB = 10.0;

    /** Outcome of one evaluation tick. */
    public static final class ZoneResult {
        public final String activeZone;     // committed zone (compositeId) or null
        public final String candidateZone;  // strongest beacon this tick or null
        public final double confidence;      // 0..1
        public final double marginDb;        // top - second (capped), 0 if none
        public final TransitionEvent transition; // non-null only when a switch commits

        ZoneResult(String activeZone, String candidateZone, double confidence,
                   double marginDb, TransitionEvent transition) {
            this.activeZone = activeZone;
            this.candidateZone = candidateZone;
            this.confidence = confidence;
            this.marginDb = marginDb;
            this.transition = transition;
        }
    }

    /** Emitted when the active zone changes (including first acquisition). */
    public static final class TransitionEvent {
        public final String from;        // previous zone, null on first acquisition
        public final String to;          // new active zone
        public final long timestampMs;
        public final long latencyMs;     // time the candidate was pending before commit
        public final boolean triggered;  // action fired (passed cooldown + confidence)

        TransitionEvent(String from, String to, long timestampMs, long latencyMs, boolean triggered) {
            this.from = from;
            this.to = to;
            this.timestampMs = timestampMs;
            this.latencyMs = latencyMs;
            this.triggered = triggered;
        }
    }

    private String activeZone;
    private String pendingCandidate;
    private long pendingSince;
    private boolean hasTriggered;
    private long lastTriggerMs;

    /** Clears all state (call when scanning stops / session resets). */
    public void reset() {
        activeZone = null;
        pendingCandidate = null;
        pendingSince = 0;
        hasTriggered = false;
        lastTriggerMs = 0;
    }

    public String getActiveZone() {
        return activeZone;
    }

    public ZoneResult evaluate(Map<String, P2BeaconSample> beacons, P2ModelConfig cfg, long now) {
        // 1. Per-beacon normalized signal (eligible = live + enough samples).
        Map<String, Double> signals = new HashMap<>();
        String top = null, second = null;
        double topSig = Double.NEGATIVE_INFINITY, secondSig = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, P2BeaconSample> e : beacons.entrySet()) {
            P2BeaconSample s = e.getValue();
            Double avg = s.getAverageRssi();
            if (avg == null || s.getRssiSampleCount() < cfg.minSamples) continue;
            // Rank on the Kalman-filtered RSSI when available (advanced once per
            // tick by the engine); fall back to the windowed average otherwise.
            Double filt = s.getLastFilteredRssi();
            double rssiForRank = (filt != null) ? filt : avg;
            // Global TX power for every beacon (uniform -> cancels in the ranking).
            double sig = rssiForRank - cfg.txPower;
            signals.put(e.getKey(), sig);
            if (sig > topSig) {
                secondSig = topSig; second = top;
                topSig = sig; top = e.getKey();
            } else if (sig > secondSig) {
                secondSig = sig; second = e.getKey();
            }
        }

        if (top == null) {
            // No eligible beacon this tick — keep the last active zone, no transition.
            pendingCandidate = null;
            return new ZoneResult(activeZone, null, 0.0, 0.0, null);
        }

        double marginDb = (second != null) ? (topSig - secondSig) : NO_RIVAL_MARGIN_DB;
        double confidence = clamp01(marginDb / CONFIDENCE_SCALE_DB);
        String candidate = top;

        TransitionEvent transition = null;
        if (activeZone == null) {
            transition = commit(null, candidate, now, 0, confidence, cfg);
        } else if (!candidate.equals(activeZone)) {
            // Lead of the candidate over the (still-measured) active zone, in dB.
            Double activeSig = signals.get(activeZone);
            double lead = (activeSig != null) ? (topSig - activeSig) : NO_RIVAL_MARGIN_DB;
            if (lead >= cfg.hysteresisMarginDb) {
                if (!candidate.equals(pendingCandidate)) {
                    pendingCandidate = candidate;
                    pendingSince = now;
                }
                if (now - pendingSince >= cfg.dwellMs) {
                    transition = commit(activeZone, candidate, now, now - pendingSince, confidence, cfg);
                }
            } else {
                pendingCandidate = null; // lead not enough — abandon any pending switch
            }
        } else {
            pendingCandidate = null; // candidate already is the active zone
        }

        return new ZoneResult(activeZone, candidate, confidence, capMargin(marginDb), transition);
    }

    private TransitionEvent commit(String from, String to, long now, long latencyMs,
                                   double confidence, P2ModelConfig cfg) {
        activeZone = to;
        pendingCandidate = null;
        boolean cooldownOk = !hasTriggered || (now - lastTriggerMs >= cfg.triggerCooldownMs);
        boolean triggered = false;
        if (cooldownOk && confidence >= cfg.confidenceThreshold) {
            triggered = true;
            hasTriggered = true;
            lastTriggerMs = now;
        }
        return new TransitionEvent(from, to, now, latencyMs, triggered);
    }

    private static double capMargin(double m) {
        return Math.min(m, NO_RIVAL_MARGIN_DB);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
