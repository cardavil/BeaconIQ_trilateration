package beaconiq.positioning.phase2;

import org.altbeacon.beacon.Beacon;

/**
 * Pure, stateless helpers for beacon identity and display labels.
 *
 * Shared by ScanFragment (Explore) and PhaseTwoTestFragment, which used to
 * each carry their own copy.
 */
public final class P2BeaconIds {

    private P2BeaconIds() {}

    /** Builds the "uuid:major:minor" composite id used as the map key. */
    public static String buildCompositeId(Beacon beacon) {
        String id = beacon.getId1().toString();
        if (beacon.getIdentifiers().size() >= 2) id += ":" + beacon.getId2();
        if (beacon.getIdentifiers().size() >= 3) id += ":" + beacon.getId3();
        return id;
    }

    /** Compact id for status panels: "uuid8...:major:minor". */
    public static String shortUid(String compositeId) {
        String[] parts = compositeId.split(":");
        if (parts.length >= 3) {
            String uuid = parts[0];
            if (uuid.length() > 8) uuid = uuid.substring(0, 8) + "...";
            return uuid + ":" + parts[1] + ":" + parts[2];
        }
        return compositeId;
    }

    /** Canvas label: "major,minor". */
    public static String extractLabel(String compositeId) {
        String[] parts = compositeId.split(":");
        if (parts.length >= 3) {
            return parts[parts.length - 2] + "," + parts[parts.length - 1];
        }
        return compositeId;
    }
}
