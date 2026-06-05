// ═══════════════════════════════════════════════════════════════
// readings.js — Write iBeacon parsed readings
// ═══════════════════════════════════════════════════════════════

function writeReadings(sessionId, readings) {
  var sh = _ensureSheet(_p().READINGS_SHEET_ID, TAB_READINGS, HEADERS_READINGS);

  var rows = [];
  for (var i = 0; i < readings.length; i++) {
    var r = readings[i];
    rows.push([
      sessionId,
      _v(r.timestamp_ms),
      _v(r.beacon_id),
      _v(r.uuid),
      _v(r.major),
      _v(r.minor),
      _v(r.rssi_raw),
      _v(r.rssi_filtered),
      _v(r.tx_power_adv),
      _v(r.distance_m),
      _v(r.dist_no_kalman),
      _v(r.est_x),
      _v(r.est_y),
      _v(r.model_phase),
      _v(r.active_zone),
      _v(r.candidate_zone),
      _v(r.confidence),
      _v(r.margin_db),
      _v(r.ground_truth_zone)
    ]);
  }

  if (rows.length > 0) {
    var startRow = sh.getLastRow() + 1;
    sh.getRange(startRow, 1, rows.length, HEADERS_READINGS.length)
      .setValues(rows);
  }

  Logger.log('[writeReadings] Wrote ' + rows.length + ' rows for ' + sessionId);
}
