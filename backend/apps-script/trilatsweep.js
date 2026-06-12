// ═══════════════════════════════════════════════════════════════
// trilatsweep.js — TX x N trilateration sweep (live, from the Sheets).
//
// Replays the raw RSSI recorded at fixed standing points through the corrected
// LSQ positioning model — linear init + damped Gauss-Newton (Levenberg-
// Marquardt), the same math as the Android P2TrilaterationJavaSolver — for
// every combination of a single GLOBAL TX power and path-loss exponent N.
//
// TX and N are NOT capture settings, they are post-processing math
// (distance = 10^((TX - rssi)/(10*N))), so the raw RSSI at a spot is valid
// for every combo and we recompute distance offline for each one.
//
// Ground truth per session:
//   beacon (x,y)      <- 'beacon_config' JSON (needs >= 3 anchors)
//   true phone (x,y)  <- 'notes', e.g. "Z1 | (0.4, 0.4)"
// Sessions without a coordinate in notes (walks / unlabeled) are skipped.
//
// Metric: per static session, median raw RSSI per beacon (steady-state
// estimate), one solve, Euclidean error vs the true (x,y). Aggregated as
// mean/median/max error per (TX, N) across points.
//
// Called from _computeAnalytics() with the already-read sheet data (no extra
// sheet reads). Shares helpers (_str, _num, _mean, _percentile, _round) and
// the RC/SC column maps with analytics.js (GAS global scope).
// Project convention: var, not const/let.
// ═══════════════════════════════════════════════════════════════

var SWEEP_TX_GRID = [-53, -59, -65];
var SWEEP_N_GRID  = [2.0, 2.5, 3.0];
var SWEEP_ROOM_DIAG_M = 4.24; // hypot(3,3) — worst-case error scale, 3x3 m room

var SWEEP_COORD_RE = /\(\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)/;
var SWEEP_LABEL_RE = /^\s*([A-Za-z]\w*)/;

function _computeSweep(sData, rData) {
  // ── fixed-point sessions (ground truth in notes + >=3 anchors) ──
  var fixed = [];
  for (var i = 0; i < sData.length; i++) {
    var row = sData[i];
    var notes = _str(row[SC.notes]);
    var cm = notes.match(SWEEP_COORD_RE);
    if (!cm) continue;
    var beacons = _sweepParseBeacons(_str(row[SC.beacon_config]));
    if (Object.keys(beacons).length < 3) continue;
    var lm = notes.match(SWEEP_LABEL_RE);
    fixed.push({ sid: _str(row[SC.session_id]), label: lm ? lm[1] : '?',
                 gx: parseFloat(cm[1]), gy: parseFloat(cm[2]), beacons: beacons });
  }
  if (!fixed.length) {
    return { fixed_sessions: 0, points: [], tx_grid: SWEEP_TX_GRID, n_grid: SWEEP_N_GRID,
             pivot_mean: null, ranked: [], by_point: [], best: null, room_diag_m: SWEEP_ROOM_DIAG_M };
  }

  // ── median raw RSSI per (session, "major,minor") ──
  var acc = {}; // sid -> bid -> [rssi]
  for (var j = 0; j < rData.length; j++) {
    var r = rData[j];
    var rssi = _num(r[RC.rssi_raw]);
    if (rssi == null) continue;
    var sid = _str(r[RC.session_id]);
    var bid = _str(r[RC.major]) + ',' + _str(r[RC.minor]);
    if (!acc[sid]) acc[sid] = {};
    if (!acc[sid][bid]) acc[sid][bid] = [];
    acc[sid][bid].push(rssi);
  }

  // ── sweep: one solve per (session, TX, N) ──
  var results = []; // {sid, label, tx, n, err}
  var usedSids = {};
  for (var f = 0; f < fixed.length; f++) {
    var s = fixed[f];
    var sm = acc[s.sid];
    if (!sm) continue;
    var common = [];
    for (var bid2 in s.beacons) if (sm[bid2]) common.push(bid2);
    if (common.length < 3) continue;
    usedSids[s.sid] = 1;
    var P = common.map(function (b) { return s.beacons[b]; });
    var med = common.map(function (b) {
      return _percentile(sm[b].slice().sort(_numCmp), 50);
    });
    for (var ti = 0; ti < SWEEP_TX_GRID.length; ti++) {
      for (var ni = 0; ni < SWEEP_N_GRID.length; ni++) {
        var tx = SWEEP_TX_GRID[ti], n = SWEEP_N_GRID[ni];
        var d = med.map(function (v) { return Math.pow(10, (tx - v) / (10 * n)); });
        var est = _sweepTrilaterate(P, d);
        var err = Math.sqrt((est[0] - s.gx) * (est[0] - s.gx) + (est[1] - s.gy) * (est[1] - s.gy));
        results.push({ sid: s.sid, label: s.label, tx: tx, n: n, err: err });
      }
    }
  }
  if (!results.length) {
    return { fixed_sessions: 0, points: [], tx_grid: SWEEP_TX_GRID, n_grid: SWEEP_N_GRID,
             pivot_mean: null, ranked: [], by_point: [], best: null, room_diag_m: SWEEP_ROOM_DIAG_M };
  }

  // ── aggregate per combo (ranked) + 3x3 pivot of mean error ──
  var combos = {};
  results.forEach(function (x) {
    var key = x.tx + '|' + x.n;
    if (!combos[key]) combos[key] = { tx: x.tx, n: x.n, errs: [] };
    combos[key].errs.push(x.err);
  });
  var ranked = [];
  for (var key in combos) {
    var c = combos[key];
    var sorted = c.errs.slice().sort(_numCmp);
    ranked.push({ tx: c.tx, n: c.n,
                  mean_err_m: _round(_mean(c.errs), 3),
                  median_err_m: _round(_percentile(sorted, 50), 3),
                  max_err_m: _round(sorted[sorted.length - 1], 3),
                  n_points: c.errs.length });
  }
  ranked.sort(function (a, b) { return a.mean_err_m - b.mean_err_m; });

  var pivot = SWEEP_TX_GRID.map(function (tx) {
    return SWEEP_N_GRID.map(function (n) {
      var c = combos[tx + '|' + n];
      return c ? _round(_mean(c.errs), 3) : null;
    });
  });

  // ── mean error per point (which spots are hardest) ──
  var byLabel = {};
  results.forEach(function (x) {
    if (!byLabel[x.label]) byLabel[x.label] = [];
    byLabel[x.label].push(x.err);
  });
  var byPoint = Object.keys(byLabel).map(function (l) {
    return { label: l, mean_err_m: _round(_mean(byLabel[l]), 3) };
  });
  byPoint.sort(function (a, b) { return a.mean_err_m - b.mean_err_m; });

  var best = ranked[0];
  return {
    fixed_sessions: Object.keys(usedSids).length,
    points: byPoint.map(function (p) { return p.label; }).sort(),
    tx_grid: SWEEP_TX_GRID, n_grid: SWEEP_N_GRID,
    pivot_mean: pivot, ranked: ranked, by_point: byPoint,
    best: { tx: best.tx, n: best.n, mean_err_m: best.mean_err_m, median_err_m: best.median_err_m },
    room_diag_m: SWEEP_ROOM_DIAG_M
  };
}

// beacon_config JSON -> {id: [x,y]}. '[]' or junk -> {}.
function _sweepParseBeacons(cfg) {
  var out = {};
  if (!cfg) return out;
  var arr;
  try { arr = JSON.parse(cfg); } catch (e) { return out; }
  if (!Array.isArray(arr)) return out;
  arr.forEach(function (b) {
    if (b && b.id != null && b.x != null && b.y != null) {
      out[String(b.id)] = [parseFloat(b.x), parseFloat(b.y)];
    }
  });
  return out;
}

// ── LSQ solver: linear init + damped Gauss-Newton (LM) ─────────
// min sum((|p - Pi| - ri)^2). LM damping keeps it stable when the distance
// circles don't intersect (over-estimated radii). All algebra is 2x2.

function _sweepSolve2x2(a11, a12, a21, a22, b1, b2) {
  var det = a11 * a22 - a12 * a21;
  if (Math.abs(det) < 1e-12) return null;
  return [(b1 * a22 - b2 * a12) / det, (a11 * b2 - a21 * b1) / det];
}

// residuals g_i = |p - Pi| - r_i, plus the deltas/norms reused by the Jacobian
function _sweepResid(p, P, r) {
  var g = [], d = [], nrm = [];
  for (var i = 0; i < P.length; i++) {
    var dx = p[0] - P[i][0], dy = p[1] - P[i][1];
    var nv = Math.sqrt(dx * dx + dy * dy);
    if (nv < 1e-9) nv = 1e-9;
    d.push([dx, dy]); nrm.push(nv); g.push(nv - r[i]);
  }
  return { g: g, d: d, nrm: nrm };
}

function _sweepCost(g) {
  var c = 0;
  for (var i = 0; i < g.length; i++) c += g[i] * g[i];
  return c;
}

function _sweepTrilaterate(P, r) {
  var k = P.length;
  // linear init: subtract the last anchor's circle equation, solve the
  // (k-1)x2 system via normal equations (AtA p = Atb)
  var xn = P[k - 1][0], yn = P[k - 1][1], rn = r[k - 1];
  var a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0;
  for (var i = 0; i < k - 1; i++) {
    var ax = 2 * (xn - P[i][0]), ay = 2 * (yn - P[i][1]);
    var bi = r[i] * r[i] - rn * rn + (xn * xn - P[i][0] * P[i][0]) + (yn * yn - P[i][1] * P[i][1]);
    a11 += ax * ax; a12 += ax * ay; a22 += ay * ay;
    b1 += ax * bi; b2 += ay * bi;
  }
  var p = _sweepSolve2x2(a11, a12, a12, a22, b1, b2) || [xn, yn];

  var R = _sweepResid(p, P, r);
  var cost = _sweepCost(R.g);
  var lam = 1e-2;
  for (var it = 0; it < 60; it++) {
    // J = d/nrm (k x 2): build JtJ (2x2) and Jtg (2) directly
    var j11 = 0, j12 = 0, j22 = 0, g1 = 0, g2 = 0;
    for (var m = 0; m < k; m++) {
      var jx = R.d[m][0] / R.nrm[m], jy = R.d[m][1] / R.nrm[m];
      j11 += jx * jx; j12 += jx * jy; j22 += jy * jy;
      g1 += jx * R.g[m]; g2 += jy * R.g[m];
    }
    // damping on the diagonal: JtJ + lam * diag(diag(JtJ) + eps)
    var step = _sweepSolve2x2(j11 + lam * (j11 + 1e-9), j12,
                              j12, j22 + lam * (j22 + 1e-9), g1, g2);
    if (!step) break;
    var pNew = [p[0] - step[0], p[1] - step[1]];
    var Rn = _sweepResid(pNew, P, r);
    var costNew = _sweepCost(Rn.g);
    if (costNew < cost) {                       // accept, less damping
      p = pNew; R = Rn; cost = costNew;
      lam = Math.max(lam * 0.5, 1e-9);
      if (Math.sqrt(step[0] * step[0] + step[1] * step[1]) < 1e-7) break;
    } else {                                    // reject, more damping
      lam = Math.min(lam * 4, 1e9);
      if (lam >= 1e9) break;
    }
  }
  return p;
}
