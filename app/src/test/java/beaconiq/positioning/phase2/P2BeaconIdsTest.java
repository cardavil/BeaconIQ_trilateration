package beaconiq.positioning.phase2;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Pure-JVM tests for the composite-id helpers. The composite id is
 * "uuid:major:minor" for iBeacons and may have fewer segments for other
 * frame types (e.g. Eddystone namespace:instance).
 */
public class P2BeaconIdsTest {

    private static final String UUID =
            "f7826da6-4fa2-4e98-8024-bc5b71e0893e";
    private static final String COMPOSITE = UUID + ":2:1";

    @Test
    public void extractUuidReturnsFirstSegment() {
        assertThat(P2BeaconIds.extractUuid(COMPOSITE)).isEqualTo(UUID);
    }

    @Test
    public void extractUuidWithoutSeparatorReturnsWholeId() {
        assertThat(P2BeaconIds.extractUuid(UUID)).isEqualTo(UUID);
    }

    @Test
    public void extractUuidTwoSegmentIdReturnsFirstSegment() {
        assertThat(P2BeaconIds.extractUuid("namespace:instance"))
                .isEqualTo("namespace");
    }

    @Test
    public void extractLabelReturnsMajorMinor() {
        assertThat(P2BeaconIds.extractLabel(COMPOSITE)).isEqualTo("2,1");
    }

    @Test
    public void extractLabelFallsBackToCompositeId() {
        assertThat(P2BeaconIds.extractLabel("namespace:instance"))
                .isEqualTo("namespace:instance");
    }

    @Test
    public void shortUidTruncatesLongUuid() {
        assertThat(P2BeaconIds.shortUid(COMPOSITE))
                .isEqualTo("f7826da6...:2:1");
    }

    @Test
    public void shortUidLeavesShortIdsUntouched() {
        assertThat(P2BeaconIds.shortUid("namespace:instance"))
                .isEqualTo("namespace:instance");
    }
}
