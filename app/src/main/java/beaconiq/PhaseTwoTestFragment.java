package beaconiq;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import beaconiq.network.TestConsoleApi;
import beaconiq.positioning.phase2.P2BeaconIds;
import beaconiq.positioning.phase2.P2BeaconSample;
import beaconiq.positioning.phase2.P2ModelConfig;
import beaconiq.positioning.phase2.P2PositioningEngine;
import beaconiq.positioning.phase2.P2ProximityClassifier;
import beaconiq.scan.BleDevice;
import beaconiq.scan.BleScanner;
import beaconiq.sensor.OrientationSensor;
import beaconiq.storage.CalibrationStore;
import beaconiq.ui.CalibrationDialog;
import beaconiq.ui.PositioningCanvasView;

import org.altbeacon.beacon.Beacon;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class PhaseTwoTestFragment extends Fragment implements BleScanner.ScanListener {

    private static final String TAG = "BeaconIQ.TestConsole";
    private static final String PREFS_BEACON = "debug_panel";
    private static final String PREFS_TEST_CONSOLE = "test_console";

    private static final String[] MOVEMENT_MODES =
            {"standing", "walking_slowly", "walking_normally", "running"};
    private static final String[] PHONE_POSITIONS =
            {"hand_at_side", "hand_chest_height", "pocket", "table"};

    // Defaults sourced from P2ModelConfig (single source of truth).
    private static final long DEFAULT_MODEL_EVAL_INTERVAL_MS = P2ModelConfig.DEF_EVAL_INTERVAL_MS;
    private static final long DEFAULT_BEACON_TIMEOUT_MS = P2ModelConfig.DEF_BEACON_TIMEOUT_MS;
    private static final double DEFAULT_SCALE_FACTOR = P2ModelConfig.DEF_SCALE_FACTOR;
    private static final int MIN_BEACONS_REQUIRED = 3;

    private static final double DEFAULT_KALMAN_Q = P2ModelConfig.DEF_KALMAN_Q;
    private static final double DEFAULT_KALMAN_R = P2ModelConfig.DEF_KALMAN_R;
    private static final int DEFAULT_RSSI_BUFFER_SIZE = P2ModelConfig.DEF_RSSI_BUFFER_SIZE;
    private static final long DEFAULT_RSSI_TIME_WINDOW_MS = P2ModelConfig.DEF_RSSI_TIME_WINDOW_MS;

    // --- UI fields ---

    private EditText editAnalyst, editDuration, editRoom, editNotes;
    private EditText editTxPower, editPathLoss, editRssiThreshold;
    private EditText editKalmanQ, editKalmanR, editRssiBuffer, editRssiWindow;
    private EditText editScaleFactor, editBeaconTimeout, editEvalInterval;
    private EditText editPosKalmanQ, editPosKalmanR, editHysteresis, editDwell, editConfidence, editTriggerCooldown, editMinSamples;
    private Spinner spinnerMovement, spinnerPhonePosition;
    private ChipGroup groupGroundTruth;
    // Clean zone values ("major,minor") parallel to the ground-truth spinner items.
    private final List<String> gtValues = new ArrayList<>();
    private View modelParamsSection;

    // --- Model parameters ---

    private P2ModelConfig config = new P2ModelConfig();
    private int selectedDurationSec = 60;

    // --- Session state ---

    private View formSection;
    private Button btnStartSession;

    private View modelStatusPanel;
    private TextView tvClosest, tvState, tvBeaconCount;
    private TextView tvKalmanStatus, tvSolverStatus, tvEngineStatus;
    private PositioningCanvasView positioningCanvas;

    private BleScanner bleScanner;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private int remainingSec;
    private boolean isRecording;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;
            remainingSec--;
            btnStartSession.setText(
                    "Recording... " + remainingSec + "s (" + ibeaconHits.get() + " readings)");
            if (remainingSec <= 0) {
                endSession();
            } else {
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    private final List<Map<String, Object>> ibeaconReadings =
            Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> rawScans =
            Collections.synchronizedList(new ArrayList<>());
    private final Set<String> uniqueBeaconIds = new HashSet<>();
    // Atomic: incremented from the binder-thread generic scan callback as well
    // as the main-thread range notifier.
    private final AtomicInteger totalScanResults = new AtomicInteger();
    private final AtomicInteger ibeaconHits = new AtomicInteger();
    private final AtomicInteger rejectedCount = new AtomicInteger();
    private long sessionStartMs;
    private long sessionEndMs;

    private Button btnTabNewSession, btnTabHistory;
    private View sessionFormContainer, sessionHistoryContainer;
    private LinearLayout historyList;
    private TextView historyStatus;
    private boolean historyLoaded;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private CalibrationStore calibrationStore;
    private P2PositioningEngine engine;
    private String closestBeaconUid;
    private String modelState = "SEARCHING";
    private double[] estimatedPosition;
    private P2ProximityClassifier.ZoneResult lastZone;

    private OrientationSensor orientationSensor;

    private Button btnCalibrate;
    private TextView tvCalibrationStatus;

    private final Handler modelHandler = new Handler(Looper.getMainLooper());

    // --- P2-specific state ---

    private boolean isCalibrationActive;

    // --- Runnables ---

    private final Runnable modelEvalRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;
            runModelEvaluation();
            modelHandler.postDelayed(this, config.evalIntervalMs);
        }
    };

    private final Runnable calibrationEvalRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isCalibrationActive) return;
            engine.pruneStale(System.currentTimeMillis(), config.beaconTimeoutMs);

            engine.updateDistances(config.txPower, config.pathLossN, config.scaleFactor);
            double[] position = engine.estimatePosition();
            String closestKey = position != null ? engine.closestTo(position) : null;

            updateCalibratedKeys();
            updateCalibrationStatusLine();
            positioningCanvas.updateP2(engine.snapshot(), position, closestKey);
            modelHandler.postDelayed(this, config.evalIntervalMs);
        }
    };

    // --- Lifecycle ---

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_test, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        calibrationStore = new CalibrationStore(requireContext());
        engine = new P2PositioningEngine(calibrationStore);
        initViews(view);
        loadSessionFormPrefs();
        setupListeners(view);
        initToggle(view);

        applyP2Config();

        bleScanner = new BleScanner(requireContext());
        bleScanner.setListener(this);

        orientationSensor = new OrientationSensor();
        orientationSensor.setListener((azimuth, pitch, roll) -> {
            if (positioningCanvas != null) {
                positioningCanvas.updateOrientation(azimuth, pitch, roll);
            }
        });

        btnStartSession.setEnabled(
                editAnalyst.getText().toString().trim().length() > 0);

        positioningCanvas.setOnBeaconTapListener((compositeId, currentX, currentY) -> {
            if (isCalibrationActive) {
                showCalibrationDialog(compositeId, currentX, currentY);
            }
        });

        btnCalibrate.setOnClickListener(v -> toggleCalibrationMode());
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isCalibrationActive) {
            stopCalibrationMode();
        }
        if (isRecording) {
            endSession();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        modelHandler.removeCallbacks(calibrationEvalRunnable);
        timerHandler.removeCallbacks(timerRunnable);
        modelHandler.removeCallbacks(modelEvalRunnable);
        if (orientationSensor != null) {
            orientationSensor.stop();
        }
        if (bleScanner != null && bleScanner.isScanning()) {
            bleScanner.stopScan();
        }
        executor.shutdown();
    }

    // --- UI init ---

    private void initViews(View view) {
        formSection = view.findViewById(R.id.form_section);

        editAnalyst = view.findViewById(R.id.edit_analyst);
        editDuration = view.findViewById(R.id.edit_duration);
        spinnerMovement = view.findViewById(R.id.spinner_movement);
        editRoom = view.findViewById(R.id.edit_room);
        spinnerPhonePosition = view.findViewById(R.id.spinner_phone_position);
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
        editPosKalmanQ = view.findViewById(R.id.edit_pos_kalman_q);
        editPosKalmanR = view.findViewById(R.id.edit_pos_kalman_r);
        editHysteresis = view.findViewById(R.id.edit_hysteresis_margin);
        editDwell = view.findViewById(R.id.edit_dwell);
        editConfidence = view.findViewById(R.id.edit_confidence);
        editTriggerCooldown = view.findViewById(R.id.edit_trigger_cooldown);
        editMinSamples = view.findViewById(R.id.edit_min_samples);
        groupGroundTruth = view.findViewById(R.id.group_ground_truth);
        editNotes = view.findViewById(R.id.edit_notes);
        btnStartSession = view.findViewById(R.id.btn_start_session);

        modelParamsSection = view.findViewById(R.id.model_params_section);

        ArrayAdapter<String> movementAdapter = new ArrayAdapter<>(requireContext(),
                R.layout.spinner_item, MOVEMENT_MODES);
        movementAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerMovement.setAdapter(movementAdapter);

        ArrayAdapter<String> positionAdapter = new ArrayAdapter<>(requireContext(),
                R.layout.spinner_item, PHONE_POSITIONS);
        positionAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerPhonePosition.setAdapter(positionAdapter);

        modelStatusPanel = view.findViewById(R.id.model_status_panel);
        tvClosest = view.findViewById(R.id.tv_closest);
        tvState = view.findViewById(R.id.tv_state);
        tvBeaconCount = view.findViewById(R.id.tv_beacon_count);
        tvKalmanStatus = view.findViewById(R.id.tv_kalman_status);
        tvSolverStatus = view.findViewById(R.id.tv_solver_status);
        tvEngineStatus = view.findViewById(R.id.tv_engine_status);
        tvCalibrationStatus = view.findViewById(R.id.tv_calibration_status);
        positioningCanvas = view.findViewById(R.id.positioning_canvas);
        btnCalibrate = view.findViewById(R.id.btn_calibrate);
    }

    private void loadSessionFormPrefs() {
        SharedPreferences tcPrefs = requireContext()
                .getSharedPreferences(PREFS_TEST_CONSOLE, Context.MODE_PRIVATE);
        editAnalyst.setText(tcPrefs.getString("analyst_name", ""));
        editRoom.setText(tcPrefs.getString("room_name", ""));
        editDuration.setText(String.valueOf(selectedDurationSec));
    }

    private void setupListeners(View view) {
        editAnalyst.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!isRecording) {
                    btnStartSession.setEnabled(s.toString().trim().length() > 0);
                }
                requireContext().getSharedPreferences(PREFS_TEST_CONSOLE, Context.MODE_PRIVATE)
                        .edit().putString("analyst_name", s.toString().trim()).apply();
            }
        });

        editRoom.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                requireContext().getSharedPreferences(PREFS_TEST_CONSOLE, Context.MODE_PRIVATE)
                        .edit().putString("room_name", s.toString().trim()).apply();
            }
        });

        view.findViewById(R.id.btn_reset_defaults).setOnClickListener(v -> {
            editTxPower.setText("-59");
            editPathLoss.setText("2.0");
            editRssiThreshold.setText("-100");
            editDuration.setText("60");
            editKalmanQ.setText("0.050");
            editKalmanR.setText("0.250");
            editRssiBuffer.setText("20");
            editRssiWindow.setText("8000");
            editScaleFactor.setText("1.0");
            editBeaconTimeout.setText("4000");
            editEvalInterval.setText("3000");
            editPosKalmanQ.setText("0.050");
            editPosKalmanR.setText("0.500");
            editHysteresis.setText("6.0");
            editDwell.setText("1500");
            editConfidence.setText("0.40");
            editTriggerCooldown.setText("8000");
            editMinSamples.setText("3");
        });

        btnStartSession.setOnClickListener(v -> {
            if (isRecording) {
                endSession();
            } else {
                startSession();
            }
        });
    }

    private void initToggle(View view) {
        btnTabNewSession = view.findViewById(R.id.btn_tab_new_session);
        btnTabHistory = view.findViewById(R.id.btn_tab_history);
        sessionFormContainer = view.findViewById(R.id.session_form_container);
        sessionHistoryContainer = view.findViewById(R.id.session_history_container);
        historyList = view.findViewById(R.id.history_list);
        historyStatus = view.findViewById(R.id.history_status);

        btnTabNewSession.setOnClickListener(v -> setTab(true));
        btnTabHistory.setOnClickListener(v -> setTab(false));

        view.findViewById(R.id.history_open_console).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(TestConsoleApi.ENDPOINT_URL.replace("/exec", "")));
            startActivity(intent);
        });
    }

    private void setTab(boolean newSession) {
        if (isRecording) return;

        sessionFormContainer.setVisibility(newSession ? View.VISIBLE : View.GONE);
        sessionHistoryContainer.setVisibility(newSession ? View.GONE : View.VISIBLE);

        int white = ContextCompat.getColor(requireContext(), R.color.white);
        int textPrimary = ContextCompat.getColor(requireContext(), R.color.text_primary);

        btnTabNewSession.setBackgroundResource(newSession ? R.drawable.btn_teal : R.drawable.btn_grey);
        btnTabNewSession.setTextColor(newSession ? white : textPrimary);
        btnTabNewSession.setTypeface(null, newSession ? Typeface.BOLD : Typeface.NORMAL);

        btnTabHistory.setBackgroundResource(newSession ? R.drawable.btn_grey : R.drawable.btn_teal);
        btnTabHistory.setTextColor(newSession ? textPrimary : white);
        btnTabHistory.setTypeface(null, newSession ? Typeface.NORMAL : Typeface.BOLD);

        if (!newSession && !historyLoaded) {
            loadHistory();
        }
    }

    // --- P2 config ---

    private void applyP2Config() {
        loadModelPreferences();

        modelParamsSection.setVisibility(View.VISIBLE);

        tvKalmanStatus.setText("Kalman: ON (q=" + config.kalmanQ + ", r=" + config.kalmanR + ")");
        tvSolverStatus.setText("Solver: LSQ");
        tvEngineStatus.setText("Engine: direct solver");
        tvCalibrationStatus.setText("Calibrated: 0/0");
        btnCalibrate.setVisibility(View.VISIBLE);
    }

    private void loadModelPreferences() {
        SharedPreferences beaconPrefs = requireContext()
                .getSharedPreferences(PREFS_BEACON, Context.MODE_PRIVATE);

        config = P2ModelConfig.load(beaconPrefs);

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
        editPosKalmanQ.setText(String.format(Locale.US, "%.3f", config.posKalmanQ));
        editPosKalmanR.setText(String.format(Locale.US, "%.3f", config.posKalmanR));
        editHysteresis.setText(String.format(Locale.US, "%.1f", config.hysteresisMarginDb));
        editDwell.setText(String.valueOf(config.dwellMs));
        editConfidence.setText(String.format(Locale.US, "%.2f", config.confidenceThreshold));
        editTriggerCooldown.setText(String.valueOf(config.triggerCooldownMs));
        editMinSamples.setText(String.valueOf(config.minSamples));
    }

    private void saveBeaconConfig() {
        config.save(requireContext().getSharedPreferences(PREFS_BEACON, Context.MODE_PRIVATE));
    }

    private void readSessionParams() {
        config.txPower = readInt(editTxPower, -59, -100, 0);
        config.pathLossN = readDouble(editPathLoss, 2.0, 1.0, 6.0);
        config.rssiThreshold = readInt(editRssiThreshold, -100, -120, -20);
        config.kalmanQ = readDouble(editKalmanQ, DEFAULT_KALMAN_Q, 0.001, 1.0);
        config.kalmanR = readDouble(editKalmanR, DEFAULT_KALMAN_R, 0.001, 5.0);
        config.rssiBufferSize = readInt(editRssiBuffer, DEFAULT_RSSI_BUFFER_SIZE, 1, 100);
        config.rssiTimeWindowMs = readInt(editRssiWindow, (int) DEFAULT_RSSI_TIME_WINDOW_MS, 500, 30000);
        config.scaleFactor = readDouble(editScaleFactor, DEFAULT_SCALE_FACTOR, 0.1, 50.0);
        config.beaconTimeoutMs = readInt(editBeaconTimeout, (int) DEFAULT_BEACON_TIMEOUT_MS, 1000, 30000);
        config.evalIntervalMs = readInt(editEvalInterval, (int) DEFAULT_MODEL_EVAL_INTERVAL_MS, 500, 30000);
        config.posKalmanQ = readDouble(editPosKalmanQ, P2ModelConfig.DEF_POS_KALMAN_Q,
                P2ModelConfig.MIN_POS_KALMAN_Q, P2ModelConfig.MAX_POS_KALMAN_Q);
        config.posKalmanR = readDouble(editPosKalmanR, P2ModelConfig.DEF_POS_KALMAN_R,
                P2ModelConfig.MIN_POS_KALMAN_R, P2ModelConfig.MAX_POS_KALMAN_R);
        config.hysteresisMarginDb = readDouble(editHysteresis, P2ModelConfig.DEF_HYSTERESIS_MARGIN_DB,
                P2ModelConfig.MIN_HYSTERESIS_MARGIN_DB, P2ModelConfig.MAX_HYSTERESIS_MARGIN_DB);
        config.dwellMs = readInt(editDwell, (int) P2ModelConfig.DEF_DWELL_MS,
                P2ModelConfig.MIN_DWELL_MS, P2ModelConfig.MAX_DWELL_MS);
        config.confidenceThreshold = readDouble(editConfidence, P2ModelConfig.DEF_CONFIDENCE_THRESHOLD,
                P2ModelConfig.MIN_CONFIDENCE_THRESHOLD, P2ModelConfig.MAX_CONFIDENCE_THRESHOLD);
        config.triggerCooldownMs = readInt(editTriggerCooldown, (int) P2ModelConfig.DEF_TRIGGER_COOLDOWN_MS,
                P2ModelConfig.MIN_TRIGGER_COOLDOWN_MS, P2ModelConfig.MAX_TRIGGER_COOLDOWN_MS);
        config.minSamples = readInt(editMinSamples, P2ModelConfig.DEF_MIN_SAMPLES,
                P2ModelConfig.MIN_MIN_SAMPLES, P2ModelConfig.MAX_MIN_SAMPLES);
    }

    private int readInt(EditText field, int defaultVal, int min, int max) {
        return P2ModelConfig.clampInt(field.getText().toString(), defaultVal, min, max);
    }

    private double readDouble(EditText field, double defaultVal, double min, double max) {
        return P2ModelConfig.clampDouble(field.getText().toString(), defaultVal, min, max);
    }

    // --- History ---

    private void loadHistory() {
        historyStatus.setText("Loading...");
        historyStatus.setVisibility(View.VISIBLE);
        historyList.removeAllViews();

        executor.execute(() -> {
            try {
                String response = TestConsoleApi.listSessions();
                JSONObject json = new JSONObject(response);
                JSONArray sessions = json.optJSONArray("sessions");

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        historyList.removeAllViews();
                        if (sessions == null || sessions.length() == 0) {
                            historyStatus.setText("No sessions yet");
                        } else {
                            historyStatus.setVisibility(View.GONE);
                            for (int i = 0; i < sessions.length(); i++) {
                                try {
                                    addHistoryRow(sessions.getJSONObject(i));
                                } catch (JSONException ignored) {
                                }
                            }
                            historyLoaded = true;
                        }
                    });
                }
            } catch (IOException | JSONException e) {
                Log.e(TAG, "loadHistory failed", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            historyStatus.setText("Failed to load: " + e.getMessage()));
                }
            }
        });
    }

    private void addHistoryRow(JSONObject session) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.card_background);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad, pad, pad);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
        row.setLayoutParams(lp);

        String sid = session.optString("session_id", "—");
        String date = session.optString("date", "");
        String room = session.optString("room", "");
        int duration = session.optInt("duration_sec", 0);
        int readings = session.optInt("ibeacon_hits", 0);

        TextView titleTv = new TextView(requireContext());
        titleTv.setText(sid + "  " + date);
        titleTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
        titleTv.setTextSize(14);
        row.addView(titleTv);

        TextView detailTv = new TextView(requireContext());
        detailTv.setText(room + "  ·  " + duration + "s  ·  " + readings + " readings");
        detailTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted));
        detailTv.setTextSize(13);
        row.addView(detailTv);

        historyList.addView(row);
    }

    // --- Session recording ---

    @SuppressLint("MissingPermission")
    private void startSession() {
        if (isCalibrationActive) {
            stopCalibrationMode();
        }
        String analyst = editAnalyst.getText().toString().trim();
        if (analyst.isEmpty()) return;

        selectedDurationSec = readInt(editDuration, 60, 5, 600);
        readSessionParams();
        saveBeaconConfig();
        ((MainActivity) requireActivity()).stopScannerForRecording();

        isRecording = true;
        sessionStartMs = System.currentTimeMillis();

        ibeaconReadings.clear();
        rawScans.clear();
        uniqueBeaconIds.clear();
        totalScanResults.set(0);
        ibeaconHits.set(0);
        rejectedCount.set(0);

        engine.resetAutoPositionCounter();
        closestBeaconUid = null;
        modelState = "SEARCHING";
        estimatedPosition = null;
        lastZone = null;

        engine.clear();
        engine.setPositionKalman(config.posKalmanQ, config.posKalmanR);
        btnCalibrate.setVisibility(View.GONE);

        formSection.setVisibility(View.GONE);
        positioningCanvas.clear();

        updateModelStatusPanel(engine.countActive());

        remainingSec = selectedDurationSec;
        btnStartSession.setText(
                "Recording... " + remainingSec + "s (0 readings)");

        bleScanner.startScan();
        timerHandler.postDelayed(timerRunnable, 1000);
        modelHandler.postDelayed(modelEvalRunnable, config.evalIntervalMs);

        if (orientationSensor != null) {
            orientationSensor.start(requireActivity());
        }

        Log.d(TAG, "Session started: phase=phase_2"
                + " duration=" + selectedDurationSec + "s");
    }

    private void endSession() {
        if (!isRecording) return;
        isRecording = false;
        sessionEndMs = System.currentTimeMillis();

        timerHandler.removeCallbacks(timerRunnable);
        modelHandler.removeCallbacks(modelEvalRunnable);
        bleScanner.stopScan();
        if (orientationSensor != null) {
            orientationSensor.stop();
        }

        ((MainActivity) requireActivity()).resumeScannerAfterRecording();

        Log.d(TAG, "Session ended: ibeacon=" + ibeaconHits.get()
                + " rejected=" + rejectedCount.get() + " total=" + totalScanResults.get());

        uploadSession();
    }

    private void uploadSession() {
        btnStartSession.setText("Uploading...");
        btnStartSession.setEnabled(false);

        try {
            JSONObject payload = buildPayload();

            executor.execute(() -> {
                try {
                    TestConsoleApi.postSession(payload);

                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            resetForm();
                            historyLoaded = false;
                            setTab(false);
                        });
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Upload failed", e);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            btnStartSession.setText("Upload failed");
                            timerHandler.postDelayed(() -> resetForm(), 3000);
                        });
                    }
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build payload", e);
            resetForm();
        }
    }

    private JSONObject buildPayload() throws JSONException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        JSONObject session = new JSONObject();
        session.put("timestamp_start", sdf.format(new Date(sessionStartMs)));
        session.put("timestamp_end", sdf.format(new Date(sessionEndMs)));
        session.put("analyst", editAnalyst.getText().toString().trim());
        session.put("duration_sec", selectedDurationSec);
        session.put("movement_mode", MOVEMENT_MODES[spinnerMovement.getSelectedItemPosition()]);
        session.put("room", editRoom.getText().toString().trim());
        session.put("phone_position",
                PHONE_POSITIONS[spinnerPhonePosition.getSelectedItemPosition()]);
        session.put("phone_model", Build.MODEL);
        session.put("android_version", Build.VERSION.RELEASE);
        session.put("txpower", config.txPower);
        session.put("path_loss_n", config.pathLossN);
        session.put("rssi_threshold", config.rssiThreshold);
        session.put("beacons_detected", uniqueBeaconIds.size());
        session.put("total_scan_results", totalScanResults.get());
        session.put("ibeacon_hits", ibeaconHits.get());
        session.put("rejected_count", rejectedCount.get());
        session.put("model_phase", "phase_2");
        session.put("kalman_q", config.kalmanQ);
        session.put("kalman_r", config.kalmanR);
        session.put("rssi_buffer_size", config.rssiBufferSize);
        session.put("rssi_time_window_ms", config.rssiTimeWindowMs);
        session.put("scale_factor", config.scaleFactor);
        session.put("beacon_timeout_ms", config.beaconTimeoutMs);
        session.put("eval_interval_ms", config.evalIntervalMs);
        session.put("solver_type", "trilateration_lsq");
        session.put("mode", config.mode == P2ModelConfig.MODE_PROXIMITY ? "proximity" : "trilateration");
        session.put("pos_kalman_q", config.posKalmanQ);
        session.put("pos_kalman_r", config.posKalmanR);
        session.put("hysteresis_margin_db", config.hysteresisMarginDb);
        session.put("dwell_ms", config.dwellMs);
        session.put("confidence_threshold", config.confidenceThreshold);
        session.put("trigger_cooldown_ms", config.triggerCooldownMs);
        session.put("min_samples", config.minSamples);
        JSONArray beaconConfig = new JSONArray();
        for (beaconiq.model.Beacon b : calibrationStore.getAllBeacons()) {
            JSONObject bc = new JSONObject();
            bc.put("id", b.getMajor() + "," + b.getMinor());
            bc.put("x", b.getX());
            bc.put("y", b.getY());
            bc.put("tx", b.getTxPower());
            bc.put("n", b.getPathLossN());
            beaconConfig.put(bc);
        }
        session.put("beacon_config", beaconConfig.toString());
        session.put("notes", editNotes.getText().toString().trim());

        JSONArray readingsArray = new JSONArray();
        synchronized (ibeaconReadings) {
            for (Map<String, Object> r : ibeaconReadings) {
                readingsArray.put(new JSONObject(r));
            }
        }

        JSONArray rawArray = new JSONArray();
        synchronized (rawScans) {
            for (Map<String, Object> r : rawScans) {
                rawArray.put(new JSONObject(r));
            }
        }

        JSONObject payload = new JSONObject();
        payload.put("auth", TestConsoleApi.AUTH_TOKEN);
        payload.put("action", "record_session");
        payload.put("session", session);
        payload.put("readings", readingsArray);
        payload.put("raw_scans", rawArray);

        return payload;
    }

    private void resetForm() {
        formSection.setVisibility(View.VISIBLE);
        positioningCanvas.clear();

        btnStartSession.setText("Start Session");
        btnStartSession.setEnabled(
                editAnalyst.getText().toString().trim().length() > 0);

        ibeaconReadings.clear();
        rawScans.clear();
        uniqueBeaconIds.clear();
        totalScanResults.set(0);
        ibeaconHits.set(0);
        rejectedCount.set(0);

        closestBeaconUid = null;
        modelState = "SEARCHING";
        estimatedPosition = null;
        lastZone = null;

        engine.clear();
        btnCalibrate.setVisibility(View.VISIBLE);
    }

    // --- Status panel ---

    private void updateModelStatusPanel(int active) {
        String uid = closestBeaconUid != null ? P2BeaconIds.shortUid(closestBeaconUid) : "---";

        tvClosest.setText("Closest: " + uid);
        tvState.setText("State: " + modelState);
        tvBeaconCount.setText("Beacons: " + active + " / 3 required");

        tvKalmanStatus.setText("Kalman: ON (q=" + config.kalmanQ + ", r=" + config.kalmanR + ")");
        tvSolverStatus.setText("Solver: LSQ");
        if (lastZone != null && lastZone.activeZone != null) {
            tvEngineStatus.setText("Zone: " + P2BeaconIds.extractLabel(lastZone.activeZone)
                    + " (conf " + (int) Math.round(lastZone.confidence * 100) + "%)");
        } else {
            tvEngineStatus.setText("Zone: searching");
        }
    }

    // --- Positioning ---

    // --- BLE callbacks ---

    @Override
    public void onBeaconDiscovered(Beacon beacon, byte[] scanRecord) {
        if (isCalibrationActive) {
            onBeaconDuringCalibration(beacon);
            return;
        }
        if (!isRecording) return;

        totalScanResults.incrementAndGet();

        if (beacon.getRssi() == 127) return;

        String compositeId = P2BeaconIds.buildCompositeId(beacon);
        uniqueBeaconIds.add(compositeId);

        if (beacon.getRssi() < config.rssiThreshold) {
            rejectedCount.incrementAndGet();
            return;
        }

        ibeaconHits.incrementAndGet();

        double rawDistance = Math.pow(10.0, (config.txPower - beacon.getRssi()) / (10.0 * config.pathLossN));
        Double filteredRssi = processP2Beacon(beacon, compositeId);
        P2BeaconSample sample = engine.beacons().get(compositeId);
        Double modelDistance = sample != null ? sample.getLastFilteredDistance() : null;

        long now = System.currentTimeMillis();
        int major = beacon.getIdentifiers().size() >= 2 ? beacon.getId2().toInt() : 0;
        int minor = beacon.getIdentifiers().size() >= 3 ? beacon.getId3().toInt() : 0;

        Map<String, Object> reading = new HashMap<>();
        reading.put("timestamp_ms", now);
        reading.put("beacon_id", compositeId);
        reading.put("uuid", beacon.getId1().toString());
        reading.put("major", major);
        reading.put("minor", minor);
        reading.put("rssi_raw", beacon.getRssi());
        reading.put("rssi_filtered", filteredRssi != null ? Math.round(filteredRssi * 100.0) / 100.0 : "");
        reading.put("tx_power_adv", beacon.getTxPower());
        reading.put("distance_m",
                modelDistance != null ? Math.round(modelDistance * 100.0) / 100.0 : "");
        reading.put("dist_no_kalman", Math.round(rawDistance * 100.0) / 100.0);
        // est_x/est_y are the last position solved by the model tick (every
        // evalIntervalMs), not recomputed per reading — so consecutive readings
        // within a tick share the same estimate (or blank while SEARCHING).
        double[] pos = estimatedPosition;
        reading.put("est_x", pos != null ? Math.round(pos[0] * 100.0) / 100.0 : "");
        reading.put("est_y", pos != null ? Math.round(pos[1] * 100.0) / 100.0 : "");
        reading.put("model_phase", "phase_2");
        // Proximity-model outputs (logged in parallel with the LSQ estimate).
        P2ProximityClassifier.ZoneResult z = lastZone;
        reading.put("active_zone",
                z != null && z.activeZone != null ? P2BeaconIds.extractLabel(z.activeZone) : "");
        reading.put("candidate_zone",
                z != null && z.candidateZone != null ? P2BeaconIds.extractLabel(z.candidateZone) : "");
        reading.put("confidence", z != null ? Math.round(z.confidence * 100.0) / 100.0 : "");
        reading.put("margin_db", z != null ? Math.round(z.marginDb * 100.0) / 100.0 : "");
        reading.put("ground_truth_zone", selectedGroundTruth());
        ibeaconReadings.add(reading);

        Map<String, Object> raw = new HashMap<>();
        raw.put("timestamp_ms", now);
        raw.put("device_address", beacon.getBluetoothAddress());
        raw.put("company_id", String.format(Locale.US, "0x%04X", beacon.getManufacturer()));
        raw.put("rssi", beacon.getRssi());
        raw.put("was_ibeacon", true);
        raw.put("data_hex", bytesToHex(scanRecord));
        rawScans.add(raw);
    }

    @Override
    public void onGenericDeviceDiscovered(BleDevice device) {
        if (!isRecording) return;

        totalScanResults.incrementAndGet();
        rejectedCount.incrementAndGet();

        Map<String, Object> raw = new HashMap<>();
        raw.put("timestamp_ms", device.getLastSeenMs());
        raw.put("device_address", device.getMacAddress());
        raw.put("rssi", device.getRssi());
        raw.put("was_ibeacon", false);
        raw.put("data_hex", bytesToHex(device.getScanRecord()));
        raw.put("reject_reason", "not_ibeacon");
        rawScans.add(raw);
    }

    @Override
    public void onScanFailed(int errorCode) {
        Log.e(TAG, "Scan failed: " + errorCode);
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (isRecording) {
                    endSession();
                }
            });
        }
    }

    // --- P2 model evaluation ---

    private void runModelEvaluation() {
        long now = System.currentTimeMillis();
        engine.pruneStale(now, config.beaconTimeoutMs);

        // Single point where the distance Kalman filter is stepped this tick.
        int active = engine.updateDistances(config.txPower, config.pathLossN, config.scaleFactor);
        if (active < MIN_BEACONS_REQUIRED) {
            modelState = "SEARCHING";
            closestBeaconUid = null;
            estimatedPosition = null;
        } else {
            evaluatePhaseTwo();
        }

        // Proximity model runs in parallel with LSQ trilateration; both are logged so the two
        // approaches can be compared offline from one recorded walk.
        lastZone = engine.classifyProximity(config, now);

        updateModelStatusPanel(active);
        updateCalibratedKeys();
        updateCalibrationStatusLine();
        refreshGroundTruthOptions();
        positioningCanvas.updateP2(engine.snapshot(), estimatedPosition, closestBeaconUid);
    }

    /**
     * Rebuilds the ground-truth spinner from the currently-visible beacons —
     * calibrated ones first and marked with ✓ — keeping the stored value clean
     * ("major,minor"). Preserves the current selection and only rebuilds when
     * the option set changes (no flicker).
     */
    private void refreshGroundTruthOptions() {
        java.util.Set<String> calibrated = engine.calibratedKeys();
        java.util.Set<String> calSeen = new HashSet<>();
        List<String> calLabels = new ArrayList<>();
        for (String id : engine.beacons().keySet()) {
            if (calibrated.contains(id)) {
                String label = P2BeaconIds.extractLabel(id);
                if (calSeen.add(label)) calLabels.add(label);
            }
        }
        java.util.Set<String> otherSeen = new HashSet<>();
        List<String> otherLabels = new ArrayList<>();
        for (String id : engine.beacons().keySet()) {
            if (!calibrated.contains(id)) {
                String label = P2BeaconIds.extractLabel(id);
                if (!calSeen.contains(label) && otherSeen.add(label)) otherLabels.add(label);
            }
        }
        Collections.sort(calLabels);
        Collections.sort(otherLabels);

        List<String> values = new ArrayList<>(calLabels);
        values.addAll(otherLabels);
        if (values.equals(gtValues)) return; // unchanged — keep current selection

        String previous = selectedGroundTruth();
        gtValues.clear();
        gtValues.addAll(values);

        // Rebuild the single-select chips in the same order as gtValues, so the
        // checked chip's index maps directly back to a clean "major,minor" value.
        groupGroundTruth.removeAllViews();
        for (int i = 0; i < values.size(); i++) {
            String v = values.get(i);
            Chip chip = new Chip(requireContext());
            chip.setText(calSeen.contains(v) ? v + " ✓" : v);
            chip.setCheckable(true);
            groupGroundTruth.addView(chip);
        }

        int idx = gtValues.indexOf(previous);
        if (idx < 0 && !gtValues.isEmpty()) idx = 0; // default to first, as the spinner did
        if (idx >= 0) ((Chip) groupGroundTruth.getChildAt(idx)).setChecked(true);
    }

    /** Clean "major,minor" value of the currently-checked ground-truth chip. */
    private String selectedGroundTruth() {
        for (int i = 0; i < groupGroundTruth.getChildCount(); i++) {
            if (((Chip) groupGroundTruth.getChildAt(i)).isChecked()) {
                return i < gtValues.size() ? gtValues.get(i) : "";
            }
        }
        return "";
    }

    private Double processP2Beacon(Beacon beacon, String compositeId) {
        engine.ingest(beacon, config.kalmanQ, config.kalmanR, config.rssiBufferSize, config.rssiTimeWindowMs);
        P2BeaconSample sample = engine.beacons().get(compositeId);
        // Non-mutating read; the RSSI filter is advanced once per tick in
        // runModelEvaluation via engine.updateDistances (mirrors distance_m).
        return sample != null ? sample.getLastFilteredRssi() : null;
    }

    private void evaluatePhaseTwo() {
        estimatedPosition = engine.estimatePosition();

        if (estimatedPosition != null) {
            modelState = "POSITIONING";
            closestBeaconUid = engine.closestTo(estimatedPosition);
        } else {
            modelState = "SEARCHING";
            closestBeaconUid = null;
        }
    }

    // --- Calibration ---

    private void toggleCalibrationMode() {
        if (isRecording) return;

        if (isCalibrationActive) {
            stopCalibrationMode();
        } else {
            startCalibrationMode();
        }
    }

    private void startCalibrationMode() {
        isCalibrationActive = true;

        config.txPower = readInt(editTxPower, -59, -100, 0);
        config.pathLossN = readDouble(editPathLoss, 2.0, 1.0, 6.0);
        config.kalmanQ = readDouble(editKalmanQ, DEFAULT_KALMAN_Q, 0.001, 1.0);
        config.kalmanR = readDouble(editKalmanR, DEFAULT_KALMAN_R, 0.001, 5.0);
        config.rssiBufferSize = readInt(editRssiBuffer, DEFAULT_RSSI_BUFFER_SIZE, 1, 100);
        config.rssiTimeWindowMs = readInt(editRssiWindow, (int) DEFAULT_RSSI_TIME_WINDOW_MS, 500, 30000);
        config.scaleFactor = readDouble(editScaleFactor, DEFAULT_SCALE_FACTOR, 0.1, 50.0);
        config.beaconTimeoutMs = readInt(editBeaconTimeout, (int) DEFAULT_BEACON_TIMEOUT_MS, 1000, 30000);
        config.evalIntervalMs = readInt(editEvalInterval, (int) DEFAULT_MODEL_EVAL_INTERVAL_MS, 500, 30000);
        config.posKalmanQ = readDouble(editPosKalmanQ, P2ModelConfig.DEF_POS_KALMAN_Q,
                P2ModelConfig.MIN_POS_KALMAN_Q, P2ModelConfig.MAX_POS_KALMAN_Q);
        config.posKalmanR = readDouble(editPosKalmanR, P2ModelConfig.DEF_POS_KALMAN_R,
                P2ModelConfig.MIN_POS_KALMAN_R, P2ModelConfig.MAX_POS_KALMAN_R);

        engine.clear();
        engine.setPositionKalman(config.posKalmanQ, config.posKalmanR);
        engine.resetAutoPositionCounter();
        positioningCanvas.clear();

        formSection.setVisibility(View.GONE);
        modelStatusPanel.setVisibility(View.VISIBLE);
        positioningCanvas.setVisibility(View.VISIBLE);

        tvClosest.setText("Closest: ---");
        tvState.setText("State: CALIBRATING");
        tvBeaconCount.setText("Beacons: 0 / 3 required");
        tvKalmanStatus.setText("Kalman: ON (q=" + config.kalmanQ + ", r=" + config.kalmanR + ")");
        tvSolverStatus.setText("Solver: LSQ");
        tvEngineStatus.setText("Tap beacon dots to calibrate");
        updateCalibrationStatusLine();

        btnCalibrate.setText("Done Calibrating");
        btnCalibrate.setBackgroundResource(R.drawable.btn_teal);
        btnCalibrate.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
        btnStartSession.setVisibility(View.VISIBLE);

        ((MainActivity) requireActivity()).stopScannerForRecording();
        bleScanner.startScan();

        if (orientationSensor != null) {
            orientationSensor.start(requireActivity());
        }

        modelHandler.postDelayed(calibrationEvalRunnable, config.evalIntervalMs);
    }

    private void stopCalibrationMode() {
        isCalibrationActive = false;
        modelHandler.removeCallbacks(calibrationEvalRunnable);
        bleScanner.stopScan();

        if (orientationSensor != null) {
            orientationSensor.stop();
        }

        ((MainActivity) requireActivity()).resumeScannerAfterRecording();

        engine.clear();
        engine.resetAutoPositionCounter();
        positioningCanvas.clear();

        formSection.setVisibility(View.VISIBLE);
        btnCalibrate.setText("Calibration Mode");
        btnCalibrate.setBackgroundResource(R.drawable.btn_grey);
        btnCalibrate.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));

        updateCalibrationStatusLine();
    }

    private void onBeaconDuringCalibration(Beacon beacon) {
        if (beacon.getRssi() == 127) return;
        engine.ingest(beacon, config.kalmanQ, config.kalmanR, config.rssiBufferSize, config.rssiTimeWindowMs);
    }

    private void updateCalibratedKeys() {
        positioningCanvas.setCalibratedKeys(engine.calibratedKeys());
    }

    private void updateCalibrationStatusLine() {
        if (tvCalibrationStatus == null) return;
        int totalVisible = engine.beacons().size();
        int calibratedVisible = 0;
        StringBuilder sb = new StringBuilder();
        for (String id : engine.beacons().keySet()) {
            beaconiq.model.Beacon b = calibrationStore.getBeacon(id);
            String[] parts = id.split(":");
            String label = parts.length >= 3 ? parts[1] + "," + parts[2] : id;
            if (b != null) {
                calibratedVisible++;
                sb.append(" [").append(label)
                        .append(" | TX:").append((int) b.getTxPower()).append("]");
            } else {
                sb.append(" [").append(label).append(" ✗]");
            }
        }
        String text = "Calibrated: " + calibratedVisible + "/" + totalVisible;
        if (sb.length() > 0) text += "\n" + sb.toString().trim();
        tvCalibrationStatus.setText(text);
    }

    private void showCalibrationDialog(String compositeId, double currentX, double currentY) {
        if (!isAdded()) return;
        CalibrationDialog.show(requireContext(), modelHandler, compositeId, currentX, currentY,
                config.txPower, config.pathLossN, calibrationStore, engine.beacons(),
                () -> {
                    updateCalibratedKeys();
                    updateCalibrationStatusLine();
                });
    }

    // --- Utilities ---

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        return sb.toString();
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
        @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
    }
}
