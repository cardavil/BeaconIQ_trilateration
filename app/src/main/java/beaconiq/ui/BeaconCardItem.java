package beaconiq.ui;

public class BeaconCardItem implements Comparable<BeaconCardItem> {

    private final String compositeId;
    private final String label;
    private final String uuid;
    private final int lastRawRssi;
    private final double filteredDistanceMeters;
    private final boolean isClosest;

    public BeaconCardItem(String compositeId, String label, String uuid,
                          int lastRawRssi, double filteredDistanceMeters,
                          boolean isClosest) {
        this.compositeId = compositeId;
        this.label = label;
        this.uuid = uuid;
        this.lastRawRssi = lastRawRssi;
        this.filteredDistanceMeters = filteredDistanceMeters;
        this.isClosest = isClosest;
    }

    public String getCompositeId() { return compositeId; }
    public String getLabel() { return label; }
    public String getUuid() { return uuid; }
    public int getLastRawRssi() { return lastRawRssi; }
    public double getFilteredDistanceMeters() { return filteredDistanceMeters; }
    public boolean isClosest() { return isClosest; }

    @Override
    public int compareTo(BeaconCardItem other) {
        return Double.compare(this.filteredDistanceMeters, other.filteredDistanceMeters);
    }
}
