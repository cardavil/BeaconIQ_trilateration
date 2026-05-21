package com.beaconiq.trilateration.scan;

import static org.assertj.core.api.Assertions.assertThat;

import org.altbeacon.beacon.Beacon;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BleScannerRoutingTest {

    private static byte[] hex(String hexString) {
        String clean = hexString.replaceAll("\\s+", "");
        byte[] result = new byte[clean.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(
                    clean.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static byte[] buildIBeaconScanRecord(String uuidHex,
                                                  int major, int minor,
                                                  int txPower) {
        byte[] record = new byte[30];
        record[0] = 0x02; record[1] = 0x01; record[2] = 0x06;
        record[3] = 0x1A; record[4] = (byte) 0xFF;
        record[5] = 0x4C; record[6] = 0x00;
        record[7] = 0x02; record[8] = 0x15;
        byte[] uuidBytes = hex(uuidHex);
        System.arraycopy(uuidBytes, 0, record, 9, 16);
        record[25] = (byte) ((major >> 8) & 0xFF);
        record[26] = (byte) (major & 0xFF);
        record[27] = (byte) ((minor >> 8) & 0xFF);
        record[28] = (byte) (minor & 0xFF);
        record[29] = (byte) txPower;
        return record;
    }

    @Test
    public void parseIBeacon_validData_returnsBeacon() {
        byte[] scanRecord = buildIBeaconScanRecord(
                "f7826da64fa24e988024bc5b71e0893e", 100, 1, -59);

        Beacon beacon = BleScanner.parseIBeacon(
                "AA:BB:CC:DD:EE:FF", -67, scanRecord);

        assertThat(beacon).isNotNull();
        assertThat(beacon.getId1().toString())
                .isEqualTo("f7826da6-4fa2-4e98-8024-bc5b71e0893e");
        assertThat(beacon.getId2().toInt()).isEqualTo(100);
        assertThat(beacon.getId3().toInt()).isEqualTo(1);
        assertThat(beacon.getTxPower()).isEqualTo(-59);
        assertThat(beacon.getRssi()).isEqualTo(-67);
        assertThat(beacon.getBluetoothAddress())
                .isEqualTo("AA:BB:CC:DD:EE:FF");
    }

    @Test
    public void parseIBeacon_nonIBeaconAppleData_returnsNull() {
        byte[] record = new byte[30];
        record[0] = 0x02; record[1] = 0x01; record[2] = 0x06;
        record[3] = 0x1A; record[4] = (byte) 0xFF;
        record[5] = 0x4C; record[6] = 0x00;
        record[7] = 0x10; record[8] = 0x05;

        Beacon beacon = BleScanner.parseIBeacon(
                "AA:BB:CC:DD:EE:FF", -67, record);

        assertThat(beacon).isNull();
    }

    @Test
    public void parseIBeacon_nonAppleManufacturer_returnsNull() {
        byte[] record = new byte[30];
        record[0] = 0x02; record[1] = 0x01; record[2] = 0x06;
        record[3] = 0x1A; record[4] = (byte) 0xFF;
        record[5] = 0x06; record[6] = 0x00;
        record[7] = 0x02; record[8] = 0x15;

        Beacon beacon = BleScanner.parseIBeacon(
                "AA:BB:CC:DD:EE:FF", -67, record);

        assertThat(beacon).isNull();
    }

    @Test
    public void parseIBeacon_nullScanRecord_returnsNull() {
        assertThat(BleScanner.parseIBeacon(
                "AA:BB:CC:DD:EE:FF", -67, null)).isNull();
    }

    @Test
    public void parseIBeacon_shortScanRecord_returnsNull() {
        assertThat(BleScanner.parseIBeacon(
                "AA:BB:CC:DD:EE:FF", -67, new byte[10])).isNull();
    }

    @Test
    public void bleDevice_carriesScanRecord() {
        byte[] record = {0x02, 0x01, 0x06, (byte) 0xFF};
        BleDevice device = new BleDevice("AA:BB:CC:DD:EE:FF", "Test",
                -67, System.currentTimeMillis(), record);

        assertThat(device.getScanRecord()).isEqualTo(record);
        assertThat(device.getMacAddress()).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(device.getRssi()).isEqualTo(-67);
    }

    @Test
    public void bleDevice_nullScanRecord() {
        BleDevice device = new BleDevice("AA:BB:CC:DD:EE:FF", "Test",
                -67, System.currentTimeMillis());

        assertThat(device.getScanRecord()).isNull();
    }

    @Test
    public void bleDevice_legacyConstructorCompat() {
        BleDevice a = new BleDevice("AA:BB:CC:DD:EE:FF", "Test", -67, 1000L);
        BleDevice b = new BleDevice("AA:BB:CC:DD:EE:FF", "Test", -67, 1000L, null);

        assertThat(a).isEqualTo(b);
    }
}
