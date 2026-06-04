package beaconiq.positioning.phase2;

import android.content.SharedPreferences;

/**
 * Single source of truth for the Phase II / Explore positioning parameters.
 *
 * Owns the SharedPreferences keys, the default values, and the load/save and
 * input-clamping logic that used to be duplicated (and slightly inconsistent)
 * between ScanFragment and PhaseTwoTestFragment.
 *
 * Values start at the documented defaults and are mutated in place.
 */
public class P2ModelConfig {

    public static final String PREFS_NAME = "debug_panel";

    // --- SharedPreferences keys ---
    public static final String KEY_TX_POWER = "debug_default_tx_power";
    public static final String KEY_PATH_LOSS_N = "debug_path_loss_n";
    public static final String KEY_RSSI_THRESHOLD = "debug_rssi_threshold";
    public static final String KEY_KALMAN_Q = "debug_kalman_q";
    public static final String KEY_KALMAN_R = "debug_kalman_r";
    public static final String KEY_RSSI_BUFFER_SIZE = "debug_rssi_buffer_size";
    public static final String KEY_RSSI_TIME_WINDOW_MS = "debug_rssi_time_window_ms";
    public static final String KEY_SCALE_FACTOR = "debug_scale_factor";
    public static final String KEY_BEACON_TIMEOUT_MS = "debug_beacon_timeout_ms";
    public static final String KEY_EVAL_INTERVAL_MS = "debug_eval_interval_ms";
    // Dual-mode (V2)
    public static final String KEY_MODE = "debug_mode";
    public static final String KEY_WCL_G = "debug_wcl_g";
    public static final String KEY_HYSTERESIS_MARGIN_DB = "debug_hysteresis_margin_db";
    public static final String KEY_DWELL_MS = "debug_dwell_ms";
    public static final String KEY_CONFIDENCE_THRESHOLD = "debug_confidence_threshold";
    public static final String KEY_TRIGGER_COOLDOWN_MS = "debug_trigger_cooldown_ms";
    public static final String KEY_MIN_SAMPLES = "debug_min_samples";

    // --- Defaults (match CLAUDE.md "Key Defaults") ---
    public static final int DEF_TX_POWER = -59;
    public static final double DEF_PATH_LOSS_N = 2.0;
    public static final int DEF_RSSI_THRESHOLD = -100;
    public static final double DEF_KALMAN_Q = 0.05;
    public static final double DEF_KALMAN_R = 0.25;
    public static final int DEF_RSSI_BUFFER_SIZE = 20;
    public static final long DEF_RSSI_TIME_WINDOW_MS = 8000;
    public static final double DEF_SCALE_FACTOR = 1.0;
    public static final long DEF_BEACON_TIMEOUT_MS = 4000;
    public static final long DEF_EVAL_INTERVAL_MS = 3000;

    // --- Dual-mode defaults (V2) ---
    public static final int MODE_PROXIMITY = 0;
    public static final int MODE_TRILATERATION = 1;
    public static final int DEF_MODE = MODE_PROXIMITY;
    public static final double DEF_WCL_G = 2.0;
    public static final double DEF_HYSTERESIS_MARGIN_DB = 6.0;
    public static final long DEF_DWELL_MS = 1500;
    public static final double DEF_CONFIDENCE_THRESHOLD = 0.4;
    public static final long DEF_TRIGGER_COOLDOWN_MS = 8000;
    public static final int DEF_MIN_SAMPLES = 3;

    // --- Validation ranges (used when clamping user input) ---
    public static final int MIN_TX_POWER = -100, MAX_TX_POWER = 0;
    public static final double MIN_PATH_LOSS_N = 1.0, MAX_PATH_LOSS_N = 6.0;
    public static final int MIN_RSSI_THRESHOLD = -120, MAX_RSSI_THRESHOLD = -20;
    public static final double MIN_KALMAN_Q = 0.001, MAX_KALMAN_Q = 1.0;
    public static final double MIN_KALMAN_R = 0.001, MAX_KALMAN_R = 5.0;
    public static final int MIN_RSSI_BUFFER_SIZE = 1, MAX_RSSI_BUFFER_SIZE = 100;
    public static final int MIN_RSSI_TIME_WINDOW_MS = 500, MAX_RSSI_TIME_WINDOW_MS = 30000;
    public static final double MIN_SCALE_FACTOR = 0.1, MAX_SCALE_FACTOR = 50.0;
    public static final int MIN_BEACON_TIMEOUT_MS = 1000, MAX_BEACON_TIMEOUT_MS = 30000;
    public static final int MIN_EVAL_INTERVAL_MS = 500, MAX_EVAL_INTERVAL_MS = 30000;
    public static final double MIN_WCL_G = 1.0, MAX_WCL_G = 6.0;
    public static final double MIN_HYSTERESIS_MARGIN_DB = 0.0, MAX_HYSTERESIS_MARGIN_DB = 20.0;
    public static final int MIN_DWELL_MS = 0, MAX_DWELL_MS = 10000;
    public static final double MIN_CONFIDENCE_THRESHOLD = 0.0, MAX_CONFIDENCE_THRESHOLD = 1.0;
    public static final int MIN_TRIGGER_COOLDOWN_MS = 0, MAX_TRIGGER_COOLDOWN_MS = 60000;
    public static final int MIN_MIN_SAMPLES = 1, MAX_MIN_SAMPLES = 50;

    // --- Current values ---
    public int txPower = DEF_TX_POWER;
    public double pathLossN = DEF_PATH_LOSS_N;
    public int rssiThreshold = DEF_RSSI_THRESHOLD;
    public double kalmanQ = DEF_KALMAN_Q;
    public double kalmanR = DEF_KALMAN_R;
    public int rssiBufferSize = DEF_RSSI_BUFFER_SIZE;
    public long rssiTimeWindowMs = DEF_RSSI_TIME_WINDOW_MS;
    public double scaleFactor = DEF_SCALE_FACTOR;
    public long beaconTimeoutMs = DEF_BEACON_TIMEOUT_MS;
    public long evalIntervalMs = DEF_EVAL_INTERVAL_MS;
    // Dual-mode (V2)
    public int mode = DEF_MODE;
    public double wclG = DEF_WCL_G;
    public double hysteresisMarginDb = DEF_HYSTERESIS_MARGIN_DB;
    public long dwellMs = DEF_DWELL_MS;
    public double confidenceThreshold = DEF_CONFIDENCE_THRESHOLD;
    public long triggerCooldownMs = DEF_TRIGGER_COOLDOWN_MS;
    public int minSamples = DEF_MIN_SAMPLES;

    /** Reads all parameters from prefs, falling back to the documented defaults. */
    public static P2ModelConfig load(SharedPreferences p) {
        P2ModelConfig c = new P2ModelConfig();
        c.txPower = p.getInt(KEY_TX_POWER, DEF_TX_POWER);
        c.pathLossN = p.getFloat(KEY_PATH_LOSS_N, (float) DEF_PATH_LOSS_N);
        c.rssiThreshold = p.getInt(KEY_RSSI_THRESHOLD, DEF_RSSI_THRESHOLD);
        c.kalmanQ = p.getFloat(KEY_KALMAN_Q, (float) DEF_KALMAN_Q);
        c.kalmanR = p.getFloat(KEY_KALMAN_R, (float) DEF_KALMAN_R);
        c.rssiBufferSize = p.getInt(KEY_RSSI_BUFFER_SIZE, DEF_RSSI_BUFFER_SIZE);
        c.rssiTimeWindowMs = p.getInt(KEY_RSSI_TIME_WINDOW_MS, (int) DEF_RSSI_TIME_WINDOW_MS);
        c.scaleFactor = p.getFloat(KEY_SCALE_FACTOR, (float) DEF_SCALE_FACTOR);
        c.beaconTimeoutMs = p.getInt(KEY_BEACON_TIMEOUT_MS, (int) DEF_BEACON_TIMEOUT_MS);
        c.evalIntervalMs = p.getInt(KEY_EVAL_INTERVAL_MS, (int) DEF_EVAL_INTERVAL_MS);
        c.mode = p.getInt(KEY_MODE, DEF_MODE);
        c.wclG = p.getFloat(KEY_WCL_G, (float) DEF_WCL_G);
        c.hysteresisMarginDb = p.getFloat(KEY_HYSTERESIS_MARGIN_DB, (float) DEF_HYSTERESIS_MARGIN_DB);
        c.dwellMs = p.getInt(KEY_DWELL_MS, (int) DEF_DWELL_MS);
        c.confidenceThreshold = p.getFloat(KEY_CONFIDENCE_THRESHOLD, (float) DEF_CONFIDENCE_THRESHOLD);
        c.triggerCooldownMs = p.getInt(KEY_TRIGGER_COOLDOWN_MS, (int) DEF_TRIGGER_COOLDOWN_MS);
        c.minSamples = p.getInt(KEY_MIN_SAMPLES, DEF_MIN_SAMPLES);
        return c;
    }

    /** Persists all parameters to prefs. */
    public void save(SharedPreferences p) {
        p.edit()
                .putInt(KEY_TX_POWER, txPower)
                .putFloat(KEY_PATH_LOSS_N, (float) pathLossN)
                .putInt(KEY_RSSI_THRESHOLD, rssiThreshold)
                .putFloat(KEY_KALMAN_Q, (float) kalmanQ)
                .putFloat(KEY_KALMAN_R, (float) kalmanR)
                .putInt(KEY_RSSI_BUFFER_SIZE, rssiBufferSize)
                .putInt(KEY_RSSI_TIME_WINDOW_MS, (int) rssiTimeWindowMs)
                .putFloat(KEY_SCALE_FACTOR, (float) scaleFactor)
                .putInt(KEY_BEACON_TIMEOUT_MS, (int) beaconTimeoutMs)
                .putInt(KEY_EVAL_INTERVAL_MS, (int) evalIntervalMs)
                .putInt(KEY_MODE, mode)
                .putFloat(KEY_WCL_G, (float) wclG)
                .putFloat(KEY_HYSTERESIS_MARGIN_DB, (float) hysteresisMarginDb)
                .putInt(KEY_DWELL_MS, (int) dwellMs)
                .putFloat(KEY_CONFIDENCE_THRESHOLD, (float) confidenceThreshold)
                .putInt(KEY_TRIGGER_COOLDOWN_MS, (int) triggerCooldownMs)
                .putInt(KEY_MIN_SAMPLES, minSamples)
                .apply();
    }

    /** Parses an int from user text, clamping to [min, max]; returns def on bad input. */
    public static int clampInt(String text, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(text.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Parses a double from user text, clamping to [min, max]; returns def on bad input. */
    public static double clampDouble(String text, double def, double min, double max) {
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(text.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
