package com.beaconiq.trilateration;

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

import com.beaconiq.trilateration.network.TestConsoleApi;
import com.beaconiq.trilateration.positioning.phase1.BeaconSample;
import com.beaconiq.trilateration.positioning.phase1.ProximityEngine;
import com.beaconiq.trilateration.positioning.phase1.TrilaterationJavaSolver;
import com.beaconiq.trilateration.scan.BleDevice;
import com.beaconiq.trilateration.scan.BleScanner;
import com.beaconiq.trilateration.storage.CalibrationStore;
import com.beaconiq.trilateration.sensor.OrientationSensor;
import com.beaconiq.trilateration.ui.PositioningCanvasView;

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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TestFragment extends Fragment implements BleScanner.ScanListener {

    private static final String TAG = "BeaconIQ.TestConsole";
    private static final String PREFS_BEACON = "debug_panel";
    private static final String PREFS_TEST_CONSOLE = "test_console";

    private static final String[] MOVEMENT_MODES =
            {"standing", "walking_slowly", "walking_normally", "running"};
    private static final String[] PHONE_POSITIONS =
            {"hand_at_side", "hand_chest_height", "pocket", "table"};
    private static final String[] SOLVER_MODES_P2 =
            {"Centroid", "WCL"};

    private static final long DEFAULT_MODEL_EVAL_INTERVAL_MS = 3000;
    private static final long DEFAULT_BEACON_TIMEOUT_MS = 4000;
    private static final double DEFAULT_SCALE_FACTOR = 1.0;
    private static final int MIN_BEACONS_REQUIRED = 3;

    private static final double DEFAULT_KALMAN_Q = 0.05;
    private static final double DEFAULT_KALMAN_R = 0.25;
    private static final int DEFAULT_RSSI_BUFFER_SIZE = 20;
    private static final long DEFAULT_RSSI_TIME_WINDOW_MS = 8000;

    // Original RadarScanActivity scan modes (only REAL is active)
    @SuppressWarnings("unused")
    private enum ScanMode { SIMULATED, JAVA, PYTHON, REAL }
    private static final ScanMode SCAN_MODE = ScanMode.REAL;

    // Original RadarScanActivity.PositionState
    private enum PositionState { SEARCHING, POSITIONING, INSIDE_ZONE }

    private EditText editAnalyst, editDuration, editRoom, editNotes;
    private EditText editTxPower, editPathLoss, editRssiThreshold;
    private EditText editKalmanQ, editKalmanR, editRssiBuffer, editRssiWindow;
    private EditText editScaleFactor, editBeaconTimeout, editEvalInterval;
    private Spinner spinnerMovement, spinnerPhonePosition, spinnerSolver;
    private View solverSection, modelParamsSection;

    private int txPower = -59;
    private double pathLossN = 2.0;
    private int rssiThreshold = -100;
    private int selectedDurationSec = 60;

    private double kalmanQ = DEFAULT_KALMAN_Q;
    private double kalmanR = DEFAULT_KALMAN_R;
    private int rssiBufferSize = DEFAULT_RSSI_BUFFER_SIZE;
    private long rssiTimeWindowMs = DEFAULT_RSSI_TIME_WINDOW_MS;
    private double scaleFactor = DEFAULT_SCALE_FACTOR;
    private long beaconTimeoutMs = DEFAULT_BEACON_TIMEOUT_MS;
    private long modelEvalIntervalMs = DEFAULT_MODEL_EVAL_INTERVAL_MS;
    private int savedSolverIndex = 1;

    private View formSection;
    private Button btnStartSession;

    private boolean isPhaseTwo = false;

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
                    "Recording... " + remainingSec + "s (" + ibeaconHits + " readings)");
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
    private volatile int totalScanResults;
    private volatile int ibeaconHits;
    private volatile int rejectedCount;
    private long sessionStartMs;
    private long sessionEndMs;

    private Button btnTabNewSession, btnTabHistory;
    private View sessionFormContainer, sessionHistoryContainer;
    private LinearLayout historyList;
    private TextView historyStatus;
    private boolean historyLoaded;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Map<String, BeaconSample> p1BeaconMap = new ConcurrentHashMap<>();
    private final Map<String, com.beaconiq.trilateration.positioning.phase2.BeaconSample> p2BeaconMap = new ConcurrentHashMap<>();
    private CalibrationStore calibrationStore;
    private int autoPositionCounter;
    private String closestBeaconUid;
    private String modelState = "SEARCHING";
    private double[] estimatedPosition;

    // Phase I: three original TEDtour systems reproduced
    private PositionState currentPositionState = PositionState.SEARCHING;
    private ProximityEngine proximityEngine;          // BUG#2: instantiated, never evaluated
    private final Map<String, Integer> legacyBeaconRSSIMap = new ConcurrentHashMap<>();
    private String legacyNearestBeacon;               // BUG#3: ScanActivity inverted RSSI
    private String currentNearestBeacon;              // ScanActivity change detection
    private String radarClosestBeacon;                // System A: RadarScanActivity result
    private String serviceClosestBeacon;              // System D: BeaconScanService result
    private String lastNotifiedBeaconUuid;            // enterZone dedup (original behavior)
    private String activeStandUuid;                   // original RadarScanActivity.activeStandUuid
    private boolean isDetailActivityOpen;             // original enterZone() guard
    private String currentClosestUuid;                // BeaconScanService.currentClosestUuid

    private OrientationSensor orientationSensor;

    private final Handler modelHandler = new Handler(Looper.getMainLooper());
    private final Runnable modelEvalRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;
            evaluateModel();
            modelHandler.postDelayed(this, modelEvalIntervalMs);
        }
    };

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
        initViews(view);
        loadPreferences();
        setupListeners(view);
        initToggle(view);

        Bundle args = getArguments();
        isPhaseTwo = (args != null && args.getInt("model_phase", 1) == 2);
        applyPhaseConfig();

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
    }

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
        editNotes = view.findViewById(R.id.edit_notes);
        btnStartSession = view.findViewById(R.id.btn_start_session);

        modelParamsSection = view.findViewById(R.id.model_params_section);
        solverSection = view.findViewById(R.id.solver_section);
        spinnerSolver = view.findViewById(R.id.spinner_solver);

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
        positioningCanvas = view.findViewById(R.id.positioning_canvas);
    }

    private void loadPreferences() {
        SharedPreferences beaconPrefs = requireContext()
                .getSharedPreferences(PREFS_BEACON, Context.MODE_PRIVATE);
        SharedPreferences tcPrefs = requireContext()
                .getSharedPreferences(PREFS_TEST_CONSOLE, Context.MODE_PRIVATE);

        txPower = beaconPrefs.getInt("debug_default_tx_power", -59);
        pathLossN = beaconPrefs.getFloat("debug_path_loss_n", 2.0f);
        rssiThreshold = beaconPrefs.getInt("debug_rssi_threshold", -100);
        kalmanQ = beaconPrefs.getFloat("debug_kalman_q", (float) DEFAULT_KALMAN_Q);
        kalmanR = beaconPrefs.getFloat("debug_kalman_r", (float) DEFAULT_KALMAN_R);
        rssiBufferSize = beaconPrefs.getInt("debug_rssi_buffer_size", DEFAULT_RSSI_BUFFER_SIZE);
        rssiTimeWindowMs = beaconPrefs.getInt("debug_rssi_time_window_ms", (int) DEFAULT_RSSI_TIME_WINDOW_MS);
        scaleFactor = beaconPrefs.getFloat("debug_scale_factor", (float) DEFAULT_SCALE_FACTOR);
        beaconTimeoutMs = beaconPrefs.getInt("debug_beacon_timeout_ms", (int) DEFAULT_BEACON_TIMEOUT_MS);
        modelEvalIntervalMs = beaconPrefs.getInt("debug_eval_interval_ms", (int) DEFAULT_MODEL_EVAL_INTERVAL_MS);
        savedSolverIndex = beaconPrefs.getInt("debug_solver_index", 1);

        editTxPower.setText(String.valueOf(txPower));
        editPathLoss.setText(String.format(Locale.US, "%.1f", pathLossN));
        editRssiThreshold.setText(String.valueOf(rssiThreshold));
        editKalmanQ.setText(String.format(Locale.US, "%.3f", kalmanQ));
        editKalmanR.setText(String.format(Locale.US, "%.3f", kalmanR));
        editRssiBuffer.setText(String.valueOf(rssiBufferSize));
        editRssiWindow.setText(String.valueOf(rssiTimeWindowMs));
        editScaleFactor.setText(String.format(Locale.US, "%.1f", scaleFactor));
        editBeaconTimeout.setText(String.valueOf(beaconTimeoutMs));
        editEvalInterval.setText(String.valueOf(modelEvalIntervalMs));
        editDuration.setText(String.valueOf(selectedDurationSec));

        editAnalyst.setText(tcPrefs.getString("analyst_name", ""));
        editRoom.setText(tcPrefs.getString("room_name", ""));
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
            editScaleFactor.setText("5.0");
            editBeaconTimeout.setText("4000");
            editEvalInterval.setText("3000");
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

    private void applyPhaseConfig() {
        int textPrimary = ContextCompat.getColor(requireContext(), R.color.text_primary);
        int textDim = ContextCompat.getColor(requireContext(), R.color.text_dim);

        editTxPower.setEnabled(isPhaseTwo);
        editPathLoss.setEnabled(isPhaseTwo);
        editRssiThreshold.setEnabled(isPhaseTwo);
        int cfgColor = isPhaseTwo ? textPrimary : textDim;
        editTxPower.setTextColor(cfgColor);
        editPathLoss.setTextColor(cfgColor);
        editRssiThreshold.setTextColor(cfgColor);

        EditText[] advancedFields = {editKalmanQ, editKalmanR, editRssiBuffer,
                editRssiWindow, editScaleFactor, editBeaconTimeout, editEvalInterval};
        for (EditText et : advancedFields) {
            et.setEnabled(isPhaseTwo);
            et.setTextColor(cfgColor);
        }

        modelParamsSection.setVisibility(View.VISIBLE);

        if (isPhaseTwo) {
            solverSection.setVisibility(View.VISIBLE);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                    R.layout.spinner_item, SOLVER_MODES_P2);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            spinnerSolver.setAdapter(adapter);
            spinnerSolver.setSelection(savedSolverIndex);
        } else {
            solverSection.setVisibility(View.GONE);
        }
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

    @SuppressLint("MissingPermission")
    private void startSession() {
        String analyst = editAnalyst.getText().toString().trim();
        if (analyst.isEmpty()) return;

        selectedDurationSec = readInt(editDuration, 60, 5, 600);
        if (isPhaseTwo) {
            txPower = readInt(editTxPower, -59, -100, 0);
            pathLossN = readDouble(editPathLoss, 2.0, 1.0, 6.0);
            rssiThreshold = readInt(editRssiThreshold, -100, -120, -20);
            kalmanQ = readDouble(editKalmanQ, DEFAULT_KALMAN_Q, 0.001, 1.0);
            kalmanR = readDouble(editKalmanR, DEFAULT_KALMAN_R, 0.001, 5.0);
            rssiBufferSize = readInt(editRssiBuffer, DEFAULT_RSSI_BUFFER_SIZE, 1, 100);
            rssiTimeWindowMs = readInt(editRssiWindow, (int) DEFAULT_RSSI_TIME_WINDOW_MS, 500, 30000);
            scaleFactor = readDouble(editScaleFactor, DEFAULT_SCALE_FACTOR, 0.1, 50.0);
            beaconTimeoutMs = readInt(editBeaconTimeout, (int) DEFAULT_BEACON_TIMEOUT_MS, 1000, 30000);
            modelEvalIntervalMs = readInt(editEvalInterval, (int) DEFAULT_MODEL_EVAL_INTERVAL_MS, 500, 30000);
        } else {
            txPower = -59;
            pathLossN = 2.0;
            rssiThreshold = -100;
            kalmanQ = DEFAULT_KALMAN_Q;
            kalmanR = DEFAULT_KALMAN_R;
            rssiBufferSize = DEFAULT_RSSI_BUFFER_SIZE;
            rssiTimeWindowMs = DEFAULT_RSSI_TIME_WINDOW_MS;
            scaleFactor = DEFAULT_SCALE_FACTOR;
            beaconTimeoutMs = DEFAULT_BEACON_TIMEOUT_MS;
            modelEvalIntervalMs = DEFAULT_MODEL_EVAL_INTERVAL_MS;
        }

        saveBeaconConfig();
        ((MainActivity) requireActivity()).stopScannerForRecording();

        isRecording = true;
        sessionStartMs = System.currentTimeMillis();

        ibeaconReadings.clear();
        rawScans.clear();
        uniqueBeaconIds.clear();
        totalScanResults = 0;
        ibeaconHits = 0;
        rejectedCount = 0;

        p1BeaconMap.clear();
        p2BeaconMap.clear();
        legacyBeaconRSSIMap.clear();
        legacyNearestBeacon = null;
        currentNearestBeacon = null;
        radarClosestBeacon = null;
        serviceClosestBeacon = null;
        lastNotifiedBeaconUuid = null;
        activeStandUuid = null;
        isDetailActivityOpen = false;
        currentClosestUuid = null;
        proximityEngine = null;
        currentPositionState = PositionState.SEARCHING;
        autoPositionCounter = 0;
        closestBeaconUid = null;
        modelState = "SEARCHING";
        estimatedPosition = null;

        formSection.setVisibility(View.GONE);
        positioningCanvas.clear();

        updateModelStatusPanel();

        remainingSec = selectedDurationSec;
        btnStartSession.setText(
                "Recording... " + remainingSec + "s (0 readings)");

        bleScanner.startScan();
        timerHandler.postDelayed(timerRunnable, 1000);
        modelHandler.postDelayed(modelEvalRunnable, modelEvalIntervalMs);

        if (isPhaseTwo && orientationSensor != null) {
            orientationSensor.start(requireActivity());
        }

        Log.d(TAG, "Session started: phase=" + (isPhaseTwo ? "II" : "I")
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

        Log.d(TAG, "Session ended: ibeacon=" + ibeaconHits
                + " rejected=" + rejectedCount + " total=" + totalScanResults);

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
        session.put("txpower", txPower);
        session.put("path_loss_n", pathLossN);
        session.put("rssi_threshold", rssiThreshold);
        session.put("beacons_detected", uniqueBeaconIds.size());
        session.put("total_scan_results", totalScanResults);
        session.put("ibeacon_hits", ibeaconHits);
        session.put("rejected_count", rejectedCount);
        session.put("model_phase", isPhaseTwo ? "phase_2" : "phase_1");
        session.put("kalman_q", kalmanQ);
        session.put("kalman_r", kalmanR);
        session.put("rssi_buffer_size", rssiBufferSize);
        session.put("rssi_time_window_ms", rssiTimeWindowMs);
        session.put("scale_factor", scaleFactor);
        session.put("beacon_timeout_ms", beaconTimeoutMs);
        session.put("eval_interval_ms", modelEvalIntervalMs);
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
        totalScanResults = 0;
        ibeaconHits = 0;
        rejectedCount = 0;

        p1BeaconMap.clear();
        p2BeaconMap.clear();
        legacyBeaconRSSIMap.clear();
        legacyNearestBeacon = null;
        currentNearestBeacon = null;
        radarClosestBeacon = null;
        serviceClosestBeacon = null;
        lastNotifiedBeaconUuid = null;
        activeStandUuid = null;
        isDetailActivityOpen = false;
        currentClosestUuid = null;
        proximityEngine = null;
        currentPositionState = PositionState.SEARCHING;
        closestBeaconUid = null;
        modelState = "SEARCHING";
        estimatedPosition = null;
    }

    private void saveBeaconConfig() {
        requireContext().getSharedPreferences(PREFS_BEACON, Context.MODE_PRIVATE)
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
                .putInt("debug_eval_interval_ms", (int) modelEvalIntervalMs)
                .putInt("debug_solver_index", spinnerSolver.getSelectedItemPosition())
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

    // --- Positioning model ---

    private void evaluateModel() {
        long now = System.currentTimeMillis();

        if (isPhaseTwo) {
            Iterator<Map.Entry<String, com.beaconiq.trilateration.positioning.phase2.BeaconSample>> p2It =
                    p2BeaconMap.entrySet().iterator();
            while (p2It.hasNext()) {
                if (now - p2It.next().getValue().lastSeen > beaconTimeoutMs) p2It.remove();
            }
            // Phase II: simple active-beacon count
            int active = countActiveBeacons();
            if (active < 3) {
                modelState = "SEARCHING";
                closestBeaconUid = null;
                estimatedPosition = null;
            } else {
                evaluatePhaseTwo();
            }
        } else {
            Iterator<Map.Entry<String, BeaconSample>> p1It =
                    p1BeaconMap.entrySet().iterator();
            while (p1It.hasNext()) {
                if (now - p1It.next().getValue().lastSeen > beaconTimeoutMs) p1It.remove();
            }
            // Phase I: 1:1 original RadarScanActivity.positioningRunnable logic
            int validBeaconCount = p1BeaconMap.size();
            Log.d(TAG, "P1 Beacon count: " + validBeaconCount);

            if (validBeaconCount >= MIN_BEACONS_REQUIRED
                    && hasValidCoordinates(p1BeaconMap.values())
                    && hasEnoughStableBeacons(p1BeaconMap.values())) {
                evaluatePhaseOne();
            } else if (validBeaconCount > 0) {
                transitionTo(PositionState.POSITIONING);
                estimatedPosition = null;
            } else {
                exitZone();
                transitionTo(PositionState.SEARCHING);
            }
        }

        updateModelStatusPanel();
        if (isPhaseTwo) {
            positioningCanvas.updateP2(new HashMap<>(p2BeaconMap), estimatedPosition, closestBeaconUid);
        } else {
            positioningCanvas.update(new HashMap<>(p1BeaconMap), estimatedPosition, closestBeaconUid);
        }
    }

    private boolean hasValidCoordinates(java.util.Collection<BeaconSample> beacons) {
        for (BeaconSample b : beacons) {
            if (Double.isNaN(b.getX()) || Double.isNaN(b.getY())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasEnoughStableBeacons(java.util.Collection<BeaconSample> beacons) {
        int count = 0;
        for (BeaconSample b : beacons) {
            if (b.getFilteredDistance() != null) count++;
        }
        return count >= MIN_BEACONS_REQUIRED;
    }

    private void evaluatePhaseOne() {
        // =================================================================
        // 1:1 reproduction of original TEDtour — three independent systems
        // running simultaneously, each with its own selection logic.
        // =================================================================

        // --- SYSTEM A: RadarScanActivity.runModel() [DRIVES MODEL OUTPUT] ---
        // BUG #1: getFilteredDistance() bypasses KalmanFilter1D (returns raw*5.0)
        for (BeaconSample b : p1BeaconMap.values()) {
            Log.d(TAG, "P1 MODEL INPUT -> uid=" + shortUid(b.getUid())
                    + " x=" + b.getX() + " y=" + b.getY()
                    + " dist=" + b.getFilteredDistance()
                    + " rssiAvg=" + b.getAverageRssi());
        }

        // Original runModel() — COMMENTED OUT version used findBeaconInInfluence (centroid + radius)
        // String closestUuid = TrilaterationJavaSolver.findBeaconInInfluence(p1BeaconMap.values());

        // Original runModel() — ACTIVE version uses findClosestBeacon (min distance)
        radarClosestBeacon = runModel(p1BeaconMap.values());
        Log.d(TAG, "P1 runModel -> closest beacon: " + radarClosestBeacon);

        if (radarClosestBeacon != null && !radarClosestBeacon.equals(lastNotifiedBeaconUuid)) {
            enterZone(radarClosestBeacon);
        }

        transitionTo(PositionState.INSIDE_ZONE);

        if (closestBeaconUid != null) {
            BeaconSample bs = p1BeaconMap.get(closestBeaconUid);
            if (bs != null) {
                estimatedPosition = new double[]{bs.getX(), bs.getY()};
            }
        }

        // --- SYSTEM B: ProximityEngine [BUG #2: instantiated, never evaluated] ---
        // Original: influenceRadius = MIN_BEACONS_REQUIRED (3) instead of meters.
        // evaluate() was never called in the active flow.
        if (proximityEngine == null) {
            proximityEngine = new ProximityEngine(p1BeaconMap, MIN_BEACONS_REQUIRED);
            Log.d(TAG, "P1 ProximityEngine instantiated with influenceRadius="
                    + MIN_BEACONS_REQUIRED + " (BUG: should be meters)");
        }

        // --- SYSTEM C: ScanActivity.findNearestBeacon() [BUG #3: inverted] ---
        // Separate Activity — picks min RSSI (farthest beacon).
        // beaconRSSIMap.clear() happened at start of didRangeBeaconsInRegion.
        // Uses containsKey: only first RSSI per beacon per scan cycle.
        legacyNearestBeacon = findNearestBeaconByRssi(legacyBeaconRSSIMap);
        legacyBeaconRSSIMap.clear();
        Log.d(TAG, "P1 ScanActivity -> nearestBeacon (min RSSI = farthest): "
                + legacyNearestBeacon);

        // Original ScanActivity change detection: beaconNotificationActivity when UUID changes
        if (legacyNearestBeacon != null && !legacyNearestBeacon.equals(currentNearestBeacon)) {
            currentNearestBeacon = legacyNearestBeacon;
            Log.d(TAG, "P1 ScanActivity -> beaconNotificationActivity: "
                    + shortUid(legacyNearestBeacon));
        }

        // --- SYSTEM D: BeaconScanService [strongest raw RSSI = correct logic] ---
        // Foreground service with 1 iBeacon parser (vs 6 in RadarScanActivity).
        // Selected beacon with highest raw RSSI from a single scan cycle.
        // Broadcast ACTION_NEW_STAND on change → RadarScanActivity.onNewStandDetected()
        serviceClosestBeacon = findStrongestBeaconByRawRssi();
        if (serviceClosestBeacon != null
                && !serviceClosestBeacon.equals(currentClosestUuid)) {
            currentClosestUuid = serviceClosestBeacon;
            Log.d(TAG, "P1 BeaconScanService -> ACTION_NEW_STAND: "
                    + shortUid(serviceClosestBeacon));
        }

        // --- DIAGNOSTIC: log when systems disagree ---
        if (radarClosestBeacon != null && legacyNearestBeacon != null
                && !radarClosestBeacon.equals(legacyNearestBeacon)) {
            Log.w(TAG, "DUAL-DETECTION: Radar=" + shortUid(radarClosestBeacon)
                    + " ScanActivity=" + shortUid(legacyNearestBeacon)
                    + " Service=" + (serviceClosestBeacon != null
                    ? shortUid(serviceClosestBeacon) : "---"));
        }

        // Gap 8 (Firebase race): Original created BeaconSample at (0,0), then
        // async-fetched real coords from Firebase. Model could run with (0,0)
        // before Firebase responded. Not reproduced — coords come from
        // CalibrationStore immediately.
    }

    // Original RadarScanActivity.runModel() — active version
    private String runModel(java.util.Collection<BeaconSample> beacons) {
        if (beacons.size() < MIN_BEACONS_REQUIRED) return null;
        return TrilaterationJavaSolver.findClosestBeacon(beacons);
    }

    // Original RadarScanActivity.enterZone()
    private void enterZone(String uuid) {
        if (uuid == null) return;
        if (uuid.equals(lastNotifiedBeaconUuid)) return;

        lastNotifiedBeaconUuid = uuid;
        activeStandUuid = uuid;
        closestBeaconUid = uuid;

        // Original: broadcast ACTION_CLOSEST_BEACON_CHANGED + open BeaconDetailActivityComplete
        Log.d(TAG, "P1 enterZone: ACTION_CLOSEST_BEACON_CHANGED uuid=" + shortUid(uuid));
        if (!isDetailActivityOpen) {
            Log.d(TAG, "P1 enterZone: would open BeaconDetailActivityComplete for " + shortUid(uuid));
            isDetailActivityOpen = true;
        }
    }

    // Original RadarScanActivity.exitZone()
    private void exitZone() {
        if (activeStandUuid != null) {
            activeStandUuid = null;
            lastNotifiedBeaconUuid = null;
        }
        closestBeaconUid = null;
        estimatedPosition = null;
    }

    // Original RadarScanActivity.transitionTo() — only acts when state changes
    private void transitionTo(PositionState state) {
        if (currentPositionState == state) return;
        currentPositionState = state;
        modelState = state.name();
        Log.d(TAG, "P1 transitionTo: " + state.name());
    }

    // Original ScanActivity.findNearestBeacon() — 1:1 reproduction
    // BUG: picks MINIMUM RSSI (most negative = farthest beacon)
    private static String findNearestBeaconByRssi(Map<String, Integer> beaconRSSIMap) {
        String nearestBeacon = null;
        int minRSSI = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : beaconRSSIMap.entrySet()) {
            if (entry.getValue() < minRSSI) {
                minRSSI = entry.getValue();
                nearestBeacon = entry.getKey();
            }
        }
        return nearestBeacon;
    }

    // Original BeaconScanService — selects beacon with strongest raw RSSI
    // Original used raw RSSI from single scan cycle, not averaged.
    // Only had 1 iBeacon parser (we use same pool — noted as limitation).
    private String findStrongestBeaconByRawRssi() {
        String strongest = null;
        int maxRssi = Integer.MIN_VALUE;
        for (Map.Entry<String, BeaconSample> entry : p1BeaconMap.entrySet()) {
            int lastRssi = entry.getValue().getLastRawRssi();
            if (lastRssi > maxRssi) {
                maxRssi = lastRssi;
                strongest = entry.getKey();
            }
        }
        return strongest;
    }

    private void evaluatePhaseTwo() {
        int solverIndex = spinnerSolver.getSelectedItemPosition();
        if (solverIndex == 1) {
            estimatedPosition = estimatePositionWCL();
        } else {
            estimatedPosition = com.beaconiq.trilateration.positioning.phase2.TrilaterationJavaSolver
                    .estimatePosition(p2BeaconMap.values());
        }

        if (estimatedPosition != null) {
            modelState = "POSITIONING";
            closestBeaconUid = findClosestToPosition(estimatedPosition);
        } else {
            modelState = "SEARCHING";
            closestBeaconUid = null;
        }
    }

    private double[] estimatePositionWCL() {
        double sumWx = 0, sumWy = 0, sumW = 0;
        for (com.beaconiq.trilateration.positioning.phase2.BeaconSample b : p2BeaconMap.values()) {
            Double d = b.getKalmanFilteredDistance(txPower, pathLossN, scaleFactor);
            if (d == null || d <= 0) continue;
            double w = 1.0 / (d * d);
            sumWx += w * b.getX();
            sumWy += w * b.getY();
            sumW += w;
        }
        if (sumW == 0) return null;
        return new double[]{sumWx / sumW, sumWy / sumW};
    }

    private String findClosestToPosition(double[] pos) {
        String closest = null;
        double minDist = Double.MAX_VALUE;
        for (Map.Entry<String, com.beaconiq.trilateration.positioning.phase2.BeaconSample> entry : p2BeaconMap.entrySet()) {
            com.beaconiq.trilateration.positioning.phase2.BeaconSample b = entry.getValue();
            double dx = pos[0] - b.getX();
            double dy = pos[1] - b.getY();
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < minDist) {
                minDist = d;
                closest = entry.getKey();
            }
        }
        return closest;
    }

    private int countActiveBeacons() {
        int count = 0;
        if (isPhaseTwo) {
            for (com.beaconiq.trilateration.positioning.phase2.BeaconSample b : p2BeaconMap.values()) {
                if (b.getKalmanFilteredDistance(txPower, pathLossN, scaleFactor) != null) count++;
            }
        } else {
            for (BeaconSample b : p1BeaconMap.values()) {
                if (b.getFilteredDistance() != null) count++;
            }
        }
        return count;
    }

    private void updateModelStatusPanel() {
        int active = countActiveBeacons();
        String uid = closestBeaconUid != null ? shortUid(closestBeaconUid) : "---";

        tvClosest.setText("Closest: " + uid);
        tvState.setText("State: " + modelState);
        tvBeaconCount.setText("Beacons: " + active + " / 3 required");

        if (isPhaseTwo) {
            tvKalmanStatus.setText("Kalman: ON (q=" + kalmanQ + ", r=" + kalmanR + ")");
            String solver = spinnerSolver.getSelectedItemPosition() == 1
                    ? "WCL" : "Centroid";
            tvSolverStatus.setText("Solver: " + solver);
            tvEngineStatus.setText("ProximityEngine: not used (direct solver)");
        } else {
            String radarUid = radarClosestBeacon != null ? shortUid(radarClosestBeacon) : "---";
            String scanUid = legacyNearestBeacon != null ? shortUid(legacyNearestBeacon) : "---";
            String svcUid = serviceClosestBeacon != null ? shortUid(serviceClosestBeacon) : "---";

            tvKalmanStatus.setText("Radar(noKalman): " + radarUid
                    + " | Solver: findClosestBeacon");
            tvSolverStatus.setText("Scan(minRSSI): " + scanUid
                    + " | Svc(rawRSSI): " + svcUid);
            tvEngineStatus.setText("Engine(r=" + MIN_BEACONS_REQUIRED
                    + "): instantiated, not called"
                    + " | findBeaconInInfluence: commented");
        }
    }

    private static String shortUid(String compositeId) {
        String[] parts = compositeId.split(":");
        if (parts.length >= 3) {
            String uuid = parts[0];
            if (uuid.length() > 8) uuid = uuid.substring(0, 8) + "...";
            return uuid + ":" + parts[1] + ":" + parts[2];
        }
        return compositeId;
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

    // --- BleScanner.ScanListener ---

    @Override
    public void onBeaconDiscovered(Beacon beacon, byte[] scanRecord) {
        if (!isRecording) return;

        totalScanResults++;

        if (isPhaseTwo && beacon.getRssi() == 127) return;

        String compositeId = beacon.getId1().toString();
        if (beacon.getIdentifiers().size() >= 2) compositeId += ":" + beacon.getId2();
        if (beacon.getIdentifiers().size() >= 3) compositeId += ":" + beacon.getId3();
        uniqueBeaconIds.add(compositeId);

        if (beacon.getRssi() < rssiThreshold) {
            rejectedCount++;
            return;
        }

        ibeaconHits++;

        double distance = Math.pow(10.0, (txPower - beacon.getRssi()) / (10.0 * pathLossN));
        Double filteredRssi;

        if (isPhaseTwo) {
            com.beaconiq.trilateration.positioning.phase2.BeaconSample sample = p2BeaconMap.get(compositeId);
            if (sample == null) {
                double[] pos = getBeaconPosition(compositeId, beacon);
                sample = new com.beaconiq.trilateration.positioning.phase2.BeaconSample(
                        compositeId, pos[0], pos[1],
                        kalmanQ, kalmanR, rssiBufferSize, rssiTimeWindowMs);
                p2BeaconMap.put(compositeId, sample);
            }
            sample.addRssi(beacon.getRssi());
            filteredRssi = sample.getKalmanFilteredRssi();
        } else {
            BeaconSample sample = p1BeaconMap.get(compositeId);
            if (sample == null) {
                double[] pos = getBeaconPosition(compositeId, beacon);
                sample = new BeaconSample(compositeId, pos[0], pos[1]);
                p1BeaconMap.put(compositeId, sample);
            }
            sample.addRssi(beacon.getRssi());
            filteredRssi = sample.getAverageRssi();

            // Phase I legacy: feed raw RSSI into ScanActivity's map (original only stored first seen)
            if (!legacyBeaconRSSIMap.containsKey(compositeId)) {
                legacyBeaconRSSIMap.put(compositeId, beacon.getRssi());
            }
        }

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
        reading.put("distance_m", Math.round(distance * 100.0) / 100.0);
        double[] pos = estimatedPosition;
        reading.put("est_x", pos != null ? Math.round(pos[0] * 100.0) / 100.0 : "");
        reading.put("est_y", pos != null ? Math.round(pos[1] * 100.0) / 100.0 : "");
        reading.put("model_phase", isPhaseTwo ? "phase_2" : "phase_1");
        if (!isPhaseTwo) {
            BeaconSample p1sample = p1BeaconMap.get(compositeId);
            Double unfilteredDist = p1sample != null ? p1sample.getFilteredDistance() : null;
            reading.put("dist_no_kalman", unfilteredDist != null
                    ? Math.round(unfilteredDist * 100.0) / 100.0 : "");
            reading.put("radar_closest", radarClosestBeacon != null ? radarClosestBeacon : "");
            reading.put("scan_nearest_rssi", legacyNearestBeacon != null ? legacyNearestBeacon : "");
            reading.put("service_closest", serviceClosestBeacon != null ? serviceClosestBeacon : "");
            reading.put("dual_conflict",
                    radarClosestBeacon != null && legacyNearestBeacon != null
                    && !radarClosestBeacon.equals(legacyNearestBeacon) ? "YES" : "NO");
        }
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

        totalScanResults++;
        rejectedCount++;

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

    @Override
    public void onResume() {
        super.onResume();
        // Original RadarScanActivity.onResume() reset all state on every resume
        if (!isPhaseTwo && isRecording) {
            lastNotifiedBeaconUuid = null;
            activeStandUuid = null;
            isDetailActivityOpen = false;
            closestBeaconUid = null;
            currentPositionState = PositionState.SEARCHING;
            modelState = "SEARCHING";
            estimatedPosition = null;
            Log.d(TAG, "P1 onResume: state reset (original behavior)");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isRecording) {
            endSession();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
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
