package beaconiq.positioning.phase2;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.data.Offset;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure-JVM tests for the centroid / weighted-centroid solver and nearest lookup. */
public class P2TrilaterationJavaSolverTest {

    /** Builds a sample whose distance filter is primed to {@code rssi}. */
    private static P2BeaconSample primed(String id, double x, double y, int rssi) {
        P2BeaconSample s = new P2BeaconSample(id, x, y);
        s.addRssi(rssi);
        s.advanceDistanceFilter(-59, 2.0, 1.0);
        return s;
    }

    @Test
    public void wclIsPulledTowardTheCloserBeacon() {
        List<P2BeaconSample> beacons = new ArrayList<>();
        beacons.add(primed("a:1:1", 0.0, 0.0, -59));   // d = 1   -> weight 1.0
        beacons.add(primed("b:1:2", 10.0, 0.0, -79));  // d = 10  -> weight 0.01
        beacons.add(primed("c:1:3", 0.0, 10.0, -79));  // d = 10  -> weight 0.01

        double[] pos = P2TrilaterationJavaSolver.estimatePositionWCL(beacons);
        assertThat(pos).isNotNull();
        // Heavily weighted toward A at the origin.
        assertThat(pos[0]).isLessThan(0.5);
        assertThat(pos[1]).isLessThan(0.5);
    }

    @Test
    public void wclDoesNotAdvanceTheFilters() {
        P2BeaconSample a = primed("a:1:1", 0.0, 0.0, -59);
        P2BeaconSample b = primed("b:1:2", 10.0, 0.0, -79);
        P2BeaconSample c = primed("c:1:3", 0.0, 10.0, -79);
        List<P2BeaconSample> beacons = new ArrayList<>(List.of(a, b, c));

        double aDist = a.getLastFilteredDistance();
        double[] first = P2TrilaterationJavaSolver.estimatePositionWCL(beacons);
        double[] second = P2TrilaterationJavaSolver.estimatePositionWCL(beacons);

        // Re-running the solver is pure: identical result and untouched filters.
        assertThat(second).containsExactly(first[0], first[1]);
        assertThat(a.getLastFilteredDistance()).isEqualTo(aDist);
    }

    @Test
    public void higherExponentPullsEstimateCloserToNearestBeacon() {
        List<P2BeaconSample> beacons = new ArrayList<>();
        beacons.add(primed("a:1:1", 0.0, 0.0, -59));   // d = 1
        beacons.add(primed("b:1:2", 10.0, 0.0, -79));  // d = 10
        beacons.add(primed("c:1:3", 0.0, 10.0, -79));  // d = 10

        double[] g2 = P2TrilaterationJavaSolver.estimatePositionWCL(beacons, 2.0);
        double[] g4 = P2TrilaterationJavaSolver.estimatePositionWCL(beacons, 4.0);

        // A larger exponent down-weights the far beacons more, so the estimate
        // sits closer to A at the origin.
        assertThat(g4[0]).isLessThan(g2[0]);
        assertThat(g4[1]).isLessThan(g2[1]);
        // Default overload equals g = 2.
        double[] def = P2TrilaterationJavaSolver.estimatePositionWCL(beacons);
        assertThat(def).containsExactly(g2[0], g2[1]);
    }

    @Test
    public void wclReturnsNullBelowThreeBeacons() {
        List<P2BeaconSample> two = new ArrayList<>();
        two.add(primed("a:1:1", 0.0, 0.0, -60));
        two.add(primed("b:1:2", 3.0, 0.0, -60));
        assertThat(P2TrilaterationJavaSolver.estimatePositionWCL(two)).isNull();
        assertThat(P2TrilaterationJavaSolver.estimatePositionWCL(two, 3.0)).isNull();
    }

    @Test
    public void findClosestReturnsTheNearestBeaconKey() {
        Map<String, P2BeaconSample> map = new LinkedHashMap<>();
        map.put("a:1:1", primed("a:1:1", 0.0, 0.0, -60));
        map.put("b:1:2", primed("b:1:2", 10.0, 0.0, -60));
        map.put("c:1:3", primed("c:1:3", 0.0, 10.0, -60));

        String closest = P2TrilaterationJavaSolver.findClosestToPosition(
                new double[]{0.2, 0.2}, map);
        assertThat(closest).isEqualTo("a:1:1");
    }

    @Test
    public void findClosestIsNullSafe() {
        assertThat(P2TrilaterationJavaSolver.findClosestToPosition(null, new LinkedHashMap<>()))
                .isNull();
        assertThat(P2TrilaterationJavaSolver.findClosestToPosition(new double[]{0, 0}, null))
                .isNull();
    }
}
