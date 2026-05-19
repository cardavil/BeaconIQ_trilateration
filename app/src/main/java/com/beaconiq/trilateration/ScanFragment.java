package com.beaconiq.trilateration;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.beaconiq.trilateration.positioning.phase2.BeaconSample;
import com.beaconiq.trilateration.positioning.phase2.TrilaterationJavaSolver;
import com.beaconiq.trilateration.scan.BleDevice;
import com.beaconiq.trilateration.scan.BleScanner;
import com.beaconiq.trilateration.sensor.OrientationSensor;
import com.beaconiq.trilateration.storage.CalibrationStore;
import com.beaconiq.trilateration.ui.DeviceListAdapter;
import com.beaconiq.trilateration.ui.PositioningCanvasView;

import org.altbeacon.beacon.Beacon;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ScanFragment extends Fragment implements BleScanner.ScanListener {

    private static final String PREFS_BEACON = "debug_panel";
    private static final long STALE_CHECK_INTERVAL_MS = 1000;
    private static final long STALE_DEVICE_AGE_MS = 10_000;

    private int txPower = -59;
    private double pathLossN = 2.0;
    private double scaleFactor = 5.0;
    private double kalmanQ = 0.05;
    private double kalmanR = 0.25;
    private int rssiBufferSize = 20;
    private long rssiTimeWindowMs = 8000;
    private long beaconTimeoutMs = 6000;
    private long evalIntervalMs = 2000;
    private int rssiThreshold = -100;
    private int solverIndex = 1; // 0=Centroid, 1=WCL

    private TextView statusText;
    private Button scanButton;
    private View paramsRow;
    private TextView paramsToggle;
    private TextView paramsLabel;
    private BleScanner bleScanner;
    private DeviceListAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean wasScanning;

    private PositioningCanvasView exploreCanvas;
    private CalibrationStore calibrationStore;
    private OrientationSensor orientationSensor;
    private final Map<String, BeaconSample> beaconSampleMap = new ConcurrentHashMap<>();
    private int autoPositionCounter = 0;

    private final Runnable staleRunnable = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) {
                adapter.removeStaleDevices(STALE_DEVICE_AGE_MS);
            }
            handler.postDelayed(this, STALE_CHECK_INTERVAL_MS);
        }
    };

    private final Runnable positionEvalRunnable = new Runnable() {
        @Override
        public void run() {
            if (bleScanner != null && bleScanner.isScanning()) {
                evaluatePosition();
                handler.postDelayed(this, evalIntervalMs);
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        statusText = view.findViewById(R.id.status_text);
        scanButton = view.findViewById(R.id.scan_button);
        RecyclerView deviceList = view.findViewById(R.id.device_list);
        exploreCanvas = view.findViewById(R.id.explore_canvas);
        paramsRow = view.findViewById(R.id.params_row);
        paramsToggle = view.findViewById(R.id.params_toggle);
        paramsLabel = view.findViewById(R.id.params_label);

        paramsToggle.setOnClickListener(v -> {
            boolean hidden = paramsLabel.getVisibility() == View.GONE;
            paramsLabel.setVisibility(hidden ? View.VISIBLE : View.GONE);
            paramsToggle.setText(hidden ? "(x)" : "(?)");
        });

        bleScanner = new BleScanner(requireContext());
        bleScanner.setListener(this);

        calibrationStore = new CalibrationStore(requireContext());
        loadPositioningParams();

        orientationSensor = new OrientationSensor();
        orientationSensor.setListener((azimuth, pitch, roll) -> {
            if (exploreCanvas != null) {
                exploreCanvas.updateOrientation(azimuth, pitch, roll);
            }
        });

        adapter = new DeviceListAdapter();
        deviceList.setLayoutManager(new LinearLayoutManager(requireContext()));
        deviceList.setAdapter(adapter);

        handler.postDelayed(staleRunnable, STALE_CHECK_INTERVAL_MS);

        setStatus("Idle", R.color.text_dim);
        scanButton.setOnClickListener(v -> toggleScan());
    }

    private void toggleScan() {
        if (bleScanner.isScanning()) {
            bleScanner.stopScan();
            scanButton.setText("Start Scan");
            scanButton.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.teal)));
            scanButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
            setStatus("Stopped", R.color.text_dim);

            handler.removeCallbacks(positionEvalRunnable);
            exploreCanvas.setVisibility(View.GONE);
            paramsRow.setVisibility(View.GONE);
            exploreCanvas.clear();
            beaconSampleMap.clear();
            autoPositionCounter = 0;
        } else {
            loadPositioningParams();
            bleScanner.startScan();
            scanButton.setText("Stop Scan");
            scanButton.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.surface_2)));
            scanButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_alert));
            setStatus("Scanning", R.color.status_ok);

            beaconSampleMap.clear();
            autoPositionCounter = 0;
            exploreCanvas.clear();
            exploreCanvas.setVisibility(View.VISIBLE);
            paramsRow.setVisibility(View.VISIBLE);
            handler.postDelayed(positionEvalRunnable, evalIntervalMs);
        }
    }

    private void loadPositioningParams() {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_BEACON, 0);
        txPower = prefs.getInt("debug_default_tx_power", -59);
        pathLossN = prefs.getFloat("debug_path_loss_n", 2.0f);
        scaleFactor = prefs.getFloat("debug_scale_factor", 5.0f);
        kalmanQ = prefs.getFloat("debug_kalman_q", 0.05f);
        kalmanR = prefs.getFloat("debug_kalman_r", 0.25f);
        rssiBufferSize = prefs.getInt("debug_rssi_buffer_size", 20);
        rssiTimeWindowMs = prefs.getInt("debug_rssi_time_window_ms", 8000);
        beaconTimeoutMs = prefs.getInt("debug_beacon_timeout_ms", 6000);
        evalIntervalMs = prefs.getInt("debug_eval_interval_ms", 2000);
        rssiThreshold = prefs.getInt("debug_rssi_threshold", -100);
        solverIndex = prefs.getInt("debug_solver_index", 1);

        if (paramsLabel != null) {
            java.util.List<com.beaconiq.trilateration.model.Beacon> calibrated =
                    calibrationStore != null
                            ? calibrationStore.getAllBeacons()
                            : java.util.Collections.emptyList();
            String solverName = solverIndex == 1 ? "WCL" : "Centroid";

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US,
                    "TX Power: %d dBm | Path Loss N: %.1f\n"
                    + "RSSI Threshold: %d dBm\n"
                    + "Kalman Q: %.3f | Kalman R: %.3f\n"
                    + "RSSI Buffer: %d | RSSI Window: %dms\n"
                    + "Scale Factor: %.1f\n"
                    + "Solver: %s\n"
                    + "Beacon Timeout: %dms | Eval Interval: %dms\n"
                    + "Calibrated beacons: %d",
                    txPower, pathLossN,
                    rssiThreshold,
                    kalmanQ, kalmanR,
                    rssiBufferSize, rssiTimeWindowMs,
                    scaleFactor,
                    solverName,
                    beaconTimeoutMs, evalIntervalMs,
                    calibrated.size()));

            if (!calibrated.isEmpty()) {
                sb.append("\n— Beacons —");
                for (com.beaconiq.trilateration.model.Beacon b : calibrated) {
                    sb.append(String.format(Locale.US, "\n  %d,%d @ (%.1f, %.1f)",
                            b.getMajor(), b.getMinor(), b.getX(), b.getY()));
                }
            }

            paramsLabel.setText(sb.toString());
        }
    }

    private void evaluatePosition() {
        long now = System.currentTimeMillis();
        beaconSampleMap.entrySet().removeIf(e -> now - e.getValue().lastSeen > beaconTimeoutMs);

        double[] position;
        if (solverIndex == 1) {
            position = TrilaterationJavaSolver.estimatePositionWCL(
                    beaconSampleMap.values(), txPower, pathLossN, scaleFactor);
        } else {
            position = TrilaterationJavaSolver.estimatePosition(
                    beaconSampleMap.values());
        }

        String closestKey = null;
        if (position != null) {
            closestKey = TrilaterationJavaSolver.findClosestToPosition(
                    position, beaconSampleMap);
        }

        exploreCanvas.updateP2(new HashMap<>(beaconSampleMap), position, closestKey);
    }

    private String buildCompositeId(Beacon beacon) {
        String id = beacon.getId1().toString();
        if (beacon.getIdentifiers().size() >= 2) id += ":" + beacon.getId2();
        if (beacon.getIdentifiers().size() >= 3) id += ":" + beacon.getId3();
        return id;
    }

    private double[] getBeaconPosition(String compositeId, Beacon beacon) {
        String uuid = beacon.getId1().toString();
        int major = beacon.getIdentifiers().size() >= 2 ? beacon.getId2().toInt() : 0;
        int minor = beacon.getIdentifiers().size() >= 3 ? beacon.getId3().toInt() : 0;

        com.beaconiq.trilateration.model.Beacon calibrated =
                calibrationStore.getBeacon(uuid, major, minor);
        if (calibrated != null) {
            return new double[]{calibrated.getX(), calibrated.getY()};
        }

        double cx = 5.0, cy = 5.0, r = 3.5;
        double angle = 2 * Math.PI * autoPositionCounter / 6.0;
        autoPositionCounter++;
        return new double[]{cx + r * Math.cos(angle), cy + r * Math.sin(angle)};
    }

    private void setStatus(String label, int dotColorRes) {
        String text = "● " + label;
        SpannableString span = new SpannableString(text);
        span.setSpan(new ForegroundColorSpan(ContextCompat.getColor(requireContext(), dotColorRes)),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new ForegroundColorSpan(
                        ContextCompat.getColor(requireContext(), R.color.text_muted)),
                2, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        statusText.setText(span);
    }

    public void onPermissionsResult(boolean allGranted) {
        if (!allGranted && statusText != null) {
            statusText.setText("Permissions denied — cannot scan");
            scanButton.setEnabled(false);
        }
    }

    public void stopScanForRecording() {
        wasScanning = bleScanner.isScanning();
        if (wasScanning) {
            bleScanner.stopScan();
            scanButton.setText("Start Scan");
            scanButton.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.teal)));
            scanButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
            setStatus("Paused (recording)", R.color.status_warn);
        }
        scanButton.setEnabled(false);
        handler.removeCallbacks(positionEvalRunnable);
        exploreCanvas.setVisibility(View.GONE);
        paramsRow.setVisibility(View.GONE);
        exploreCanvas.clear();
        beaconSampleMap.clear();
    }

    public void resumeAfterRecording() {
        scanButton.setEnabled(true);
        if (wasScanning) {
            loadPositioningParams();
            bleScanner.startScan();
            scanButton.setText("Stop Scan");
            scanButton.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.surface_2)));
            scanButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_alert));
            setStatus("Scanning", R.color.status_ok);

            beaconSampleMap.clear();
            autoPositionCounter = 0;
            exploreCanvas.clear();
            exploreCanvas.setVisibility(View.VISIBLE);
            paramsRow.setVisibility(View.VISIBLE);
            handler.postDelayed(positionEvalRunnable, evalIntervalMs);
        } else {
            setStatus("Idle", R.color.text_dim);
        }
        wasScanning = false;
    }

    @Override
    public void onBeaconDiscovered(Beacon beacon, byte[] scanRecord) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (adapter != null) adapter.updateBeacon(beacon);

                if (bleScanner != null && bleScanner.isScanning()) {
                    String compositeId = buildCompositeId(beacon);
                    BeaconSample sample = beaconSampleMap.get(compositeId);
                    if (sample == null) {
                        double[] pos = getBeaconPosition(compositeId, beacon);
                        sample = new BeaconSample(compositeId, pos[0], pos[1],
                                kalmanQ, kalmanR, rssiBufferSize, rssiTimeWindowMs);
                        beaconSampleMap.put(compositeId, sample);
                    }
                    sample.addRssi(beacon.getRssi());
                }
            });
        }
    }

    @Override
    public void onGenericDeviceDiscovered(BleDevice device) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (adapter != null) adapter.updateDevice(device);
            });
        }
    }

    @Override
    public void onScanFailed(int errorCode) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (statusText != null) {
                    statusText.setText("Scan failed: " + errorCode);
                    scanButton.setText("Start Scan");
                    scanButton.setBackgroundTintList(ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.teal)));
                    scanButton.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.text_primary));
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (orientationSensor != null) {
            orientationSensor.start(requireActivity());
        }
        loadPositioningParams();
        if (statusText != null && scanButton != null) {
            boolean allGranted = checkAllPermissions();
            if (allGranted && !scanButton.isEnabled()) {
                scanButton.setEnabled(true);
                setStatus("Idle", R.color.text_dim);
            } else if (!allGranted) {
                statusText.setText("Permissions denied — cannot scan");
                scanButton.setEnabled(false);
            }
        }
    }

    private boolean checkAllPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (orientationSensor != null) {
            orientationSensor.stop();
        }
        if (bleScanner != null && bleScanner.isScanning()) {
            bleScanner.stopScan();
            handler.removeCallbacks(positionEvalRunnable);
            if (scanButton != null) {
                scanButton.setText("Start Scan");
                scanButton.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.teal)));
                scanButton.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.text_primary));
                setStatus("Stopped", R.color.text_dim);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacks(staleRunnable);
        handler.removeCallbacks(positionEvalRunnable);
        if (orientationSensor != null) {
            orientationSensor.stop();
        }
        if (bleScanner != null && bleScanner.isScanning()) {
            bleScanner.stopScan();
        }
    }
}
