package beaconiq.model;

import java.util.Objects;

public class Beacon {

    private final String uuid;
    private final int major;
    private final int minor;
    private final double x;
    private final double y;
    private final double txPower;
    private final double pathLossN;

    public Beacon(String uuid, int major, int minor, double x, double y,
                  double txPower, double pathLossN) {
        this.uuid = uuid;
        this.major = major;
        this.minor = minor;
        this.x = x;
        this.y = y;
        this.txPower = txPower;
        this.pathLossN = pathLossN;
    }

    public String getUuid() { return uuid; }
    public int getMajor() { return major; }
    public int getMinor() { return minor; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getTxPower() { return txPower; }
    public double getPathLossN() { return pathLossN; }

    public String getCompositeId() {
        return uuid + ":" + major + ":" + minor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Beacon)) return false;
        Beacon b = (Beacon) o;
        return major == b.major && minor == b.minor
                && Double.compare(x, b.x) == 0
                && Double.compare(y, b.y) == 0
                && Double.compare(txPower, b.txPower) == 0
                && Double.compare(pathLossN, b.pathLossN) == 0
                && Objects.equals(uuid, b.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, major, minor, x, y, txPower, pathLossN);
    }
}
