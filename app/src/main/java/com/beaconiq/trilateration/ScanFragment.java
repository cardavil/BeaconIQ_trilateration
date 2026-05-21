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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
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

    private static final String[] SOLVER_MODES = {"Centroid", "WCL"};

    private TextView statusText;
    private Button scanButton;
    private BleScanner bleScanner;

    private View headerParams, contentParams, headerDevices, contentDevices;
    private TextView arrowParams, arrowDevices;
    private EditText editTxPower, editPathLoss, editRssiThreshold;
    private EditText editKalmanQ, editKalmanR, editRssiBuffer, editRssiWindow;
    private EditText editScaleFactor, editBeaconTimeout, editEvalInterval;
    private Spinner spinnerSolver;
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

        headerParams = view.findViewById(R.id.header_params);
        arrowParams = view.findViewById(R.id.arrow_params);
        contentParams = view.findViewById(R.id.content_params);
        headerDevices = view.findViewById(R.id.header_devices);
        arrowDevices = view.findViewById(R.id.arrow_devices);
        contentDevices = view.findViewById(R.id.content_devices);

        editTxPower = view.findViewById(R.id.explore_edit_tx_power);
        editPathLoss = view.findViewById(R.id.explore_edit_path_loss);
        editRssiThreshold = view.findViewById(R.id.explore_edit_rssi_threshold);
        editKalmanQ = view.findViewById(R.id.explore_edit_kalman_q);
        editKalmanR = view.findViewById(R.id.explore_edit_kalman_r);
        editRssiBuffer = view.findViewById(R.id.explore_edit_rssi_buffer);
        editRssiWindow = view.findViewById(R.id.explore_edit_rssi_window);
        editScaleFactor = view.findViewById(R.id.explore_edit_scale_factor);
        editBeaconTimeout = view.findViewById(R.id.explore_edit_beacon_timeout);
        editEvalInterval = view.findViewById(R.id.explore_edit_eval_interval);
        spinnerSolver = view.findViewById(R.id.explore_spinner_solver);

        ArrayAdapter<String> solverAdapter = new ArrayAdapter<>(requireContext(),
                R.layout.spinner_item, SOLVER_MODES);
        solverAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerSolver.setAdapter(solverAdapter);

        bleScanner = new BleScanner(requireContext());
        bleScanner.setListener(this);

        calibrationStore = new CalibrationStore(requireContext());
        loadPositioningParams();
        setupParameterListeners();

        headerParams.setOnClickListener(v -> toggleAccordion(contentParams, arrowParams));
        headerDevices.setOnClickListener(v -> toggleAccordion(contentDevices, arrowDevices));

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
            exploreCanvas.clear();
            beaconSampleMap.clear();
            autoPositionCounter = 0;
        } else {
            applyAllParameters();
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

        if (editTxPower != null) {
            editTxPower.setText(String.valueOf(txPower));
            editPathLoss.setText(String.format(Locale.US, "%.1f", pathLossN));
            editRssiThreshold.setText(String.valueOf(rssiThreshold));
            editKalmanQ.setText(String.format(Locale.US, "%.3f", kalmanQ));
            editKalmanR.setText(String.format(Locale.US, "%.3f", kalmanR));
            editRssiBuffer.setText(String.valueOf(rssiBufferSize));
            editRssiWindow.setText(String.valueOf(rssiTimeWindowMs));
            editScaleFactor.setText(String.format(Locale.US, "%.1f", scaleFactor));
            editBeaconTimeout.setText(String.valueOf(beaconTimeoutMs));
            editEvalInterval.setText(String.valueOf(evalIntervalMs));
            spinnerSolver.setSelection(solverIndex);
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
        exploreCanvas.clear();
        beaconSampleMap.clear();
    }

    public void resumeAfterRecording() {
        scanButton.setEnabled(true);
        if (wasScanning) {
            applyAllParameters();
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

    // --- Accordion & parameter helpers ---

    private void toggleAccordion(View content, TextView arrow) {
        boolean collapsed = content.getVisibility() == View.GONE;
        content.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        arrow.setText(collapsed ? "▼" : "▶");
    }

    private void setupParameterListeners() {
        View.OnFocusChangeListener paramFocusListener = (v, hasFocus) -> {
            if (!hasFocus) {
                applyAllParameters();
            }
        };

        EditText[] allFields = {editTxPower, editPathLoss, editRssiThreshold,
                editKalmanQ, editKalmanR, editRssiBuffer, editRssiWindow,
                editScaleFactor, editBeaconTimeout, editEvalInterval};
        for (EditText et : allFields) {
            et.setOnFocusChangeListener(paramFocusListener);
        }

        spinnerSolver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                solverIndex = position;
                saveBeaconConfig();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void applyAllParameters() {
        txPower = readInt(editTxPower, -59, -100, 0);
        pathLossN = readDouble(editPathLoss, 2.0, 1.0, 6.0);
        rssiThreshold = readInt(editRssiThreshold, -100, -120, -20);
        kalmanQ = readDouble(editKalmanQ, 0.05, 0.001, 1.0);
        kalmanR = readDouble(editKalmanR, 0.25, 0.001, 5.0);
        rssiBufferSize = readInt(editRssiBuffer, 20, 1, 100);
        rssiTimeWindowMs = readInt(editRssiWindow, 8000, 500, 30000);
        scaleFactor = readDouble(editScaleFactor, 5.0, 0.1, 50.0);
        beaconTimeoutMs = readInt(editBeaconTimeout, 6000, 1000, 30000);
        evalIntervalMs = readInt(editEvalInterval, 2000, 500, 30000);
        solverIndex = spinnerSolver.getSelectedItemPosition();
        saveBeaconConfig();
    }

    private void saveBeaconConfig() {
        requireContext().getSharedPreferences(PREFS_BEACON, 0)
                .edit()
                .putInt("debug_default_tx_power", txPower)
                .putFloat("debug_path_loss_n", (float) pathLossN)
                .putInt("debug_rssi_threshold", rssiThreshold)
                .putFloat("debug_kalman_q", (float) kalmanQ)
                .putFloat("debug_kalman_r", (float) kalmanR)
                .putInt("debug_rssi_buffer_size", rssiBufferSize)
                .putInt("debug_rssi_time_window_ms", (int) rssiTimeWindowMs)
                .putFloat("debug_scale_factor", (float) scaleFactor)
                .putInt("debug_beacon_timeout_ms", (int) beaconTimeoutMs)
                .putInt("debug_eval_interval_ms", (int) evalIntervalMs)
                .putInt("debug_solver_index", solverIndex)
                .apply();
    }

    private int readInt(EditText field, int defaultVal, int min, int max) {
        try {
            int val = Integer.parseInt(field.getText().toString().trim());
            return Math.max(min, Math.min(max, val));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private double readDouble(EditText field, double defaultVal, double min, double max) {
        try {
            double val = Double.parseDouble(field.getText().toString().trim());
            return Math.max(min, Math.min(max, val));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
