package beaconiq;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import beaconiq.positioning.phase2.P2BeaconIds;
import beaconiq.positioning.phase2.P2BeaconSample;
import beaconiq.positioning.phase2.P2ModelConfig;
import beaconiq.positioning.phase2.P2PositioningEngine;
import beaconiq.positioning.phase2.P2ProximityClassifier;
import beaconiq.scan.BleDevice;
import beaconiq.scan.BleScanner;
import beaconiq.sensor.OrientationSensor;
import beaconiq.storage.CalibrationStore;
import beaconiq.ui.BeaconCardAdapter;
import beaconiq.ui.BeaconCardItem;
import beaconiq.ui.CalibrationDialog;
import beaconiq.ui.DeviceListAdapter;
import beaconiq.ui.PositioningCanvasView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import org.altbeacon.beacon.Beacon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ScanFragment extends Fragment implements BleScanner.ScanListener {

    private static final String PREFS_BEACON = "debug_panel";
    private static final long STALE_CHECK_INTERVAL_MS = 1000;
    private static final long STALE_DEVICE_AGE_MS = 10_000;

    private P2ModelConfig config = new P2ModelConfig();

    private TextView statusText;
    private Button scanButton;
    private BleScanner bleScanner;

    private View headerParams, contentParams, headerDevices, contentDevices;
    private TextView arrowParams, arrowDevices;
    private EditText editTxPower, editPathLoss, editRssiThreshold;
    private EditText editKalmanQ, editKalmanR, editRssiBuffer, editRssiWindow;
    private EditText editScaleFactor, editBeaconTimeout, editEvalInterval;
    private EditText editWclG, editHysteresis, editDwell, editConfidence, editCooldown, editMinSamples;
    private Button btnModeProximity, btnModeTrilateration;
    private View trilaterationSection, proximitySection;
    private DeviceListAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean wasScanning;

    private PositioningCanvasView exploreCanvas;
    private CalibrationStore calibrationStore;
    private OrientationSensor orientationSensor;
    private P2PositioningEngine engine;

    private final Set<String> vibratedBeaconIds = new HashSet<>();
    private Vibrator vibrator;

    private View beaconBottomSheet;
    private BottomSheetBehavior<View> bottomSheetBehavior;
    private BeaconCardAdapter beaconCardAdapter;
    private TextView beaconSheetHeader;

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
                handler.postDelayed(this, config.evalIntervalMs);
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

        editTxPower = view.findViewById(R.id.edit_tx_power);
        editPathLoss = view.findViewById(R.id.edit_path_loss);
        editRssiThreshold = view.findViewById(R.id.edit_rssi_threshold);
        editKalmanQ = view.findViewById(R.id.edit_kalman_q);
        editKalmanR = view.findViewById(R.id.edit_kalman_r);
        editRssiBuffer = view.findViewById(R.id.edit_rssi_buffer);
        editRssiWindow = view.findViewById(R.id.edit_rssi_window);
        editScaleFactor = view.findViewById(R.id.edit_scale_factor);
        editBeaconTimeout = view.findViewById(R.id.edit_beacon_timeout);
        editEvalInterval = view.findViewById(R.id.edit_eval_interval);
        editWclG = view.findViewById(R.id.edit_wcl_g);
        editHysteresis = view.findViewById(R.id.edit_hysteresis_margin);
        editDwell = view.findViewById(R.id.edit_dwell);
        editConfidence = view.findViewById(R.id.edit_confidence);
        editCooldown = view.findViewById(R.id.edit_trigger_cooldown);
        editMinSamples = view.findViewById(R.id.edit_min_samples);
        trilaterationSection = view.findViewById(R.id.trilateration_section);
        proximitySection = view.findViewById(R.id.proximity_section);

        btnModeProximity = view.findViewById(R.id.btn_mode_proximity);
        btnModeTrilateration = view.findViewById(R.id.btn_mode_trilateration);
        btnModeProximity.setOnClickListener(v -> setMode(P2ModelConfig.MODE_PROXIMITY));
        btnModeTrilateration.setOnClickListener(v -> setMode(P2ModelConfig.MODE_TRILATERATION));

        bleScanner = new BleScanner(requireContext());
        bleScanner.setListener(this);

        calibrationStore = new CalibrationStore(requireContext());
        engine = new P2PositioningEngine(calibrationStore);
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

        beaconBottomSheet = view.findViewById(R.id.beacon_bottom_sheet);
        beaconSheetHeader = view.findViewById(R.id.beacon_sheet_header);
        bottomSheetBehavior = BottomSheetBehavior.from(beaconBottomSheet);
        // Collapsed peek shows only the drag handle + "NEARBY BEACONS" header bar,
        // sitting just above the system navigation bar. Expand by dragging up to see
        // the cards. Peek + bottom padding are recomputed when window insets arrive.
        final int headerPeekPx = (int) (56 * getResources().getDisplayMetrics().density);
        bottomSheetBehavior.setPeekHeight(headerPeekPx);
        ViewCompat.setOnApplyWindowInsetsListener(beaconBottomSheet, (v, insets) -> {
            int navBar = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), navBar);
            bottomSheetBehavior.setPeekHeight(headerPeekPx + navBar);
            return insets;
        });
        bottomSheetBehavior.setHideable(false);
        beaconBottomSheet.setVisibility(View.GONE);

        RecyclerView beaconCardList = view.findViewById(R.id.beacon_card_list);
        beaconCardAdapter = new BeaconCardAdapter();
        beaconCardList.setLayoutManager(new LinearLayoutManager(requireContext()));
        beaconCardList.setAdapter(beaconCardAdapter);
        beaconCardAdapter.setOnBeaconCardTap(this::reviewBeacon);

        vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);

        exploreCanvas.setOnBeaconTapListener(this::showCalibrationDialog);

        setStatus("Idle", R.color.text_dim);
        scanButton.setOnClickListener(v -> toggleScan());

        applyModeUi();
    }

    // --- Mode toggle ---

    private void setMode(int mode) {
        if (config.mode == mode) return;
        config.mode = mode;
        saveBeaconConfig();
        applyModeUi();
    }

    private void applyModeUi() {
        boolean prox = config.mode == P2ModelConfig.MODE_PROXIMITY;
        int sel = ContextCompat.getColor(requireContext(), R.color.white);
        int unsel = ContextCompat.getColor(requireContext(), R.color.text_primary);

        btnModeProximity.setBackgroundResource(prox ? R.drawable.btn_teal : R.drawable.btn_grey);
        btnModeProximity.setTextColor(prox ? sel : unsel);
        btnModeTrilateration.setBackgroundResource(prox ? R.drawable.btn_grey : R.drawable.btn_teal);
        btnModeTrilateration.setTextColor(prox ? unsel : sel);

        if (trilaterationSection != null) trilaterationSection.setVisibility(prox ? View.GONE : View.VISIBLE);
        if (proximitySection != null) proximitySection.setVisibility(prox ? View.VISIBLE : View.GONE);

        // The radar canvas is the trilateration view only.
        boolean scanning = bleScanner != null && bleScanner.isScanning();
        exploreCanvas.setVisibility(!prox && scanning ? View.VISIBLE : View.GONE);
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
            engine.clear();
            engine.resetAutoPositionCounter();

            vibratedBeaconIds.clear();
            beaconBottomSheet.setVisibility(View.GONE);
            beaconCardAdapter.updateItems(Collections.emptyList());
        } else {
            applyAllParameters();
            bleScanner.startScan();
            scanButton.setText("Stop Scan");
            scanButton.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.surface_2)));
            scanButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_alert));
            setStatus("Scanning", R.color.status_ok);

            engine.clear();
            engine.resetAutoPositionCounter();
            exploreCanvas.clear();
            exploreCanvas.setVisibility(
                    config.mode == P2ModelConfig.MODE_TRILATERATION ? View.VISIBLE : View.GONE);
            handler.postDelayed(positionEvalRunnable, config.evalIntervalMs);

            vibratedBeaconIds.clear();
            beaconBottomSheet.setVisibility(View.VISIBLE);
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        }
    }

    private void loadPositioningParams() {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_BEACON, 0);
        config = P2ModelConfig.load(prefs);

        if (editTxPower != null) {
            editTxPower.setText(String.valueOf(config.txPower));
            editPathLoss.setText(String.format(Locale.US, "%.1f", config.pathLossN));
            editRssiThreshold.setText(String.valueOf(config.rssiThreshold));
            editKalmanQ.setText(String.format(Locale.US, "%.3f", config.kalmanQ));
            editKalmanR.setText(String.format(Locale.US, "%.3f", config.kalmanR));
            editRssiBuffer.setText(String.valueOf(config.rssiBufferSize));
            editRssiWindow.setText(String.valueOf(config.rssiTimeWindowMs));
            editScaleFactor.setText(String.format(Locale.US, "%.1f", config.scaleFactor));
            editBeaconTimeout.setText(String.valueOf(config.beaconTimeoutMs));
            editEvalInterval.setText(String.valueOf(config.evalIntervalMs));
            editWclG.setText(String.format(Locale.US, "%.1f", config.wclG));
            editHysteresis.setText(String.format(Locale.US, "%.1f", config.hysteresisMarginDb));
            editDwell.setText(String.valueOf(config.dwellMs));
            editConfidence.setText(String.format(Locale.US, "%.2f", config.confidenceThreshold));
            editCooldown.setText(String.valueOf(config.triggerCooldownMs));
            editMinSamples.setText(String.valueOf(config.minSamples));
        }
    }

    private void evaluatePosition() {
        long now = System.currentTimeMillis();
        engine.pruneStale(now, config.beaconTimeoutMs);

        // Single point where the distance Kalman filter is stepped this tick.
        engine.updateDistances(config.txPower, config.pathLossN, config.scaleFactor);

        if (config.mode == P2ModelConfig.MODE_PROXIMITY) {
            P2ProximityClassifier.ZoneResult zr = engine.classifyProximity(config, now);
            updateCalibratedKeys();
            updateBeaconCards(zr.activeZone);   // highlight the active zone card
            showProximityStatus(zr);
        } else {
            double[] position = engine.estimatePosition(config.wclG);
            String closestKey = position != null ? engine.closestTo(position) : null;
            updateCalibratedKeys();
            exploreCanvas.updateP2(engine.snapshot(), position, closestKey);
            updateBeaconCards(closestKey);
        }
    }

    private void showProximityStatus(P2ProximityClassifier.ZoneResult zr) {
        if (statusText == null) return;
        if (zr.activeZone == null) {
            setStatus("Searching…", R.color.status_warn);
            return;
        }
        String label = P2BeaconIds.extractLabel(zr.activeZone);
        int pct = (int) Math.round(zr.confidence * 100);
        setStatus("Zone " + label + " · conf " + pct + "%", R.color.status_ok);
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
        engine.clear();

        vibratedBeaconIds.clear();
        beaconBottomSheet.setVisibility(View.GONE);
        beaconCardAdapter.updateItems(Collections.emptyList());
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

            engine.clear();
            engine.resetAutoPositionCounter();
            exploreCanvas.clear();
            exploreCanvas.setVisibility(
                    config.mode == P2ModelConfig.MODE_TRILATERATION ? View.VISIBLE : View.GONE);
            handler.postDelayed(positionEvalRunnable, config.evalIntervalMs);
            beaconBottomSheet.setVisibility(View.VISIBLE);
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else {
            setStatus("Idle", R.color.text_dim);
        }
        wasScanning = false;
    }

    @Override
    public void onBeaconDiscovered(Beacon beacon, byte[] scanRecord) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (beacon.getRssi() == 127) return;

                if (adapter != null) adapter.updateBeacon(beacon);

                if (bleScanner != null && bleScanner.isScanning()) {
                    String compositeId = P2BeaconIds.buildCompositeId(beacon);
                    boolean isNew = engine.ingest(beacon,
                            config.kalmanQ, config.kalmanR,
                            config.rssiBufferSize, config.rssiTimeWindowMs);
                    if (isNew && !vibratedBeaconIds.contains(compositeId)) {
                        vibratedBeaconIds.add(compositeId);
                        vibrateNewBeacon();
                    }
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
        applyModeUi();
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
        vibratedBeaconIds.clear();
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

    // --- Calibration ---

    private void updateCalibratedKeys() {
        exploreCanvas.setCalibratedKeys(engine.calibratedKeys());
    }

    private void showCalibrationDialog(String compositeId, double currentX, double currentY) {
        if (!isAdded()) return;
        CalibrationDialog.show(requireContext(), handler, compositeId, currentX, currentY,
                config.txPower, config.pathLossN, calibrationStore, engine.beacons(),
                this::updateCalibratedKeys);
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
                editScaleFactor, editBeaconTimeout, editEvalInterval,
                editWclG, editHysteresis, editDwell, editConfidence,
                editCooldown, editMinSamples};
        for (EditText et : allFields) {
            et.setOnFocusChangeListener(paramFocusListener);
        }
    }

    private void applyAllParameters() {
        config.txPower = readInt(editTxPower, P2ModelConfig.DEF_TX_POWER,
                P2ModelConfig.MIN_TX_POWER, P2ModelConfig.MAX_TX_POWER);
        config.pathLossN = readDouble(editPathLoss, P2ModelConfig.DEF_PATH_LOSS_N,
                P2ModelConfig.MIN_PATH_LOSS_N, P2ModelConfig.MAX_PATH_LOSS_N);
        config.rssiThreshold = readInt(editRssiThreshold, P2ModelConfig.DEF_RSSI_THRESHOLD,
                P2ModelConfig.MIN_RSSI_THRESHOLD, P2ModelConfig.MAX_RSSI_THRESHOLD);
        config.kalmanQ = readDouble(editKalmanQ, P2ModelConfig.DEF_KALMAN_Q,
                P2ModelConfig.MIN_KALMAN_Q, P2ModelConfig.MAX_KALMAN_Q);
        config.kalmanR = readDouble(editKalmanR, P2ModelConfig.DEF_KALMAN_R,
                P2ModelConfig.MIN_KALMAN_R, P2ModelConfig.MAX_KALMAN_R);
        config.rssiBufferSize = readInt(editRssiBuffer, P2ModelConfig.DEF_RSSI_BUFFER_SIZE,
                P2ModelConfig.MIN_RSSI_BUFFER_SIZE, P2ModelConfig.MAX_RSSI_BUFFER_SIZE);
        config.rssiTimeWindowMs = readInt(editRssiWindow, (int) P2ModelConfig.DEF_RSSI_TIME_WINDOW_MS,
                P2ModelConfig.MIN_RSSI_TIME_WINDOW_MS, P2ModelConfig.MAX_RSSI_TIME_WINDOW_MS);
        config.scaleFactor = readDouble(editScaleFactor, P2ModelConfig.DEF_SCALE_FACTOR,
                P2ModelConfig.MIN_SCALE_FACTOR, P2ModelConfig.MAX_SCALE_FACTOR);
        config.beaconTimeoutMs = readInt(editBeaconTimeout, (int) P2ModelConfig.DEF_BEACON_TIMEOUT_MS,
                P2ModelConfig.MIN_BEACON_TIMEOUT_MS, P2ModelConfig.MAX_BEACON_TIMEOUT_MS);
        config.evalIntervalMs = readInt(editEvalInterval, (int) P2ModelConfig.DEF_EVAL_INTERVAL_MS,
                P2ModelConfig.MIN_EVAL_INTERVAL_MS, P2ModelConfig.MAX_EVAL_INTERVAL_MS);
        config.wclG = readDouble(editWclG, P2ModelConfig.DEF_WCL_G,
                P2ModelConfig.MIN_WCL_G, P2ModelConfig.MAX_WCL_G);
        config.hysteresisMarginDb = readDouble(editHysteresis, P2ModelConfig.DEF_HYSTERESIS_MARGIN_DB,
                P2ModelConfig.MIN_HYSTERESIS_MARGIN_DB, P2ModelConfig.MAX_HYSTERESIS_MARGIN_DB);
        config.dwellMs = readInt(editDwell, (int) P2ModelConfig.DEF_DWELL_MS,
                P2ModelConfig.MIN_DWELL_MS, P2ModelConfig.MAX_DWELL_MS);
        config.confidenceThreshold = readDouble(editConfidence, P2ModelConfig.DEF_CONFIDENCE_THRESHOLD,
                P2ModelConfig.MIN_CONFIDENCE_THRESHOLD, P2ModelConfig.MAX_CONFIDENCE_THRESHOLD);
        config.triggerCooldownMs = readInt(editCooldown, (int) P2ModelConfig.DEF_TRIGGER_COOLDOWN_MS,
                P2ModelConfig.MIN_TRIGGER_COOLDOWN_MS, P2ModelConfig.MAX_TRIGGER_COOLDOWN_MS);
        config.minSamples = readInt(editMinSamples, P2ModelConfig.DEF_MIN_SAMPLES,
                P2ModelConfig.MIN_MIN_SAMPLES, P2ModelConfig.MAX_MIN_SAMPLES);
        saveBeaconConfig();
    }

    private void saveBeaconConfig() {
        config.save(requireContext().getSharedPreferences(PREFS_BEACON, 0));
    }

    private int readInt(EditText field, int defaultVal, int min, int max) {
        return P2ModelConfig.clampInt(field.getText().toString(), defaultVal, min, max);
    }

    private double readDouble(EditText field, double defaultVal, double min, double max) {
        return P2ModelConfig.clampDouble(field.getText().toString(), defaultVal, min, max);
    }

    // --- Beacon cards bottom sheet ---

    private void updateBeaconCards(String closestKey) {
        List<BeaconCardItem> cards = new ArrayList<>();
        for (Map.Entry<String, P2BeaconSample> entry : engine.beacons().entrySet()) {
            P2BeaconSample sample = entry.getValue();
            String compositeId = entry.getKey();
            // Non-mutating read of the model distance (filter advanced during eval).
            Double dist = sample.getLastFilteredDistance();
            if (dist == null) continue;
            String label = P2BeaconIds.extractLabel(compositeId);
            boolean isClosest = compositeId.equals(closestKey);
            cards.add(new BeaconCardItem(compositeId, label,
                    sample.getLastRawRssi(), dist, isClosest));
        }
        Collections.sort(cards);
        beaconCardAdapter.updateItems(cards);
        if (beaconSheetHeader != null) {
            beaconSheetHeader.setText("NEARBY BEACONS (" + cards.size() + ")");
        }
    }

    /**
     * Tapping a card is the end-user "content for this point" action (marketing
     * function, owned by another team). This model app only shows a placeholder;
     * the real title/description/media for point X is provided elsewhere.
     */
    private void reviewBeacon(String compositeId) {
        if (!isAdded()) return;
        String label = P2BeaconIds.extractLabel(compositeId);
        P2BeaconSample sample = engine.beacons().get(compositeId);

        StringBuilder msg = new StringBuilder();
        msg.append("You've reached this point.\n\n");
        if (sample != null) {
            Double dist = sample.getLastFilteredDistance();
            msg.append("Signal: ").append(sample.getLastRawRssi()).append(" dBm");
            if (dist != null) {
                msg.append("   ·   ~").append(String.format(Locale.US, "%.1f m", dist));
            }
            msg.append("\n\n");
        }
        msg.append("This point's content — title, description and media — ")
           .append("will be shown here (provided separately).");

        new AlertDialog.Builder(requireContext())
                .setTitle("You're at Beacon " + label)
                .setMessage(msg.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    // --- Vibration ---

    private void vibrateNewBeacon() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.vibrate(VibrationEffect.createWaveform(
                new long[]{0, 50, 100, 50}, -1));
    }

}
