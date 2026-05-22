package com.beaconiq.trilateration;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
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
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.beaconiq.trilateration.network.TestConsoleApi;
import com.beaconiq.trilateration.positioning.phase2.P2BeaconSample;
import com.beaconiq.trilateration.positioning.phase2.P2TrilaterationJavaSolver;
import com.beaconiq.trilateration.scan.BleDevice;
import com.beaconiq.trilateration.scan.BleScanner;
import com.beaconiq.trilateration.sensor.OrientationSensor;
import com.beaconiq.trilateration.storage.CalibrationStore;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhaseTwoTestFragment extends Fragment implements BleScanner.ScanListener {

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

    // --- UI fields ---

    private EditText editAnalyst, editDuration, editRoom, editNotes;
    private EditText editTxPower, editPathLoss, editRssiThreshold;
    private EditText editKalmanQ, editKalmanR, editRssiBuffer, editRssiWindow;
    private EditText editScaleFactor, editBeaconTimeout, editEvalInterval;
    private Spinner spinnerMovement, spinnerPhonePosition, spinnerSolver;
    private View solverSection, modelParamsSection;

    // --- Model parameters ---

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

    private CalibrationStore calibrationStore;
    private int autoPositionCounter;
    private String closestBeaconUid;
    private String modelState = "SEARCHING";
    private double[] estimatedPosition;

    private OrientationSensor orientationSensor;

    private Button btnCalibrate;
    private TextView tvCalibrationStatus;

    private final Handler modelHandler = new Handler(Looper.getMainLooper());

    // --- P2-specific state ---

    private final Map<String, P2BeaconSample> p2BeaconMap = new ConcurrentHashMap<>();
    private boolean isCalibrationActive;

    // --- Runnables ---

    private final Runnable modelEvalRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;
            runModelEvaluation();
            modelHandler.postDelayed(this, modelEvalIntervalMs);
        }
    };

    private final Runnable calibrationEvalRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isCalibrationActive) return;
            long now = System.currentTimeMillis();
            p2BeaconMap.entrySet().removeIf(e -> now - e.getValue().lastSeen > beaconTimeoutMs);

            double[] position = null;
            String closestKey = null;
            int solIdx = spinnerSolver.getSelectedItemPosition();
            if (solIdx == 1) {
                position = estimatePositionWCL();
            } else {
                position = P2TrilaterationJavaSolver.estimatePosition(p2BeaconMap.values());
            }
            if (position != null) {
                closestKey = findClosestToPosition(position);
            }

            updateCalibratedKeys();
            updateCalibrationStatusLine();
            positioningCanvas.updateP2(new HashMap<>(p2BeaconMap), position, closestKey);
            modelHandler.postDelayed(this, modelEvalIntervalMs);
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

        solverSection.setVisibility(View.VISIBLE);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                R.layout.spinner_item, SOLVER_MODES_P2);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerSolver.setAdapter(adapter);
        spinnerSolver.setSelection(savedSolverIndex);

        tvKalmanStatus.setText("Kalman: ON (q=" + kalmanQ + ", r=" + kalmanR + ")");
        tvSolverStatus.setText("Solver: " + (savedSolverIndex == 1 ? "WCL" : "Centroid"));
        tvEngineStatus.setText("ProximityEngine: not used (direct solver)");
        tvCalibrationStatus.setText("Calibrated: 0/0");
        btnCalibrate.setVisibility(View.VISIBLE);
    }

    private void loadModelPreferences() {
        SharedPreferences beaconPrefs = requireContext()
                .getSharedPreferences(PREFS_BEACON, Context.MODE_PRIVATE);

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

    private void readSessionParams() {
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
        totalScanResults = 0;
        ibeaconHits = 0;
        rejectedCount = 0;

        autoPositionCounter = 0;
        closestBeaconUid = null;
        modelState = "SEARCHING";
        estimatedPosition = null;

        p2BeaconMap.clear();
        btnCalibrate.setVisibility(View.GONE);

        formSection.setVisibility(View.GONE);
        positioningCanvas.clear();

        updateModelStatusPanel();

        remainingSec = selectedDurationSec;
        btnStartSession.setText(
                "Recording... " + remainingSec + "s (0 readings)");

        bleScanner.startScan();
        timerHandler.postDelayed(timerRunnable, 1000);
        modelHandler.postDelayed(modelEvalRunnable, modelEvalIntervalMs);

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
        session.put("model_phase", "phase_2");
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

        closestBeaconUid = null;
        modelState = "SEARCHING";
        estimatedPosition = null;

        p2BeaconMap.clear();
        btnCalibrate.setVisibility(View.VISIBLE);
    }

    // --- Status panel ---

    private void updateModelStatusPanel() {
        int active = countActiveP2Beacons();
        String uid = closestBeaconUid != null ? shortUid(closestBeaconUid) : "---";

        tvClosest.setText("Closest: " + uid);
        tvState.setText("State: " + modelState);
        tvBeaconCount.setText("Beacons: " + active + " / 3 required");

        tvKalmanStatus.setText("Kalman: ON (q=" + kalmanQ + ", r=" + kalmanR + ")");
        String solver = spinnerSolver.getSelectedItemPosition() == 1
                ? "WCL" : "Centroid";
        tvSolverStatus.setText("Solver: " + solver);
        tvEngineStatus.setText("ProximityEngine: not used (direct solver)");
    }

    // --- Positioning ---

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

    // --- BLE callbacks ---

    @Override
    public void onBeaconDiscovered(Beacon beacon, byte[] scanRecord) {
        if (isCalibrationActive) {
            onBeaconDuringCalibration(beacon);
            return;
        }
        if (!isRecording) return;

        totalScanResults++;

        if (beacon.getRssi() == 127) return;

        String compositeId = buildCompositeId(beacon);
        uniqueBeaconIds.add(compositeId);

        if (beacon.getRssi() < rssiThreshold) {
            rejectedCount++;
            return;
        }

        ibeaconHits++;

        double distance = Math.pow(10.0, (txPower - beacon.getRssi()) / (10.0 * pathLossN));
        Double filteredRssi = processP2Beacon(beacon, compositeId);

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
        reading.put("model_phase", "phase_2");
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

    // --- P2 model evaluation ---

    private void runModelEvaluation() {
        long now = System.currentTimeMillis();
        p2BeaconMap.entrySet().removeIf(e -> now - e.getValue().lastSeen > beaconTimeoutMs);

        int active = countActiveP2Beacons();
        if (active < MIN_BEACONS_REQUIRED) {
            modelState = "SEARCHING";
            closestBeaconUid = null;
            estimatedPosition = null;
        } else {
            evaluatePhaseTwo();
        }

        updateModelStatusPanel();
        updateCalibratedKeys();
        updateCalibrationStatusLine();
        positioningCanvas.updateP2(new HashMap<>(p2BeaconMap), estimatedPosition, closestBeaconUid);
    }

    private Double processP2Beacon(Beacon beacon, String compositeId) {
        P2BeaconSample sample = getOrCreateP2Sample(beacon, compositeId);
        return sample.getKalmanFilteredRssi();
    }

    private P2BeaconSample getOrCreateP2Sample(Beacon beacon, String compositeId) {
        P2BeaconSample sample = p2BeaconMap.get(compositeId);
        if (sample == null) {
            double[] pos = getBeaconPosition(compositeId, beacon);
            sample = new P2BeaconSample(
                    compositeId, pos[0], pos[1],
                    kalmanQ, kalmanR, rssiBufferSize, rssiTimeWindowMs);
            com.beaconiq.trilateration.model.Beacon cal =
                    calibrationStore.getBeacon(compositeId);
            if (cal != null) {
                sample.setTxPowerOverride(cal.getTxPower());
            }
            p2BeaconMap.put(compositeId, sample);
        }
        sample.addRssi(beacon.getRssi());
        return sample;
    }

    private int countActiveP2Beacons() {
        int count = 0;
        for (P2BeaconSample b : p2BeaconMap.values()) {
            if (b.getKalmanFilteredDistance(txPower, pathLossN, scaleFactor) != null) count++;
        }
        return count;
    }

    private void evaluatePhaseTwo() {
        int solverIndex = spinnerSolver.getSelectedItemPosition();
        if (solverIndex == 1) {
            estimatedPosition = estimatePositionWCL();
        } else {
            estimatedPosition = P2TrilaterationJavaSolver
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
        for (P2BeaconSample b : p2BeaconMap.values()) {
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
        for (Map.Entry<String, P2BeaconSample> entry : p2BeaconMap.entrySet()) {
            P2BeaconSample b = entry.getValue();
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

        txPower = readInt(editTxPower, -59, -100, 0);
        pathLossN = readDouble(editPathLoss, 2.0, 1.0, 6.0);
        kalmanQ = readDouble(editKalmanQ, DEFAULT_KALMAN_Q, 0.001, 1.0);
        kalmanR = readDouble(editKalmanR, DEFAULT_KALMAN_R, 0.001, 5.0);
        rssiBufferSize = readInt(editRssiBuffer, DEFAULT_RSSI_BUFFER_SIZE, 1, 100);
        rssiTimeWindowMs = readInt(editRssiWindow, (int) DEFAULT_RSSI_TIME_WINDOW_MS, 500, 30000);
        scaleFactor = readDouble(editScaleFactor, DEFAULT_SCALE_FACTOR, 0.1, 50.0);
        beaconTimeoutMs = readInt(editBeaconTimeout, (int) DEFAULT_BEACON_TIMEOUT_MS, 1000, 30000);
        modelEvalIntervalMs = readInt(editEvalInterval, (int) DEFAULT_MODEL_EVAL_INTERVAL_MS, 500, 30000);

        p2BeaconMap.clear();
        autoPositionCounter = 0;
        positioningCanvas.clear();

        formSection.setVisibility(View.GONE);
        modelStatusPanel.setVisibility(View.VISIBLE);
        positioningCanvas.setVisibility(View.VISIBLE);

        tvClosest.setText("Closest: ---");
        tvState.setText("State: CALIBRATING");
        tvBeaconCount.setText("Beacons: 0 / 3 required");
        tvKalmanStatus.setText("Kalman: ON (q=" + kalmanQ + ", r=" + kalmanR + ")");
        String solver = spinnerSolver.getSelectedItemPosition() == 1 ? "WCL" : "Centroid";
        tvSolverStatus.setText("Solver: " + solver);
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

        modelHandler.postDelayed(calibrationEvalRunnable, modelEvalIntervalMs);
    }

    private void stopCalibrationMode() {
        isCalibrationActive = false;
        modelHandler.removeCallbacks(calibrationEvalRunnable);
        bleScanner.stopScan();

        if (orientationSensor != null) {
            orientationSensor.stop();
        }

        ((MainActivity) requireActivity()).resumeScannerAfterRecording();

        p2BeaconMap.clear();
        autoPositionCounter = 0;
        positioningCanvas.clear();

        formSection.setVisibility(View.VISIBLE);
        btnCalibrate.setText("Calibration Mode");
        btnCalibrate.setBackgroundResource(R.drawable.btn_grey);
        btnCalibrate.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));

        updateCalibrationStatusLine();
    }

    private void onBeaconDuringCalibration(Beacon beacon) {
        if (beacon.getRssi() == 127) return;

        String compositeId = buildCompositeId(beacon);
        getOrCreateP2Sample(beacon, compositeId);
    }

    private void updateCalibratedKeys() {
        Set<String> keys = new HashSet<>();
        for (String compositeId : p2BeaconMap.keySet()) {
            if (calibrationStore.getBeacon(compositeId) != null) {
                keys.add(compositeId);
            }
        }
        positioningCanvas.setCalibratedKeys(keys);
    }

    private void updateCalibrationStatusLine() {
        if (tvCalibrationStatus == null) return;
        int totalVisible = p2BeaconMap.size();
        int calibratedVisible = 0;
        StringBuilder sb = new StringBuilder();
        for (String id : p2BeaconMap.keySet()) {
            com.beaconiq.trilateration.model.Beacon b = calibrationStore.getBeacon(id);
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

        String[] parts = compositeId.split(":");
        String uuid = parts.length >= 1 ? parts[0] : "";
        String major = parts.length >= 2 ? parts[1] : "?";
        String minor = parts.length >= 3 ? parts[2] : "?";

        com.beaconiq.trilateration.model.Beacon saved = calibrationStore.getBeacon(compositeId);
        double startX = saved != null ? saved.getX() : currentX;
        double startY = saved != null ? saved.getY() : currentY;
        final double[] calibratedTxPower = {saved != null ? saved.getTxPower() : txPower};

        Context ctx = requireContext();
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView subtitle = new TextView(ctx);
        subtitle.setText("uuid: " + uuid.substring(0, Math.min(uuid.length(), 18)) + "...");
        subtitle.setTextColor(0xFF9E9E9E);
        subtitle.setTextSize(12f);
        layout.addView(subtitle);

        TextView labelX = new TextView(ctx);
        labelX.setText("X (meters)");
        labelX.setTextColor(0xFFE0E0E0);
        labelX.setPadding(0, pad / 2, 0, 4);
        layout.addView(labelX);

        EditText editX = new EditText(ctx);
        editX.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        editX.setText(String.format(Locale.US, "%.2f", startX));
        editX.setTextColor(0xFFFFFFFF);
        editX.setBackgroundColor(0xFF424242);
        editX.setPadding(16, 12, 16, 12);
        layout.addView(editX);

        TextView labelY = new TextView(ctx);
        labelY.setText("Y (meters)");
        labelY.setTextColor(0xFFE0E0E0);
        labelY.setPadding(0, pad / 2, 0, 4);
        layout.addView(labelY);

        EditText editY = new EditText(ctx);
        editY.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        editY.setText(String.format(Locale.US, "%.2f", startY));
        editY.setTextColor(0xFFFFFFFF);
        editY.setBackgroundColor(0xFF424242);
        editY.setPadding(16, 12, 16, 12);
        layout.addView(editY);

        TextView tvTxPower = new TextView(ctx);
        tvTxPower.setText(String.format(Locale.US, "TX Power: %d dBm", (int) calibratedTxPower[0]));
        tvTxPower.setTextColor(0xFFE0E0E0);
        tvTxPower.setPadding(0, pad, 0, 4);
        layout.addView(tvTxPower);

        P2BeaconSample sample = p2BeaconMap.get(compositeId);

        TextView tvLiveRssi = new TextView(ctx);
        int liveRssi = sample != null ? sample.getLastRawRssi() : 0;
        tvLiveRssi.setText(String.format(Locale.US, "Current RSSI: %d dBm", liveRssi));
        tvLiveRssi.setTextColor(0xFF9E9E9E);
        tvLiveRssi.setTextSize(12f);
        layout.addView(tvLiveRssi);

        ProgressBar progressBar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        layout.addView(progressBar);

        Button btnCalibrateBtn = new Button(ctx);
        btnCalibrateBtn.setText("Calibrate TX (stand 1m away)");
        btnCalibrateBtn.setTextSize(13f);
        btnCalibrateBtn.setAllCaps(false);

        btnCalibrateBtn.setOnClickListener(v -> {
            btnCalibrateBtn.setEnabled(false);
            btnCalibrateBtn.setText("Sampling...");
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);

            final List<Integer> rssiSamples = new ArrayList<>();
            final long sampleDurationMs = 5000;
            final long sampleIntervalMs = 200;
            final int totalSteps = (int) (sampleDurationMs / sampleIntervalMs);

            Runnable[] sampleRunnable = new Runnable[1];
            final int[] step = {0};
            sampleRunnable[0] = () -> {
                P2BeaconSample s = p2BeaconMap.get(compositeId);
                if (s != null && s.getLastRawRssi() != 0) {
                    rssiSamples.add(s.getLastRawRssi());
                }
                step[0]++;
                progressBar.setProgress((step[0] * 100) / totalSteps);

                if (step[0] < totalSteps) {
                    modelHandler.postDelayed(sampleRunnable[0], sampleIntervalMs);
                } else {
                    progressBar.setVisibility(View.GONE);
                    if (!rssiSamples.isEmpty()) {
                        int sum = 0;
                        for (int r : rssiSamples) sum += r;
                        calibratedTxPower[0] = sum / rssiSamples.size();
                        tvTxPower.setText(String.format(Locale.US,
                                "TX Power: %d dBm (calibrated)", (int) calibratedTxPower[0]));
                    }
                    btnCalibrateBtn.setEnabled(true);
                    btnCalibrateBtn.setText("Calibrate TX (stand 1m away)");
                }
            };
            modelHandler.post(sampleRunnable[0]);
        });
        layout.addView(btnCalibrateBtn);

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("Beacon " + major + "," + minor)
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    double newX, newY;
                    try {
                        newX = Double.parseDouble(editX.getText().toString().trim());
                        newY = Double.parseDouble(editY.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        Toast.makeText(ctx, "Invalid coordinates", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int majorInt = 0, minorInt = 0;
                    try { majorInt = Integer.parseInt(major); } catch (NumberFormatException ignored) {}
                    try { minorInt = Integer.parseInt(minor); } catch (NumberFormatException ignored) {}

                    com.beaconiq.trilateration.model.Beacon beacon =
                            new com.beaconiq.trilateration.model.Beacon(
                                    uuid, majorInt, minorInt, newX, newY,
                                    calibratedTxPower[0], pathLossN);
                    calibrationStore.saveBeacon(beacon);

                    P2BeaconSample existing = p2BeaconMap.get(compositeId);
                    if (existing != null) {
                        existing.setCoordinates(newX, newY);
                    }
                    updateCalibratedKeys();
                    updateCalibrationStatusLine();
                    Toast.makeText(ctx, "Beacon " + major + "," + minor + " saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null);

        if (saved != null) {
            builder.setNeutralButton("Delete", (dialog, which) -> {
                calibrationStore.removeBeacon(compositeId);
                updateCalibratedKeys();
                updateCalibrationStatusLine();
                Toast.makeText(ctx, "Calibration removed", Toast.LENGTH_SHORT).show();
            });
        }

        builder.show();
    }

    // --- Utilities ---

    private static String buildCompositeId(Beacon beacon) {
        String id = beacon.getId1().toString();
        if (beacon.getIdentifiers().size() >= 2) id += ":" + beacon.getId2();
        if (beacon.getIdentifiers().size() >= 3) id += ":" + beacon.getId3();
        return id;
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
