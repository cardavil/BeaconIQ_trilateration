// ═══════════════════════════════════════════════════════════════
// rawscans.js — Write raw BLE scan results
// ═══════════════════════════════════════════════════════════════

function writeRawScans(sessionId, rawScans) {
  var sh = _ensureSheet(_p().RAWSCANS_SHEET_ID, TAB_RAWSCANS, HEADERS_RAWSCANS);

  var rows = [];
  for (var i = 0; i < rawScans.length; i++) {
    var s = rawScans[i];
    rows.push([
      sessionId,
      _v(s.timestamp_ms),
      _v(s.device_address),
      _v(s.company_id),
      _v(s.rssi),
      s.was_ibeacon === true ? 'TRUE' : 'FALSE',
      _v(s.data_hex),
      _v(s.reject_reason)
    ]);
  }

  if (rows.length > 0) {
    var startRow = sh.getLastRow() + 1;
    sh.getRange(startRow, 1, rows.length, HEADERS_RAWSCANS.length)
      .setValues(rows);
  }

  Logger.log('[writeRawScans] Wrote ' + rows.length + ' rows for ' + sessionId);
}
