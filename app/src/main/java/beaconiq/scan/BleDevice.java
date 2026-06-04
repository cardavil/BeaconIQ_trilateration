package beaconiq.scan;

import java.util.Objects;

public class BleDevice {

    private final String macAddress;
    private final String name;
    private final int rssi;
    private final long lastSeenMs;
    private final byte[] scanRecord;

    public BleDevice(String macAddress, String name, int rssi, long lastSeenMs) {
        this(macAddress, name, rssi, lastSeenMs, null);
    }

    public BleDevice(String macAddress, String name, int rssi, long lastSeenMs,
                     byte[] scanRecord) {
        this.macAddress = macAddress;
        this.name = name;
        this.rssi = rssi;
        this.lastSeenMs = lastSeenMs;
        this.scanRecord = scanRecord;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public String getName() {
        return name;
    }

    public int getRssi() {
        return rssi;
    }

    public long getLastSeenMs() {
        return lastSeenMs;
    }

    public byte[] getScanRecord() {
        return scanRecord;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BleDevice)) return false;
        BleDevice that = (BleDevice) o;
        return Objects.equals(macAddress, that.macAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(macAddress);
    }
}
