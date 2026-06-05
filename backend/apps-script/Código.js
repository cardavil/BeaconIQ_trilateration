// ═══════════════════════════════════════════════════════════════
// BeaconIQ Test Console — Backend
// Código.gs — Entry Point
// ═══════════════════════════════════════════════════════════════

function doPost(e) {
  try {
    var data = JSON.parse(e.postData.contents);

    if (!validateToken(data.auth)) {
      return _jsonResponse({ status: 'error', message: 'Unauthorized' });
    }

    var action = data.action || '';

    if (action === 'record_session') {
      var result = recordSession(data);
      return _jsonResponse(result);
    }

    if (action === 'list_sessions') {
      var sessions = api_getSessions();
      return _jsonResponse({ sessions: sessions });
    }

    return _jsonResponse({ status: 'error', message: 'Unknown action: ' + action });

  } catch (err) {
    Logger.log('[doPost] ERROR: ' + err.message + '\n' + (err.stack || ''));
    return _jsonResponse({ status: 'error', message: err.message });
  }
}

function doGet() {
  return HtmlService
    .createTemplateFromFile('index')
    .evaluate()
    .setTitle('BeaconIQ Console')
    .setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
}

function include(file) {
  return HtmlService.createHtmlOutputFromFile(file).getContent();
}

function _jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

// ── Web UI API ────────────────────────────────────────────────

function api_getSessions() {
  var sh = _ensureSheet(_p().SESSIONS_SHEET_ID, TAB_SESSIONS, HEADERS_SESSIONS);
  var last = sh.getLastRow();
  if (last < 2) return [];

  var data = sh.getRange(2, 1, last - 1, HEADERS_SESSIONS.length).getValues();

  var rows = [];
  for (var i = data.length - 1; i >= 0; i--) {
    var r = data[i];
    rows.push({
      session_id:       String(r[0]),
      timestamp_start:  String(r[1] || ''),
      analyst:          r[3] || '',
      duration_sec:     r[4] || 0,
      room:             r[6] || '',
      beacons_detected: r[23] || 0,
      ibeacon_hits:     r[25] || 0
    });
  }
  return rows;
}

function api_getSheetLinks() {
  var props = _p();
  var ids = {
    sessions: props.SESSIONS_SHEET_ID  || '',
    readings: props.READINGS_SHEET_ID  || '',
    rawscans: props.RAWSCANS_SHEET_ID  || ''
  };
  var links = {};
  for (var key in ids) {
    links[key] = ids[key]
      ? 'https://docs.google.com/spreadsheets/d/' + ids[key] + '/edit'
      : '';
  }
  return links;
}
