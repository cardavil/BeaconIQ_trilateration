package beaconiq.positioning.phase2;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.data.Offset;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-JVM tests for the real least-squares trilateration solver. They assert the
 * properties that distinguish genuine lateration from the old weighted-centroid:
 * it recovers a known point, can place the estimate OUTSIDE the beacon hull, and
 * its result DEPENDS on the absolute distance scale.
 */
public class P2TrilaterationJavaSolverTest {

    /** Sample whose filtered distance is primed exactly to {@code d} meters.
     *  With txPower=-59, n=2, scale=1: distance = 10^((-59 - rssi)/20); inverting,
     *  rssi = -59 - 20*log10(d). The first Kalman step returns the measurement,
     *  so getLastFilteredDistance() == d. */
    private static P2BeaconSample atDistance(String id, double x, double y, double d) {
        P2BeaconSample s = new P2BeaconSample(id, x, y);
        double rssi = -59.0 - 20.0 * Math.log10(d);
        s.addRssi(rssi);
        s.advanceDistanceFilter(-59, 2.0, 1.0);
        return s;
    }

    private static double dist(double[] p, double x, double y) {
        return Math.hypot(p[0] - x, p[1] - y);
    }

    @Test
    public void recoversAKnownInteriorPoint() {
        // True point (3,4); distances consistent with beacons at the axes.
        List<P2BeaconSample> bs = new ArrayList<>();
        bs.add(atDistance("a", 0, 0, 5.0));                 // |(3,4)-(0,0)|  = 5
        bs.add(atDistance("b", 10, 0, Math.sqrt(65)));      // |(3,4)-(10,0)| = √65
        bs.add(atDistance("c", 0, 10, Math.sqrt(45)));      // |(3,4)-(0,10)| = √45

        double[] p = P2TrilaterationJavaSolver.estimatePosition(bs);
        assertThat(p).isNotNull();
        assertThat(p[0]).isCloseTo(3.0, Offset.offset(0.05));
        assertThat(p[1]).isCloseTo(4.0, Offset.offset(0.05));
    }

    @Test
    public void recoversAPointOutsideTheBeaconHull() {
        // True point (8,8) is OUTSIDE the triangle (0,0)-(4,0)-(0,4).
        // WCL could never produce x>4 or y>4; real trilateration does.
        List<P2BeaconSample> bs = new ArrayList<>();
        bs.add(atDistance("a", 0, 0, Math.sqrt(128)));  // √(8²+8²)
        bs.add(atDistance("b", 4, 0, Math.sqrt(80)));   // √(4²+8²)
        bs.add(atDistance("c", 0, 4, Math.sqrt(80)));   // √(8²+4²)

        double[] p = P2TrilaterationJavaSolver.estimatePosition(bs);
        assertThat(p).isNotNull();
        assertThat(p[0]).isCloseTo(8.0, Offset.offset(0.1));
        assertThat(p[1]).isCloseTo(8.0, Offset.offset(0.1));
        assertThat(p[0]).isGreaterThan(4.0); // outside the hull — impossible for WCL
    }

    @Test
    public void resultDependsOnAbsoluteDistanceScale() {
        // Real lateration is NOT scale-invariant (WCL was). Doubling every radius
        // must move the estimate.
        double[] base = P2TrilaterationJavaSolver.estimatePosition(triangle(1.0));
        double[] scaled = P2TrilaterationJavaSolver.estimatePosition(triangle(2.0));
        assertThat(base).isNotNull();
        assertThat(scaled).isNotNull();
        assertThat(dist(base, scaled[0], scaled[1])).isGreaterThan(0.5);
    }

    private static List<P2BeaconSample> triangle(double scale) {
        List<P2BeaconSample> bs = new ArrayList<>();
        bs.add(atDistance("a", 0, 0, 5.0 * scale));
        bs.add(atDistance("b", 10, 0, Math.sqrt(65) * scale));
        bs.add(atDistance("c", 0, 10, Math.sqrt(45) * scale));
        return bs;
    }

    @Test
    public void returnsNullBelowThreeUsableBeacons() {
        List<P2BeaconSample> two = new ArrayList<>();
        two.add(atDistance("a", 0, 0, 3.0));
        two.add(atDistance("b", 4, 0, 3.0));
        assertThat(P2TrilaterationJavaSolver.estimatePosition(two)).isNull();
        assertThat(P2TrilaterationJavaSolver.estimatePosition(null)).isNull();
    }

    @Test
    public void residualIsSmallForConsistentDistances() {
        double[] p = P2TrilaterationJavaSolver.estimatePosition(triangle(1.0));
        double rms = P2TrilaterationJavaSolver.residualRms(triangle(1.0), p);
        assertThat(rms).isLessThan(0.05);
    }

    @Test
    public void findClosestReturnsTheNearestBeaconKey() {
        Map<String, P2BeaconSample> map = new LinkedHashMap<>();
        map.put("a:1:1", atDistance("a:1:1", 0.0, 0.0, 3.0));
        map.put("b:1:2", atDistance("b:1:2", 10.0, 0.0, 3.0));
        map.put("c:1:3", atDistance("c:1:3", 0.0, 10.0, 3.0));
        String closest = P2TrilaterationJavaSolver.findClosestToPosition(new double[]{0.2, 0.2}, map);
        assertThat(closest).isEqualTo("a:1:1");
    }

    @Test
    public void findClosestIsNullSafe() {
        assertThat(P2TrilaterationJavaSolver.findClosestToPosition(null, new LinkedHashMap<>())).isNull();
        assertThat(P2TrilaterationJavaSolver.findClosestToPosition(new double[]{0, 0}, null)).isNull();
    }
}
