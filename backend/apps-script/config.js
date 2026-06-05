// ═══════════════════════════════════════════════════════════════
// config.js — Cached properties, timestamps, schema definitions
// ═══════════════════════════════════════════════════════════════

var _cachedProps = null;

function _p() {
  if (!_cachedProps)
    _cachedProps = PropertiesService.getScriptProperties().getProperties();
  return _cachedProps;
}

function _ts(fecha) {
  return Utilities.formatDate(
    fecha || new Date(),
    Session.getScriptTimeZone(),
    'yyyy-MM-dd HH:mm:ss'
  );
}

function _v(val, fallback) {
  if (fallback === undefined) fallback = '';
  return (val !== undefined && val !== null) ? val : fallback;
}

// ── Tab names (must match the actual sheet tab names) ──────────

var TAB_SESSIONS  = 'BeaconIQ_Sessions';
var TAB_READINGS  = 'BeaconIQ_Readings';
var TAB_RAWSCANS  = 'BeaconIQ_RawScans';

// ── Column headers (written on first use if sheet is empty) ────

var HEADERS_SESSIONS = [
  'session_id', 'timestamp_start', 'timestamp_end', 'analyst',
  'duration_sec', 'movement_mode', 'room', 'phone_position',
  'phone_model', 'android_version', 'txpower', 'path_loss_n',
  'rssi_threshold', 'kalman_q', 'kalman_r',
  'rssi_buffer_size', 'rssi_time_window_ms', 'scale_factor',
  'beacon_timeout_ms', 'eval_interval_ms', 'solver_type',
  'beacon_config',
  'model_phase', 'beacons_detected', 'total_scan_results',
  'ibeacon_hits', 'rejected_count',
  'mode', 'wcl_g', 'hysteresis_margin_db', 'dwell_ms',
  'confidence_threshold', 'trigger_cooldown_ms', 'min_samples',
  'notes',
  'pos_kalman_q', 'pos_kalman_r'
];

var HEADERS_READINGS = [
  'session_id', 'timestamp_ms', 'beacon_id', 'uuid',
  'major', 'minor', 'rssi_raw', 'rssi_filtered',
  'tx_power_adv', 'distance_m', 'dist_no_kalman',
  'est_x', 'est_y', 'model_phase',
  'active_zone', 'candidate_zone', 'confidence', 'margin_db', 'ground_truth_zone'
];

var HEADERS_RAWSCANS = [
  'session_id', 'timestamp_ms', 'device_address', 'company_id',
  'rssi', 'was_ibeacon', 'data_hex', 'reject_reason'
];

// ── Sheet helper: open sheet, ensure headers exist ─────────────

function _ensureSheet(spreadsheetId, tabName, headers) {
  var ss = SpreadsheetApp.openById(spreadsheetId);
  var sh = ss.getSheetByName(tabName);
  if (!sh) throw new Error('Tab "' + tabName + '" not found in spreadsheet');

  if (sh.getLastRow() === 0) {
    sh.appendRow(headers);
    sh.getRange(1, 1, 1, headers.length)
      .setFontWeight('bold')
      .setBackground('#f3f3f3');
    SpreadsheetApp.flush();
  } else {
    // Reconcile: if the schema grew (new trailing columns appended to HEADERS),
    // write the missing header cells without touching existing data. One-time
    // auto-migration so the app can write the new columns immediately.
    var lastCol = sh.getLastColumn();
    if (lastCol < headers.length) {
      var missing = headers.slice(lastCol);
      sh.getRange(1, lastCol + 1, 1, missing.length)
        .setValues([missing])
        .setFontWeight('bold')
        .setBackground('#f3f3f3');
      SpreadsheetApp.flush();
    }
  }

  return sh;
}
