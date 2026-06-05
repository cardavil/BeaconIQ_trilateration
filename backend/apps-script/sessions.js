// ═══════════════════════════════════════════════════════════════
// sessions.js — Session recording orchestrator
// ═══════════════════════════════════════════════════════════════

function recordSession(data) {
  var session  = data.session;
  var readings = Array.isArray(data.readings)  ? data.readings  : [];
  var rawScans = Array.isArray(data.raw_scans) ? data.raw_scans : [];

  if (!session || typeof session !== 'object')
    throw new Error('Missing session object in payload');

  var lock = LockService.getScriptLock();
  lock.waitLock(10000);

  var sessionId;
  try {
    var sh = _ensureSheet(_p().SESSIONS_SHEET_ID, TAB_SESSIONS, HEADERS_SESSIONS);
    sessionId = _nextSessionId(sh);

    var row = [
      sessionId,
      _v(session.timestamp_start, _ts()),
      _v(session.timestamp_end, _ts()),
      _v(session.analyst),
      _v(session.duration_sec, 0),
      _v(session.movement_mode),
      _v(session.room),
      _v(session.phone_position),
      _v(session.phone_model),
      _v(session.android_version),
      _v(session.txpower),
      _v(session.path_loss_n),
      _v(session.rssi_threshold),
      _v(session.kalman_q),
      _v(session.kalman_r),
      _v(session.rssi_buffer_size),
      _v(session.rssi_time_window_ms),
      _v(session.scale_factor),
      _v(session.beacon_timeout_ms),
      _v(session.eval_interval_ms),
      _v(session.solver_type),
      _v(session.beacon_config),
      _v(session.model_phase),
      _v(session.beacons_detected, 0),
      _v(session.total_scan_results, 0),
      _v(session.ibeacon_hits, 0),
      _v(session.rejected_count, 0),
      _v(session.mode),
      _v(session.wcl_g),
      _v(session.hysteresis_margin_db),
      _v(session.dwell_ms),
      _v(session.confidence_threshold),
      _v(session.trigger_cooldown_ms),
      _v(session.min_samples),
      _v(session.notes),
      _v(session.pos_kalman_q),
      _v(session.pos_kalman_r)
    ];

    sh.appendRow(row);

    if (readings.length > 0) {
      writeReadings(sessionId, readings);
    }

    if (rawScans.length > 0) {
      writeRawScans(sessionId, rawScans);
    }
  } finally {
    lock.releaseLock();
  }

  Logger.log('[recordSession] OK — ' + sessionId +
    ' | readings=' + readings.length +
    ' | rawScans=' + rawScans.length);

  return {
    status: 'ok',
    session_id: sessionId,
    readings_count: readings.length,
    raw_scans_count: rawScans.length
  };
}

function _nextSessionId(sh) {
  var last = sh.getLastRow();
  if (last < 2) return 'BIQ-0001';
  var prev = sh.getRange(last, 1).getValue();
  var num  = Number(String(prev).replace(/\D/g, '')) || 0;
  return 'BIQ-' + String(num + 1).padStart(4, '0');
}
