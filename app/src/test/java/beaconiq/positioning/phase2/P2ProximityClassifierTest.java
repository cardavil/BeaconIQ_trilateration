package beaconiq.positioning.phase2;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.data.Offset;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure-JVM tests for the proximity zone classifier (hysteresis, dwell, trigger). */
public class P2ProximityClassifierTest {

    private static P2BeaconSample sample(String id, int rssi, int count) {
        P2BeaconSample s = new P2BeaconSample(id, 0.0, 0.0);
        for (int i = 0; i < count; i++) s.addRssi(rssi);
        return s;
    }

    private static Map<String, P2BeaconSample> map(P2BeaconSample... ss) {
        Map<String, P2BeaconSample> m = new LinkedHashMap<>();
        for (P2BeaconSample s : ss) m.put(s.getUid(), s);
        return m;
    }

    private static P2ModelConfig cfg() {
        return new P2ModelConfig(); // defaults: minSamples 3, hysteresis 6, dwell 1500, cooldown 8000
    }

    @Test
    public void firstAcquisitionAdoptsStrongestAndTriggers() {
        P2ProximityClassifier c = new P2ProximityClassifier();
        // A: sig = -50-(-59)=9 ; B: sig = -60-(-59)=-1 ; margin 10 -> confidence 1.0
        P2ProximityClassifier.ZoneResult r =
                c.evaluate(map(sample("A", -50, 5), sample("B", -60, 5)), cfg(), 1000);

        assertThat(r.activeZone).isEqualTo("A");
        assertThat(r.candidateZone).isEqualTo("A");
        assertThat(r.confidence).isEqualTo(1.0);
        assertThat(r.transition).isNotNull();
        assertThat(r.transition.from).isNull();
        assertThat(r.transition.to).isEqualTo("A");
        assertThat(r.transition.triggered).isTrue();
    }

    @Test
    public void staysOnActiveWhenLeadBelowHysteresis() {
        P2ProximityClassifier c = new P2ProximityClassifier();
        P2ModelConfig cfg = cfg();
        c.evaluate(map(sample("A", -50, 5), sample("B", -70, 5)), cfg, 0); // acquire A

        // Now B leads A by only 3 dB (< 6): A sig 7 (-52), B sig 10 (-49).
        P2ProximityClassifier.ZoneResult r =
                c.evaluate(map(sample("A", -52, 5), sample("B", -49, 5)), cfg, 5000);

        assertThat(r.candidateZone).isEqualTo("B");
        assertThat(r.activeZone).isEqualTo("A");   // no switch
        assertThat(r.transition).isNull();
    }

    @Test
    public void switchesOnlyAfterLeadSustainedForDwell() {
        P2ProximityClassifier c = new P2ProximityClassifier();
        P2ModelConfig cfg = cfg(); // dwell 1500
        c.evaluate(map(sample("A", -50, 5), sample("B", -70, 5)), cfg, 0); // acquire A

        // B now leads A by 8 dB: A sig 0 (-59), B sig 8 (-51).
        Map<String, P2BeaconSample> bLeads = map(sample("A", -59, 5), sample("B", -51, 5));

        P2ProximityClassifier.ZoneResult t1 = c.evaluate(bLeads, cfg, 1000); // pending starts
        assertThat(t1.activeZone).isEqualTo("A");
        assertThat(t1.transition).isNull();

        P2ProximityClassifier.ZoneResult t2 = c.evaluate(bLeads, cfg, 1000 + 1500); // dwell elapsed
        assertThat(t2.activeZone).isEqualTo("B");
        assertThat(t2.transition).isNotNull();
        assertThat(t2.transition.from).isEqualTo("A");
        assertThat(t2.transition.to).isEqualTo("B");
        assertThat(t2.transition.latencyMs).isEqualTo(1500);
    }

    @Test
    public void pendingSwitchAbortsIfLeadCollapses() {
        P2ProximityClassifier c = new P2ProximityClassifier();
        P2ModelConfig cfg = cfg();
        c.evaluate(map(sample("A", -50, 5), sample("B", -70, 5)), cfg, 0); // acquire A

        c.evaluate(map(sample("A", -59, 5), sample("B", -51, 5)), cfg, 1000); // B leads 8 -> pending
        // Before dwell, B's lead collapses to 3 dB (< hysteresis) -> abort.
        P2ProximityClassifier.ZoneResult r =
                c.evaluate(map(sample("A", -52, 5), sample("B", -49, 5)), cfg, 1500);

        assertThat(r.activeZone).isEqualTo("A");
        assertThat(r.transition).isNull();
    }

    @Test
    public void triggerRespectsCooldown() {
        P2ProximityClassifier c = new P2ProximityClassifier();
        P2ModelConfig cfg = cfg();
        cfg.dwellMs = 0; // switch immediately to isolate cooldown behaviour

        P2ProximityClassifier.ZoneResult a =
                c.evaluate(map(sample("A", -50, 5), sample("B", -70, 5)), cfg, 0);
        assertThat(a.transition.triggered).isTrue(); // first trigger at t=0

        // Switch to B at t=100 (< cooldown 8000): commits but must NOT re-trigger.
        P2ProximityClassifier.ZoneResult b =
                c.evaluate(map(sample("A", -70, 5), sample("B", -50, 5)), cfg, 100);
        assertThat(b.activeZone).isEqualTo("B");
        assertThat(b.transition).isNotNull();
        assertThat(b.transition.triggered).isFalse();
    }

    @Test
    public void confidenceTracksMarginAndSingleBeaconIsCertain() {
        P2ProximityClassifier c = new P2ProximityClassifier();
        // Two beacons, margin 3 dB: A sig 9 (-50), B sig 6 (-53) -> confidence 0.3.
        P2ProximityClassifier.ZoneResult two =
                c.evaluate(map(sample("A", -50, 5), sample("B", -53, 5)), cfg(), 0);
        assertThat(two.confidence).isCloseTo(0.3, Offset.offset(1e-9));

        // Single eligible beacon -> no rival -> full confidence, capped margin.
        P2ProximityClassifier c2 = new P2ProximityClassifier();
        P2ProximityClassifier.ZoneResult one =
                c2.evaluate(map(sample("A", -50, 5)), cfg(), 0);
        assertThat(one.confidence).isEqualTo(1.0);
        assertThat(one.marginDb).isEqualTo(99.0);
    }

    @Test
    public void beaconsBelowMinSamplesAreIneligible() {
        P2ProximityClassifier c = new P2ProximityClassifier();
        P2ModelConfig cfg = cfg(); // minSamples 3
        // A is stronger but has only 2 samples -> ignored; B wins.
        P2ProximityClassifier.ZoneResult r =
                c.evaluate(map(sample("A", -50, 2), sample("B", -60, 5)), cfg, 0);
        assertThat(r.candidateZone).isEqualTo("B");
        assertThat(r.activeZone).isEqualTo("B");
    }

    @Test
    public void resetClearsState() {
        P2ProximityClassifier c = new P2ProximityClassifier();
        c.evaluate(map(sample("A", -50, 5), sample("B", -70, 5)), cfg(), 0);
        assertThat(c.getActiveZone()).isEqualTo("A");
        c.reset();
        assertThat(c.getActiveZone()).isNull();
    }
}
