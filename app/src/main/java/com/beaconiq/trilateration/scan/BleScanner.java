package com.beaconiq.trilateration.scan;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.util.Log;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class BleScanner {

    public interface ScanListener {
        void onBeaconDiscovered(Beacon beacon, byte[] scanRecord);
        void onGenericDeviceDiscovered(BleDevice device);
        void onScanFailed(int errorCode);
    }

    private static final String TAG = "BeaconIQ";

    private final Context appContext;
    private ScanListener listener;
    private boolean isScanning;
    private final Set<String> knownBeaconMacs = new CopyOnWriteArraySet<>();
    private final Map<String, byte[]> rawBytesCache = new ConcurrentHashMap<>();

    private BeaconManager beaconManager;
    private Region allRegion;
    private BluetoothLeScanner leScanner;
    private ScanCallback genericCallback;

    public BleScanner(Context context) {
        appContext = context.getApplicationContext();
        initBeaconManager();
        initGenericCallback();
    }

    private void initBeaconManager() {
        beaconManager = BeaconManager.getInstanceForApplication(appContext);
        allRegion = new Region("all-beacons", null, null, null);
        beaconManager.getBeaconParsers().clear();

        beaconManager.getBeaconParsers().add(new BeaconParser()
                .setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"));
        beaconManager.getBeaconParsers().add(new BeaconParser()
                .setBeaconLayout("m:2-3=beac,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"));
        beaconManager.getBeaconParsers().add(new BeaconParser()
                .setBeaconLayout("s:0-1=feaa,m:2-2=00,p:3-3:-41,i:4-13,i:14-19"));
        beaconManager.getBeaconParsers().add(new BeaconParser()
                .setBeaconLayout("s:0-1=feaa,m:2-2=10,p:3-3:-41,i:4-20v"));
        beaconManager.getBeaconParsers().add(new BeaconParser()
                .setBeaconLayout("x:0-1=feaa,m:2-2=20,d:3-3,d:4-5,d:6-7,d:8-11,d:12-15"));
        beaconManager.getBeaconParsers().add(new BeaconParser()
                .setBeaconLayout("s:0-1=fed8,m:2-2=00,p:3-3:-41,i:4-21v"));

        beaconManager.addRangeNotifier((beacons, region) -> {
            if (listener == null) return;
            for (Beacon b : beacons) {
                knownBeaconMacs.add(b.getBluetoothAddress());
                Log.d(TAG, "Beacon: " + b.getId1()
                        + " txPower=" + b.getTxPower()
                        + " rssi=" + b.getRssi());
                byte[] cached = rawBytesCache.remove(b.getBluetoothAddress());
                listener.onBeaconDiscovered(b, cached);
            }
        });
    }

    private void initGenericCallback() {
        genericCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                routeGenericResult(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult r : results) routeGenericResult(r);
            }

            @Override
            public void onScanFailed(int errorCode) {
                if (listener != null) listener.onScanFailed(errorCode);
            }
        };
    }

    @SuppressLint("MissingPermission")
    private void routeGenericResult(ScanResult result) {
        if (listener == null) return;
        String mac = result.getDevice().getAddress();
        byte[] bytes = result.getScanRecord() != null
                ? result.getScanRecord().getBytes() : null;
        if (knownBeaconMacs.contains(mac)) {
            if (bytes != null) rawBytesCache.put(mac, bytes);
            return;
        }
        long now = System.currentTimeMillis();
        String name = result.getDevice().getName();
        listener.onGenericDeviceDiscovered(
                new BleDevice(mac, name, result.getRssi(), now, bytes));
    }

    public void setListener(ScanListener listener) {
        this.listener = listener;
    }

    public boolean isScanning() {
        return isScanning;
    }

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (isScanning) return;

        try {
            beaconManager.startRangingBeacons(allRegion);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start beacon ranging", e);
            if (listener != null) listener.onScanFailed(-1);
            return;
        }

        BluetoothManager manager =
                (BluetoothManager) appContext.getSystemService(
                        Context.BLUETOOTH_SERVICE);
        if (manager != null) {
            BluetoothAdapter adapter = manager.getAdapter();
            if (adapter != null && adapter.isEnabled()) {
                BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
                if (scanner != null) {
                    leScanner = scanner;
                    ScanSettings settings = new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build();
                    scanner.startScan(new ArrayList<>(), settings, genericCallback);
                }
            }
        }

        isScanning = true;
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!isScanning) return;

        try {
            beaconManager.stopRangingBeacons(allRegion);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop beacon ranging", e);
        }

        if (leScanner != null) {
            try {
                leScanner.stopScan(genericCallback);
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop generic scanner", e);
            }
        }

        rawBytesCache.clear();
        isScanning = false;
    }

    static Beacon parseIBeacon(String mac, int rssi, byte[] scanRecord) {
        if (scanRecord == null || scanRecord.length < 25) return null;

        int offset = -1;
        for (int i = 0; i < scanRecord.length - 24; i++) {
            if ((scanRecord[i] & 0xFF) == 0x4C
                    && (scanRecord[i + 1] & 0xFF) == 0x00
                    && (scanRecord[i + 2] & 0xFF) == 0x02
                    && (scanRecord[i + 3] & 0xFF) == 0x15) {
                offset = i + 4;
                break;
            }
        }

        if (offset < 0 || offset + 21 > scanRecord.length) return null;

        StringBuilder uuidBuilder = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            uuidBuilder.append(String.format(Locale.US, "%02x",
                    scanRecord[offset + i] & 0xFF));
            if (i == 3 || i == 5 || i == 7 || i == 9) {
                uuidBuilder.append('-');
            }
        }

        int major = ((scanRecord[offset + 16] & 0xFF) << 8)
                | (scanRecord[offset + 17] & 0xFF);
        int minor = ((scanRecord[offset + 18] & 0xFF) << 8)
                | (scanRecord[offset + 19] & 0xFF);
        int txPower = scanRecord[offset + 20];

        return new Beacon.Builder()
                .setId1(uuidBuilder.toString())
                .setId2(String.valueOf(major))
                .setId3(String.valueOf(minor))
                .setManufacturer(0x004C)
                .setTxPower(txPower)
                .setRssi(rssi)
                .setBluetoothAddress(mac)
                .build();
    }
}
