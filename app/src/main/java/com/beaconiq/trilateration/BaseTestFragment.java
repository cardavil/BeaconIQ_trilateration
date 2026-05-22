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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class BaseTestFragment extends Fragment implements BleScanner.ScanListener {

    protected static final String TAG = "BeaconIQ.TestConsole";
    protected static final String PREFS_BEACON = "debug_panel";
    private static final String PREFS_TEST_CONSOLE = "test_console";

    protected static final String[] MOVEMENT_MODES =
            {"standing", "walking_slowly", "walking_normally", "running"};
    protected static final String[] PHONE_POSITIONS =
            {"hand_at_side", "hand_chest_height", "pocket", "table"};
    protected static final long DEFAULT_MODEL_EVAL_INTERVAL_MS = 3000;
    protected static final long DEFAULT_BEACON_TIMEOUT_MS = 4000;
    protected static final double DEFAULT_SCALE_FACTOR = 1.0;
    protected static final int MIN_BEACONS_REQUIRED = 3;

    protected static final double DEFAULT_KALMAN_Q = 0.05;
    protected static final double DEFAULT_KALMAN_R = 0.25;
    protected static final int DEFAULT_RSSI_BUFFER_SIZE = 20;
    protected static final long DEFAULT_RSSI_TIME_WINDOW_MS = 8000;

    protected EditText editAnalyst, editDuration, editRoom, editNotes;
    protected EditText editTxPower, editPathLoss, editRssiThreshold;
    protected EditText editKalmanQ, editKalmanR, editRssiBuffer, editRssiWindow;
    protected EditText editScaleFactor, editBeaconTimeout, editEvalInterval;
    protected Spinner spinnerMovement, spinnerPhonePosition, spinnerSolver;
    protected View solverSection, modelParamsSection;

    protected int txPower = -59;
    protected double pathLossN = 2.0;
    protected int rssiThreshold = -100;
    protected int selectedDurationSec = 60;

    protected double kalmanQ = DEFAULT_KALMAN_Q;
    protected double kalmanR = DEFAULT_KALMAN_R;
    protected int rssiBufferSize = DEFAULT_RSSI_BUFFER_SIZE;
    protected long rssiTimeWindowMs = DEFAULT_RSSI_TIME_WINDOW_MS;
    protected double scaleFactor = DEFAULT_SCALE_FACTOR;
    protected long beaconTimeoutMs = DEFAULT_BEACON_TIMEOUT_MS;
    protected long modelEvalIntervalMs = DEFAULT_MODEL_EVAL_INTERVAL_MS;
    protected View formSection;
    protected Button btnStartSession;

    protected View modelStatusPanel;
    protected TextView tvClosest, tvState, tvBeaconCount;
    protected TextView tvKalmanStatus, tvSolverStatus, tvEngineStatus;
    protected PositioningCanvasView positioningCanvas;

    protected BleScanner bleScanner;
    protected final Handler timerHandler = new Handler(Looper.getMainLooper());
    protected int remainingSec;
    protected boolean isRecording;

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

    protected final List<Map<String, Object>> ibeaconReadings =
            Collections.synchronizedList(new ArrayList<>());
    protected final List<Map<String, Object>> rawScans =
            Collections.synchronizedList(new ArrayList<>());
    protected final Set<String> uniqueBeaconIds = new HashSet<>();
    protected volatile int totalScanResults;
    protected volatile int ibeaconHits;
    protected volatile int rejectedCount;
    protected long sessionStartMs;
    protected long sessionEndMs;

    private Button btnTabNewSession, btnTabHistory;
    private View sessionFormContainer, sessionHistoryContainer;
    private LinearLayout historyList;
    private TextView historyStatus;
    private boolean historyLoaded;

    protected final ExecutorService executor = Executors.newSingleThreadExecutor();

    protected CalibrationStore calibrationStore;
    protected int autoPositionCounter;
    protected String closestBeaconUid;
    protected String modelState = "SEARCHING";
    protected double[] estimatedPosition;

    protected OrientationSensor orientationSensor;

    protected Button btnCalibrate;
    protected TextView tvCalibrationStatus;

    protected final Handler modelHandler = new Handler(Looper.getMainLooper());
    private final Runnable modelEvalRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;
            runModelEvaluation();
            modelHandler.postDelayed(this, modelEvalIntervalMs);
        }
    };

    // --- Abstract hooks ---

    protected abstract void applyPhaseConfig();
    protected abstract String getPhaseLabel();
    protected abstract void readSessionParams();
    protected abstract void clearPhaseState();
    protected abstract void runModelEvaluation();
    protected abstract Double processBeacon(Beacon beacon, String compositeId);
    protected abstract Map<String, Object> buildPhaseReadingFields(String compositeId);
    protected abstract void onResumePhase();
    protected abstract int countActiveBeacons();
    protected abstract void updatePhaseStatusLabels();

    // --- Overridable hooks ---

    protected boolean isCalibrating() { return false; }
    protected void onBeaconDuringCalibration(Beacon beacon) { }
    protected void stopCalibrationIfActive() { }
    protected boolean shouldSkipBeacon(Beacon beacon) { return false; }

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

    @Override
    public void onResume() {
        super.onResume();
        onResumePhase();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopCalibrationIfActive();
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
        stopCalibrationIfActive();
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

        clearPhaseState();

        formSection.setVisibility(View.GONE);
        btnCalibrate.setVisibility(View.GONE);
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

        Log.d(TAG, "Session started: phase=" + getPhaseLabel()
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
        session.put("model_phase", getPhaseLabel());
        session.put("kalman_q", kalmanQ);
        session.put("kalman_r", kalmanR);
        session.put("rssi_buffer_size", rssiBufferSize);
        session.put("rssi_time_window_ms", rssiTimeWindowMs);
        session.put("scale_factor", scaleFactor);
        session.put("beacon_timeout_ms", beaconTimeoutMs);
        session.put("eval_interval_ms", modelEvalIntervalMs);
        session.put("solver_type", "N/A");
        JSONArray beaconConfig = new JSONArray();
        for (com.beaconiq.trilateration.model.Beacon b : calibrationStore.getAllBeacons()) {
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

    protected void resetForm() {
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

        clearPhaseState();
    }

    // --- Config ---

    protected void saveBeaconConfig() {
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

    protected int readInt(EditText field, int defaultVal, int min, int max) {
        try {
            int val = Integer.parseInt(field.getText().toString().trim());
            return Math.max(min, Math.min(max, val));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    protected double readDouble(EditText field, double defaultVal, double min, double max) {
        try {
            double val = Double.parseDouble(field.getText().toString().trim());
            return Math.max(min, Math.min(max, val));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // --- Status panel ---

    protected void updateModelStatusPanel() {
        int active = countActiveBeacons();
        String uid = closestBeaconUid != null ? shortUid(closestBeaconUid) : "---";

        tvClosest.setText("Closest: " + uid);
        tvState.setText("State: " + modelState);
        tvBeaconCount.setText("Beacons: " + active + " / 3 required");

        updatePhaseStatusLabels();
    }

    // --- Positioning ---

    protected double[] getBeaconPosition(String compositeId, Beacon beacon) {
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
        if (isCalibrating()) {
            onBeaconDuringCalibration(beacon);
            return;
        }
        if (!isRecording) return;

        totalScanResults++;

        if (shouldSkipBeacon(beacon)) return;

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
        Double filteredRssi = processBeacon(beacon, compositeId);

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
        reading.put("distance_m", Math.round(distance * 100.0) / 100.0);
        double[] pos = estimatedPosition;
        reading.put("est_x", pos != null ? Math.round(pos[0] * 100.0) / 100.0 : "");
        reading.put("est_y", pos != null ? Math.round(pos[1] * 100.0) / 100.0 : "");
        reading.put("model_phase", getPhaseLabel());
        reading.putAll(buildPhaseReadingFields(compositeId));
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

    // --- Utilities ---

    protected static String shortUid(String compositeId) {
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

    protected abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
        @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
    }
}
