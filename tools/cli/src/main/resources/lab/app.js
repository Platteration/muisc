// Muisc Transition Lab — the page. Vanilla JS, no external resources (it must work offline and under
// "script-src 'self'": no inline handlers, no eval). Everything user- or file-supplied is inserted as text.
'use strict';

(() => {
  // ---- tiny helpers ------------------------------------------------------------------------------------------------
  const $ = (id) => document.getElementById(id);

  /** el('div', {class: 'x', text: 'hi', onclick: fn, dataset: {...}, attrs: {...}}, child, ...) */
  function el(tag, props, ...children) {
    const node = document.createElement(tag);
    if (props) {
      for (const [k, v] of Object.entries(props)) {
        if (v === undefined || v === null) continue;
        if (k === 'class') node.className = v;
        else if (k === 'text') node.textContent = v;
        else if (k === 'attrs') for (const [a, av] of Object.entries(v)) { if (av !== undefined && av !== null && av !== false) node.setAttribute(a, av === true ? '' : String(av)); }
        else if (k === 'dataset') Object.assign(node.dataset, v);
        else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
        else node[k] = v;
      }
    }
    for (const c of children) {
      if (c === null || c === undefined || c === false) continue;
      node.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return node;
  }

  const clear = (node) => { while (node.firstChild) node.removeChild(node.firstChild); return node; };
  const fmt = (v, d = 2) => (v === null || v === undefined || Number.isNaN(v)) ? '—' : Number(v).toFixed(d);
  const fmtTime = (s) => {
    if (!Number.isFinite(s)) return '0:00.0';
    const m = Math.floor(s / 60);
    const r = s - m * 60;
    return `${m}:${r < 10 ? '0' : ''}${r.toFixed(1)}`;
  };
  const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  function debounce(fn, ms) {
    let t = 0;
    return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
  }

  function setStatus(msg, isError = false) {
    const s = $('status');
    s.textContent = msg || '';
    s.classList.toggle('error', !!isError);
    s.setAttribute('role', isError ? 'alert' : 'status');
  }

  /** Runs an async handler and reports any failure in the status line instead of losing it in the console. */
  function guard(fn) {
    return async (...args) => {
      try {
        return await fn(...args);
      } catch (e) {
        setStatus(e && e.message ? e.message : String(e), true);
        return undefined;
      }
    };
  }

  async function api(method, path, body) {
    const opt = { method, headers: { Accept: 'application/json' } };
    if (body !== undefined) {
      opt.headers['Content-Type'] = 'application/json';
      opt.body = JSON.stringify(body);
    }
    const r = await fetch(path, opt);
    let data = null;
    try { data = await r.json(); } catch (_) { data = null; }
    if (!r.ok) throw new Error((data && data.error) || `${r.status} ${r.statusText}`);
    return data;
  }

  // ---- state -------------------------------------------------------------------------------------------------------
  const state = {
    tracks: [],
    strategies: [],
    strategyById: new Map(),
    modifiers: [],
    presets: [],
    styles: [],
    recipes: [],
    plan: null,
    selected: null,          // strategy id in the Design tab
    candidate: null,         // the ranked candidate for `selected`, when there is one
    slots: [],               // {name, result, blind}
    current: -1,
    laneOff: new Set(),
    busy: false,
    recipeVars: [],
    blind: null,             // {blindId, labels}
    sweep: null,             // last sweep result
  };

  const trackA = () => $('track-a').value;
  const trackB = () => $('track-b').value;
  const styleId = () => $('style').value || null;
  const contextSec = () => {
    const v = parseFloat($('context-sec').value);
    return Number.isFinite(v) ? Math.min(60, Math.max(0, v)) : 8;
  };

  // ---- jobs ----------------------------------------------------------------------------------------------------------
  function setBusy(b) {
    state.busy = b;
    for (const id of ['render', 'recipe-render', 'blind-start', 'sweep-run']) $(id).disabled = b;
  }

  /** Starts a job (the POST that returns {job}) and polls it to the end; returns the job's result. */
  async function runJob(start, label) {
    setBusy(true);
    const box = $('job');
    box.hidden = false;
    $('job-progress').value = 0;
    $('job-msg').textContent = label;
    try {
      const { job } = await start;
      let j = job;
      for (;;) {
        $('job-progress').value = j.progress || 0;
        $('job-msg').textContent = `${label}: ${j.message}`;
        if (j.status === 'done') return j.result;
        if (j.status === 'failed') throw new Error(j.error || 'the job failed');
        await sleep(200);
        j = await api('GET', `/api/jobs/${encodeURIComponent(job.id)}`);
      }
    } finally {
      box.hidden = true;
      setBusy(false);
    }
  }

  // ---- tracks & pair -----------------------------------------------------------------------------------------------
  function fillTrackSelects(keepA, keepB) {
    for (const [id, keep] of [['track-a', keepA], ['track-b', keepB]]) {
      const sel = clear($(id));
      for (const t of state.tracks) {
        sel.append(el('option', { value: t.id, text: `${t.name} — ${fmt(t.bpm, 1)} BPM, ${t.key} (${t.camelot})` }));
      }
      if (keep && state.tracks.some((t) => t.id === keep)) sel.value = keep;
    }
    if (!keepB && state.tracks.length > 1 && $('track-b').value === $('track-a').value) $('track-b').value = state.tracks[1].id;
  }

  async function loadTracks(keepA, keepB) {
    const r = await api('GET', '/api/tracks');
    state.tracks = r.tracks;
    fillTrackSelects(keepA, keepB);
    if (!state.tracks.length) setStatus('No tracks yet: add a file or folder above, or start the Lab with --fixtures.');
  }

  function stat(k, v, title) {
    return el('div', { class: 'stat', title }, el('span', { class: 'k', text: k }), el('span', { class: 'v', text: v }));
  }

  function renderPair(p) {
    const box = clear($('pair-summary'));
    if (!p) return;
    const a = p.a;
    const b = p.b;
    box.append(
      stat('Tempo', `${fmt(a.bpm, 1)} → ${fmt(b.bpm, 1)} BPM`, 'Beats per minute of A and B'),
      stat('Stretch', `${fmt(p.stretchPercent, 1)} %${p.tempoRelation !== 'SAME' ? ` (${p.tempoRelation.toLowerCase()} time)` : ''}`, 'Tempo change needed to beat-match'),
      stat('Key', `${a.key} (${a.camelot}) → ${b.key} (${b.camelot})`),
      stat('Camelot distance', `${p.camelotDistance}${p.pitchShiftSemitones ? ` → ${p.camelotDistanceAfterShift} after ${p.pitchShiftSemitones > 0 ? '+' : ''}${p.pitchShiftSemitones} st` : ''}`),
      stat('Loudness', `${fmt(a.lufs, 1)} / ${fmt(b.lufs, 1)} LUFS (Δ ${fmt(p.loudnessDeltaLu, 1)} LU)`),
      stat('Edges', `${a.edge.toLowerCase().replace(/_/g, ' ')} → ${b.edge.toLowerCase().replace(/_/g, ' ')}`, `A outro ${p.outroBeats} beats, B intro ${p.introBeats} beats`),
      stat('Grid confidence', `${fmt(p.gridConfidenceA, 2)} / ${fmt(p.gridConfidenceB, 2)}`),
      stat('Rating context', p.bucket, 'Ratings and votes are learned per context'),
    );
  }

  // ---- planning & candidates ---------------------------------------------------------------------------------------
  async function replan() {
    const a = trackA();
    const b = trackB();
    if (!a || !b) return;
    const plan = await api('POST', '/api/plan', { a, b, style: styleId() });
    state.plan = plan;
    renderPair(plan.pair);
    renderCandidates();
    const keep = state.selected && (state.strategyById.has(state.selected)) ? state.selected : null;
    selectStrategy(keep || (plan.candidates[0] && plan.candidates[0].strategy));
    fillBlindDefaults();
    if (plan.pin) setStatus(`Pin for this pair: ${plan.pin.reason}`);
  }

  function renderCandidates() {
    const list = clear($('candidates'));
    const plan = state.plan;
    if (!plan) return;
    for (const c of plan.candidates) {
      const badges = el('span', { class: 'badges' });
      if (c.pinned) badges.append(el('span', { class: 'badge', text: 'pinned' }));
      if (c.recipe) badges.append(el('span', { class: 'badge', text: 'recipe' }));
      if (c.learned !== undefined && Math.abs(c.learned - 1) > 1e-9) badges.append(el('span', { class: 'badge', text: `learned ×${fmt(c.learned, 2)}` }));
      for (const m of c.modifiers) badges.append(el('span', { class: 'badge', text: `+${m}` }));
      if (c.expectedSec) badges.append(el('span', { class: 'badge', text: `≈${fmt(c.expectedSec, 1)} s` }));
      const btn = el('button', {
        type: 'button',
        attrs: { 'aria-pressed': c.strategy === state.selected ? 'true' : 'false' },
        onclick: () => selectStrategy(c.strategy),
      },
      el('span', { class: 'rank', text: `${c.rank}.` }),
      el('span', { class: 'name' }, c.displayName, ' ', el('span', { class: 'id', text: c.strategy })),
      el('span', { class: 'score', text: fmt(c.score, 3) }),
      badges);
      const why = el('details', null, el('summary', { text: 'Why' }));
      if (c.formula) why.append(el('p', { class: 'small', text: c.formula }));
      if (c.subScores) {
        const subs = el('div', { class: 'subscores' });
        for (const [k, v] of Object.entries(c.subScores)) {
          const bar = el('span');
          bar.style.width = `${Math.round(Math.max(0, Math.min(1, v)) * 100)}%`;
          subs.append(el('div', { class: 'sub' }, `${k} ${fmt(v, 2)}`, el('div', { class: 'bar', attrs: { 'aria-hidden': 'true' } }, bar)));
        }
        why.append(subs);
      }
      const lines = [...(c.reasons || []), ...(c.notes || [])];
      for (const n of [c.learnedNote, c.presetNote, c.pinNote]) if (n) lines.push(n);
      if (lines.length) why.append(el('ul', null, ...lines.map((r) => el('li', { text: r }))));
      if (c.blockers && c.blockers.length) why.append(el('p', { class: 'err-text', text: `Blockers: ${c.blockers.join('; ')}` }));
      list.append(el('li', { class: `cand${c.strategy === state.selected ? ' selected' : ''}`, dataset: { id: c.strategy } }, btn, why));
    }
    const sk = clear($('skipped'));
    $('skipped-count').textContent = String(plan.skipped.length);
    for (const s of plan.skipped) {
      sk.append(el('li', null,
        el('strong', { text: s.strategy }), ` — ${s.reason}${s.blockers.length ? `: ${s.blockers.join('; ')}` : ''} `,
        el('button', { type: 'button', class: 'quiet', text: 'Try anyway', onclick: () => selectStrategy(s.strategy) })));
    }
  }

  function markSelected() {
    for (const li of $('candidates').children) {
      const on = li.dataset.id === state.selected;
      li.classList.toggle('selected', on);
      const b = li.querySelector('button');
      if (b) b.setAttribute('aria-pressed', on ? 'true' : 'false');
    }
  }

  function selectStrategy(id) {
    if (!id) return;
    const s = state.strategyById.get(id);
    if (!s) { setStatus(`Unknown strategy ${id}`, true); return; }
    state.selected = id;
    state.candidate = state.plan ? state.plan.candidates.find((c) => c.strategy === id) || null : null;
    $('strategy-title').textContent = `${s.displayName} (${s.id})`;
    $('strategy-desc').textContent = s.description + (state.candidate ? '' : ' — not ranked for this pair; it will be rendered anyway.');
    fillPresetSelect();
    const values = state.candidate ? state.candidate.params : {};
    buildParams($('params'), s.params, values);
    const auto = state.candidate ? state.candidate.modifiers : [];
    $('mod-auto').textContent = `(${auto.length ? auto.join(', ') : 'none'})`;
    for (const cb of $('mod-list').querySelectorAll('input')) cb.checked = auto.includes(cb.value);
    markSelected();
    fillSweepParams();
  }

  // ---- parameter controls ------------------------------------------------------------------------------------------
  let paramSeq = 0;

  /** Controls for ParamSpecs: slider + number for double/int, checkbox for bool, select for choice. */
  function buildParams(form, specs, values, onChange) {
    clear(form);
    if (!specs.length) { form.append(el('p', { class: 'muted small', text: 'This strategy has no parameters.' })); return; }
    for (const p of specs) {
      const uid = `p${++paramSeq}`;
      const docId = `${uid}-doc`;
      const start = values && values[p.id] !== undefined ? values[p.id] : p.default;
      const row = el('div', { class: 'param', dataset: { id: p.id, type: p.type, def: p.default } });
      const label = el('label', { class: 'plabel', attrs: { for: uid } }, p.label || p.id, p.unit ? el('span', { class: 'unit', text: ` ${p.unit}` }) : null);
      const changed = () => {
        row.classList.toggle('changed', String(read(row)) !== String(normal(p, p.default)));
        if (onChange) onChange();
      };
      if (p.type === 'double' || p.type === 'int') {
        const step = p.type === 'int' ? 1 : (p.step || niceStep(p.min, p.max));
        const range = el('input', { type: 'range', min: p.min, max: p.max, step, value: start, attrs: { 'aria-label': `${p.label || p.id} slider`, 'aria-describedby': docId } });
        const num = el('input', { type: 'number', id: uid, min: p.min, max: p.max, step, value: start, attrs: { 'aria-describedby': docId } });
        range.addEventListener('input', () => { num.value = range.value; changed(); });
        num.addEventListener('input', () => { if (num.value !== '') range.value = num.value; changed(); });
        row.append(label, range, num);
      } else if (p.type === 'bool') {
        const cb = el('input', { type: 'checkbox', id: uid, checked: String(start) === 'true', attrs: { 'aria-describedby': docId } });
        cb.addEventListener('change', changed);
        row.append(label, cb, el('span'));
      } else {
        const sel = el('select', { id: uid, attrs: { 'aria-describedby': docId } }, ...p.choices.map((c) => el('option', { value: c, text: c })));
        sel.value = start;
        sel.addEventListener('change', changed);
        row.append(label, sel, el('span'));
      }
      row.append(el('p', { class: 'doc', id: docId, text: p.doc || '' }));
      form.append(row);
      row.classList.toggle('changed', String(read(row)) !== String(normal(p, p.default)));
    }
  }

  function niceStep(min, max) {
    const span = Math.abs(max - min) || 1;
    const raw = span / 200;
    const pow = Math.pow(10, Math.floor(Math.log10(raw)));
    return [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= raw) || raw;
  }

  function normal(p, v) {
    if (p.type === 'double' || p.type === 'int') return Number(v);
    if (p.type === 'bool') return String(v) === 'true';
    return String(v);
  }

  function read(row) {
    const type = row.dataset.type;
    if (type === 'double' || type === 'int') return Number(row.querySelector('input[type="number"]').value);
    if (type === 'bool') return row.querySelector('input[type="checkbox"]').checked;
    return row.querySelector('select').value;
  }

  /** All values of a params form as strings; with `onlyChanged`, only those that differ from the default. */
  function readParams(form, onlyChanged = false) {
    const out = {};
    for (const row of form.querySelectorAll('.param')) {
      const v = read(row);
      if (typeof v === 'number' && !Number.isFinite(v)) throw new Error(`${row.dataset.id} needs a number`);
      if (onlyChanged && !row.classList.contains('changed')) continue;
      out[row.dataset.id] = String(v);
    }
    return out;
  }

  function setParams(form, values) {
    for (const row of form.querySelectorAll('.param')) {
      const v = values[row.dataset.id] !== undefined ? values[row.dataset.id] : row.dataset.def;
      const type = row.dataset.type;
      if (type === 'double' || type === 'int') {
        row.querySelector('input[type="number"]').value = v;
        row.querySelector('input[type="range"]').value = v;
        row.querySelector('input[type="number"]').dispatchEvent(new Event('input'));
      } else if (type === 'bool') {
        row.querySelector('input[type="checkbox"]').checked = String(v) === 'true';
        row.querySelector('input[type="checkbox"]').dispatchEvent(new Event('change'));
      } else {
        row.querySelector('select').value = v;
        row.querySelector('select').dispatchEvent(new Event('change'));
      }
    }
  }

  function modifierChoice() {
    const manual = document.querySelector('input[name="mod-mode"][value="manual"]').checked;
    if (!manual) return null;
    return [...$('mod-list').querySelectorAll('input:checked')].map((c) => c.value);
  }

  // ---- presets -------------------------------------------------------------------------------------------------------
  function fillPresetSelect() {
    const sel = clear($('preset'));
    sel.append(el('option', { value: '', text: '(none)' }));
    for (const p of state.presets.filter((x) => x.strategy === state.selected)) {
      sel.append(el('option', { value: p.id, text: `${p.name}${p.builtIn ? ' (built-in)' : ''}` }));
    }
    $('preset-delete').disabled = true;
  }

  function onPresetChange() {
    const id = $('preset').value;
    const p = state.presets.find((x) => x.id === id);
    $('preset-delete').disabled = !p || p.builtIn;
    if (!p) return;
    setParams($('params'), p.params);
    if (p.modifiers) {
      document.querySelector('input[name="mod-mode"][value="manual"]').checked = true;
      for (const cb of $('mod-list').querySelectorAll('input')) cb.checked = p.modifiers.includes(cb.value);
    }
    setStatus(`Preset ${p.name} loaded${p.note ? `: ${p.note}` : ''}.`);
  }

  async function loadPresets() {
    state.presets = (await api('GET', '/api/presets')).presets;
    fillPresetSelect();
    renderLibraryPresets();
  }

  // ---- render --------------------------------------------------------------------------------------------------------
  async function renderDesign() {
    if (!state.selected) throw new Error('Pick a strategy first.');
    const body = {
      a: trackA(), b: trackB(), strategy: state.selected, params: readParams($('params')),
      modifiers: modifierChoice(), style: styleId(), contextSec: contextSec(),
    };
    const preset = $('preset').value;
    if (preset) body.preset = preset;
    const s = state.strategyById.get(state.selected);
    const result = await runJob(api('POST', '/api/render', body), `Rendering ${s ? s.displayName : state.selected}`);
    addSlot(result.displayName + (preset ? ` [${preset}]` : ''), result, false);
    setStatus(`Rendered ${result.strategy} in ${result.renderMillis} ms — ${result.worst}.`);
  }

  // ---- slots & player -------------------------------------------------------------------------------------------------
  const MAX_SLOTS = 16;

  function addSlot(name, result, blind) {
    state.slots.push({ name, result, blind });
    if (state.slots.length > MAX_SLOTS) {
      state.slots.shift();
      if (state.current > 0) state.current -= 1;
    }
    selectSlot(state.slots.length - 1, true);
    // In the one-column layout the player sits below the tools: bring it into view so the result is heard at once.
    if (window.matchMedia('(max-width: 1199px)').matches) $('player-panel').scrollIntoView({ block: 'start' });
  }

  function renderSlots() {
    const box = clear($('slots'));
    state.slots.forEach((s, i) => {
      const worst = s.blind ? null : s.result.worst;
      box.append(el('button', {
        type: 'button',
        title: s.name,
        attrs: { 'aria-pressed': i === state.current ? 'true' : 'false', 'aria-keyshortcuts': i < 9 ? String(i + 1) : null },
        onclick: () => selectSlot(i),
      }, `${i + 1}. ${s.name}`, worst ? el('span', { class: `verdict worst v-${worst}`, text: worst }) : null));
    });
    $('slot-empty').hidden = state.slots.length > 0;
  }

  /** Switches slot; the playback position (and play/pause) carries over so A/B differences land at the same moment. */
  function selectSlot(i, fresh = false) {
    if (i < 0 || i >= state.slots.length) return;
    const audio = $('audio');
    const pos = audio.currentTime || 0;
    const wasPlaying = !audio.paused && !audio.ended;
    state.current = i;
    const slot = state.slots[i];
    const r = slot.result;
    const target = fresh ? 0 : pos;
    audio.src = r.contextUrl;
    audio.addEventListener('loadedmetadata', () => {
      try { audio.currentTime = Math.min(target, Math.max(0, audio.duration - 0.05)); } catch (_) { /* not seekable yet */ }
      if (wasPlaying) audio.play().catch(() => {});
    }, { once: true });
    audio.load();
    renderSlots();
    renderLegend();
    renderInfo();
    renderMetrics();
    $('download-ctx').href = r.contextUrl;
    $('download-ctx').hidden = false;
    $('download-seg').hidden = !r.segmentUrl || slot.blind;
    if (r.segmentUrl) $('download-seg').href = r.segmentUrl;
    $('rate-up').disabled = slot.blind;
    $('rate-down').disabled = slot.blind;
    $('wave').setAttribute('aria-valuemax', String(Math.round(r.durationSec || 0)));
    invalidateWave();
  }

  function currentSlot() { return state.current >= 0 ? state.slots[state.current] : null; }

  function renderInfo() {
    const box = clear($('render-info'));
    const s = currentSlot();
    if (!s) return;
    const r = s.result;
    if (s.blind) {
      box.append(el('p', { text: `Blind candidate ${s.name}: vote in the Blind test tab to reveal it.` }));
      return;
    }
    const params = Object.entries(r.params || {}).map(([k, v]) => `${k}=${v}`).join(', ');
    box.append(
      el('p', null, el('strong', { text: r.displayName }), ` (${r.strategy})`, r.preset ? ` preset ${r.preset}` : '', r.unsavedRecipe ? ' — unsaved recipe' : '',
        ` · score ${fmt(r.score, 3)} · segment ${fmt(r.segmentDurationSec, 2)} s · context ${fmt(r.durationSec, 2)} s`),
      params ? el('p', { class: 'small', text: `Values: ${params}` }) : null,
      el('p', { class: 'small', text: `Modifiers: ${(r.modifiers || []).join(', ') || 'none'} · seams at ${(r.seams || []).map((x) => fmtTime(x)).join(', ')}` }),
      el('p', { class: 'small' }, 'renderKey ', el('code', { text: r.renderKey || '—' })),
    );
    if (r.markers && r.markers.length) box.append(el('details', null, el('summary', { text: `Markers (${r.markers.length})` }), el('ul', { class: 'small' }, ...r.markers.map((m) => el('li', { text: `${fmtTime(m.t)} ${m.label}` })))));
    if (r.notes && r.notes.length) box.append(el('details', null, el('summary', { text: `Plan notes (${r.notes.length})` }), el('ul', { class: 'small' }, ...r.notes.map((n) => el('li', { text: n })))));
  }

  function renderMetrics() {
    const t = clear($('metrics'));
    const w = clear($('warnings'));
    const s = currentSlot();
    if (!s || s.blind) return;
    const r = s.result;
    t.append(el('thead', null, el('tr', null, ...['Metric', 'Value', 'Warn at', 'Fail at', 'Verdict'].map((h) => el('th', { attrs: { scope: 'col' }, text: h })))));
    const body = el('tbody');
    const section = (title, list) => {
      body.append(el('tr', null, el('th', { attrs: { colspan: 5, scope: 'colgroup' }, text: title })));
      for (const m of list) {
        body.append(el('tr', null,
          el('td', { text: m.id }),
          el('td', { class: 'numc', text: `${m.value === null ? 'n/a' : fmt(m.value, 3)}${m.unit ? ` ${m.unit}` : ''}` }),
          el('td', { class: 'numc', text: m.warnAt === null ? '—' : fmt(m.warnAt, 2) }),
          el('td', { class: 'numc', text: m.failAt === null ? '—' : fmt(m.failAt, 2) }),
          el('td', null, el('span', { class: `verdict v-${m.verdict}`, text: m.verdict }))));
      }
    };
    section('Segment', r.metrics || []);
    section('Context render (through the player)', r.contextMetrics || []);
    t.append(body);
    for (const x of r.warnings || []) w.append(el('li', { text: x }));
  }

  function laneColor(i) { return css(`--lane-${i % 8}`); }

  function renderLegend() {
    const box = clear($('lane-legend'));
    const s = currentSlot();
    if (!s || s.blind || !s.result.lanes) return;
    s.result.lanes.forEach((lane, i) => {
      const vals = lane.points.map((p) => p.v).filter((v) => v !== null);
      const lo = vals.length ? Math.min(...vals) : 0;
      const hi = vals.length ? Math.max(...vals) : 0;
      const cb = el('input', { type: 'checkbox', checked: !state.laneOff.has(lane.id) });
      cb.addEventListener('change', () => { if (cb.checked) state.laneOff.delete(lane.id); else state.laneOff.add(lane.id); invalidateWave(); });
      const sw = el('span', { class: 'swatch', attrs: { 'aria-hidden': 'true' } });
      sw.style.background = laneColor(i);
      box.append(el('label', null, cb, sw, `${lane.id} (${fmt(lo, 2)}…${fmt(hi, 2)})`));
    });
  }

  // Waveform: a static layer (peaks, segment, seams, markers, lanes, time axis) cached off-screen, plus the playhead.
  let waveCache = null;

  function invalidateWave() { waveCache = null; drawWave(); }

  function drawStatic(canvas) {
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.width;
    const h = canvas.height;
    const off = document.createElement('canvas');
    off.width = w;
    off.height = h;
    const g = off.getContext('2d');
    const s = currentSlot();
    if (!s) return off;
    const r = s.result;
    const dur = r.durationSec || 1;
    const x = (t) => (t / dur) * w;
    const top = 18 * dpr;
    const bottom = h - 16 * dpr;
    const mid = (top + bottom) / 2;
    const amp = (bottom - top) / 2;

    // Segment region.
    if (r.segmentStartSec !== undefined) {
      g.fillStyle = css('--wave-seg');
      g.fillRect(x(r.segmentStartSec), 0, x(r.segmentEndSec) - x(r.segmentStartSec), h);
    }
    // Peaks.
    const pk = r.peaks;
    if (pk && pk.bins) {
      g.fillStyle = css('--wave');
      const n = pk.bins;
      for (let px = 0; px < w; px++) {
        const b0 = Math.floor((px / w) * n);
        const b1 = Math.max(b0 + 1, Math.floor(((px + 1) / w) * n));
        let lo = 0;
        let hi = 0;
        for (let b = b0; b < Math.min(b1, n); b++) { lo = Math.min(lo, pk.min[b]); hi = Math.max(hi, pk.max[b]); }
        const y0 = mid - hi * amp;
        const y1 = mid - lo * amp;
        g.fillRect(px, y0, 1, Math.max(1, y1 - y0));
      }
    }
    // Time axis.
    g.fillStyle = css('--muted');
    g.font = `${11 * dpr}px system-ui, sans-serif`;
    const tick = dur > 60 ? 10 : dur > 20 ? 5 : dur > 6 ? 2 : 1;
    for (let t = 0; t <= dur; t += tick) {
      g.fillRect(x(t), h - 4 * dpr, 1, 4 * dpr);
      g.fillText(fmtTime(t).replace(/\.0$/, ''), x(t) + 2 * dpr, h - 5 * dpr);
    }
    // Seams.
    g.strokeStyle = css('--seam');
    g.lineWidth = 1.5 * dpr;
    g.setLineDash([5 * dpr, 4 * dpr]);
    for (const t of r.seams || []) { g.beginPath(); g.moveTo(x(t), 0); g.lineTo(x(t), h); g.stroke(); }
    g.setLineDash([]);
    g.fillStyle = css('--seam');
    for (const t of r.seams || []) g.fillText('seam', x(t) + 3 * dpr, bottom - 4 * dpr);
    if (s.blind) return off;
    // Markers: every line is drawn; a label goes on the first of three rows where it does not overlap an earlier
    // label, and is left out when all three are taken (every marker is listed under "Markers" below the player).
    g.strokeStyle = css('--marker');
    g.fillStyle = css('--marker');
    g.lineWidth = 1 * dpr;
    const rowsEnd = [-Infinity, -Infinity, -Infinity];
    (r.markers || []).forEach((m) => {
      g.beginPath(); g.moveTo(x(m.t), top); g.lineTo(x(m.t), bottom); g.stroke();
      const label = m.label.length > 22 ? `${m.label.slice(0, 21)}…` : m.label;
      const tw = g.measureText(label).width;
      const lx = Math.min(Math.max(0, x(m.t) + 2 * dpr), w - tw - 2);
      const row = rowsEnd.findIndex((end) => lx > end + 4 * dpr);
      if (row < 0) return;
      rowsEnd[row] = lx + tw;
      g.fillText(label, lx, (11 + row * 12) * dpr);
    });
    // Lanes, each normalised to its own range.
    g.lineWidth = 2 * dpr;
    (r.lanes || []).forEach((lane, i) => {
      if (state.laneOff.has(lane.id)) return;
      const pts = lane.points.filter((p) => p.v !== null && p.t !== null);
      if (!pts.length) return;
      const vs = pts.map((p) => p.v);
      const lo = Math.min(...vs);
      const hi = Math.max(...vs);
      const span = hi - lo || 1;
      const y = (v) => (hi === lo ? mid : bottom - ((v - lo) / span) * (bottom - top));
      g.strokeStyle = laneColor(i);
      g.beginPath();
      pts.forEach((p, k) => { if (k === 0) g.moveTo(x(p.t), y(p.v)); else g.lineTo(x(p.t), y(p.v)); });
      g.stroke();
    });
    return off;
  }

  function drawWave() {
    const canvas = $('wave');
    const dpr = window.devicePixelRatio || 1;
    const w = Math.max(100, Math.round(canvas.clientWidth * dpr));
    const h = Math.round(220 * dpr);
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; waveCache = null; }
    const g = canvas.getContext('2d');
    g.clearRect(0, 0, w, h);
    const s = currentSlot();
    if (!s) return;
    if (!waveCache) waveCache = drawStatic(canvas);
    g.drawImage(waveCache, 0, 0);
    const audio = $('audio');
    const dur = s.result.durationSec || 1;
    const px = Math.min(w - 1, ((audio.currentTime || 0) / dur) * w);
    g.fillStyle = css('--playhead');
    g.fillRect(px - dpr, 0, 2 * dpr, h);
    g.font = `${11 * dpr}px system-ui, sans-serif`;
    const label = fmtTime(audio.currentTime || 0);
    const tw = g.measureText(label).width;
    const lx = Math.min(px + 4 * dpr, w - tw - 6 * dpr);
    const ly = h / 2 + 4 * dpr;
    g.fillStyle = css('--panel');
    g.fillRect(lx - 2 * dpr, ly - 11 * dpr, tw + 4 * dpr, 14 * dpr);
    g.fillStyle = css('--text');
    g.fillText(label, lx, ly);
    canvas.setAttribute('aria-valuenow', String(Math.round(audio.currentTime || 0)));
    canvas.setAttribute('aria-valuetext', `${fmtTime(audio.currentTime || 0)} of ${fmtTime(dur)}`);
  }

  let raf = 0;
  function tick() {
    drawWave();
    const audio = $('audio');
    raf = (!audio.paused && !audio.ended) ? requestAnimationFrame(tick) : 0;
  }

  function seekTo(t) {
    const audio = $('audio');
    const s = currentSlot();
    if (!s) return;
    const dur = Number.isFinite(audio.duration) ? audio.duration : s.result.durationSec;
    audio.currentTime = Math.max(0, Math.min(dur - 0.01, t));
    drawWave();
  }

  // ---- ratings, pins ----------------------------------------------------------------------------------------------------
  async function rateCurrent(rating) {
    const s = currentSlot();
    if (!s || s.blind) return;
    const r = s.result;
    const res = await api('POST', '/api/ratings', { a: r.a, b: r.b, strategy: r.strategy, rating, style: styleId() });
    setStatus(`Rated ${r.strategy} ${rating} in “${res.bucket}”: ${res.describe}.`);
    await replan();
    await loadRatings();
  }

  async function pinCurrent() {
    if (!state.selected) throw new Error('Pick a strategy first.');
    const body = { a: trackA(), b: trackB(), strategy: state.selected, params: readParams($('params'), true), style: styleId() };
    if ($('preset').value) body.preset = $('preset').value;
    const res = await api('POST', '/api/pins', body);
    setStatus(res.used === false ? `Pinned, but: ${res.reason}` : `Pinned ${state.selected} to this pair.`);
    await replan();
    await loadPins();
  }

  async function unpin() {
    await api('POST', '/api/pins/clear', { a: trackA(), b: trackB() });
    setStatus('Pin cleared.');
    await replan();
    await loadPins();
  }

  // ---- recipe editor ------------------------------------------------------------------------------------------------------
  const recipeText = () => $('recipe-text').value;

  async function loadRecipeList() {
    const r = await api('GET', '/api/recipes');
    state.recipes = r.recipes;
    $('recipe-dir').textContent = `Saved to ${r.dir}`;
    const sel = clear($('recipe-source'));
    sel.append(el('option', { value: '', text: 'Blank template' }));
    for (const origin of ['user', 'built-in']) {
      const list = r.recipes.filter((x) => x.origin === origin);
      if (!list.length) continue;
      const g = el('optgroup', { label: origin === 'user' ? 'Your recipes' : 'Built-in recipes' });
      for (const x of list) g.append(el('option', { value: x.id, text: `${x.name} (${x.id})${x.status !== 'active' ? ` — ${x.status}` : ''}` }));
      sel.append(g);
    }
  }

  async function loadRecipe() {
    const id = $('recipe-source').value;
    const r = id ? await api('GET', `/api/recipes/text/${encodeURIComponent(id)}`) : await api('GET', '/api/recipes/template');
    $('recipe-text').value = r.text;
    updateCursor();
    await validateRecipe();
  }

  function updateCursor() {
    const ta = $('recipe-text');
    const before = ta.value.slice(0, ta.selectionStart);
    const line = before.split('\n').length;
    const col = before.length - before.lastIndexOf('\n');
    $('recipe-cursor').textContent = `line ${line}, column ${col}`;
  }

  function jumpTo(line, col) {
    const ta = $('recipe-text');
    const lines = ta.value.split('\n');
    const l = Math.max(1, Math.min(line, lines.length));
    let offset = 0;
    for (let i = 0; i < l - 1; i++) offset += lines[i].length + 1;
    const start = offset + Math.max(0, Math.min((col || 1) - 1, lines[l - 1].length));
    ta.focus();
    ta.setSelectionRange(start, offset + lines[l - 1].length);
    const lh = parseFloat(getComputedStyle(ta).lineHeight) || 19;
    ta.scrollTop = Math.max(0, (l - 3) * lh);
    updateCursor();
  }

  let validateSeq = 0;
  async function validateRecipe() {
    const seq = ++validateSeq;
    const v = await api('POST', '/api/recipes/validate', { text: recipeText() });
    if (seq !== validateSeq) return v;
    const list = clear($('recipe-problems'));
    for (const p of v.problems) {
      const where = p.line ? `line ${p.line}${p.column ? `:${p.column}` : ''}` : '';
      const btn = el('button', { type: 'button', disabled: !p.line, onclick: () => jumpTo(p.line, p.column) },
        el('span', { class: `sev ${p.severity}`, text: p.severity }),
        [where, p.path].filter(Boolean).join(' · '), where || p.path ? ' — ' : '', p.message, p.setting ? ` (at ${p.setting})` : '');
      list.append(el('li', null, btn));
    }
    const sum = $('recipe-summary');
    clear(sum);
    if (v.ok) sum.append(el('span', { class: 'ok-text', text: `Valid${v.warnings ? ` with ${v.warnings} warning${v.warnings > 1 ? 's' : ''}` : ''}` }), ` — ${v.strategy}${v.note ? ` (${v.note})` : ''}`);
    else sum.append(el('span', { class: 'err-text', text: `${v.errors} error${v.errors === 1 ? '' : 's'}` }), v.warnings ? `, ${v.warnings} warning${v.warnings > 1 ? 's' : ''}` : '');
    $('recipe-save').disabled = !v.ok;
    $('recipe-render').disabled = !v.ok || state.busy;
    if (v.vars) {
      const prev = safeRead($('recipe-vars'));
      const ids = v.vars.map((x) => x.id).join(',');
      if (ids !== state.recipeVars.map((x) => x.id).join(',')) {
        state.recipeVars = v.vars;
        const specs = v.vars.map((x) => ({ id: x.id, label: x.label, type: x.integer ? 'int' : 'double', min: x.min, max: x.max, default: String(x.default), unit: x.unit, doc: x.doc }));
        buildParams($('recipe-vars'), specs, prev, debounce(guard(updateLanePlot), 150));
      }
      await updateLanePlot();
    }
    return v;
  }

  function safeRead(form) { try { return readParams(form); } catch (_) { return {}; } }

  let lanesSeq = 0;
  async function updateLanePlot() {
    const seq = ++lanesSeq;
    let res = null;
    try {
      res = await api('POST', '/api/recipes/lanes', { text: recipeText(), vars: safeRead($('recipe-vars')) });
    } catch (e) {
      if (seq === lanesSeq) drawLanePlot(null, e.message);
      return;
    }
    if (seq === lanesSeq) drawLanePlot(res);
  }

  function drawLanePlot(res, error) {
    const canvas = $('lane-plot');
    const dpr = window.devicePixelRatio || 1;
    const lanes = res ? res.lanes.filter((l) => !l.neutralEverywhere) : [];
    const rowH = 46;
    const cssH = Math.max(60, lanes.length * rowH + 26);
    canvas.style.height = `${cssH}px`;
    const w = Math.max(100, Math.round(canvas.clientWidth * dpr));
    const h = Math.round(cssH * dpr);
    canvas.width = w;
    canvas.height = h;
    const g = canvas.getContext('2d');
    g.clearRect(0, 0, w, h);
    g.font = `${12 * dpr}px system-ui, sans-serif`;
    g.fillStyle = css('--text');
    if (!res) { g.fillText(error || 'Validate the recipe to see its lanes.', 8 * dpr, 20 * dpr); canvas.setAttribute('aria-label', 'Recipe lane preview: none'); return; }
    if (!lanes.length) { g.fillText('Every lane is neutral: this recipe leaves both decks untouched.', 8 * dpr, 20 * dpr); return; }
    const left = 110 * dpr;
    const right = w - 10 * dpr;
    const total = res.totalBars || 1;
    const x = (bar) => left + (bar / total) * (right - left);
    lanes.forEach((lane, i) => {
      const y0 = i * rowH * dpr + 4 * dpr;
      const y1 = y0 + (rowH - 10) * dpr;
      const tr = (v) => (lane.log ? Math.log(Math.max(1, v)) : Math.max(v, lane.kind === 'db' ? -48 : v));
      const all = [...lane.samples, lane.neutral].map(tr);
      let lo = Math.min(...all);
      let hi = Math.max(...all);
      if (hi === lo) { hi += 1; lo -= 1; }
      const y = (v) => y1 - ((tr(v) - lo) / (hi - lo)) * (y1 - y0);
      g.fillStyle = css('--panel');
      g.fillRect(left, y0, right - left, y1 - y0);
      g.strokeStyle = css('--border');
      g.lineWidth = 1;
      g.beginPath(); g.moveTo(left, y(lane.neutral)); g.lineTo(right, y(lane.neutral)); g.stroke();
      g.strokeStyle = laneColor(i);
      g.lineWidth = 2 * dpr;
      g.beginPath();
      lane.samples.forEach((v, k) => { const bx = x((k / (lane.samples.length - 1)) * total); if (k === 0) g.moveTo(bx, y(v)); else g.lineTo(bx, y(v)); });
      g.stroke();
      g.fillStyle = laneColor(i);
      for (const p of lane.points) { g.beginPath(); g.arc(x(p.bar), y(p.value), 3 * dpr, 0, Math.PI * 2); g.fill(); }
      g.fillStyle = css('--text');
      g.fillText(lane.id, 6 * dpr, y0 + 14 * dpr);
      g.fillStyle = css('--muted');
      const vals = lane.samples;
      g.fillText(`${fmt(Math.min(...vals), 1)}…${fmt(Math.max(...vals), 1)} ${lane.unit}`, 6 * dpr, y0 + 30 * dpr);
    });
    // End of the overlap and bar ticks.
    g.strokeStyle = css('--seam');
    g.setLineDash([4 * dpr, 3 * dpr]);
    g.beginPath(); g.moveTo(x(res.lengthBars), 0); g.lineTo(x(res.lengthBars), h - 18 * dpr); g.stroke();
    g.setLineDash([]);
    g.fillStyle = css('--muted');
    const step = total > 32 ? 8 : total > 12 ? 4 : 1;
    for (let b = 0; b <= total + 1e-9; b += step) g.fillText(String(b), x(b) - 3 * dpr, h - 6 * dpr);
    g.fillText('bars', left - 36 * dpr, h - 6 * dpr);
    canvas.setAttribute('aria-label', `Recipe lane preview: ${lanes.map((l) => l.id).join(', ')} over ${fmt(total, 1)} bars`);
  }

  async function renderRecipe() {
    const v = await validateRecipe();
    if (!v.ok) throw new Error('Fix the errors first (click a problem to jump to it).');
    const body = { a: trackA(), b: trackB(), recipe: recipeText(), params: readParams($('recipe-vars')), style: styleId(), contextSec: contextSec() };
    const result = await runJob(api('POST', '/api/render', body), `Rendering ${v.strategy} (unsaved)`);
    addSlot(`${v.name} (unsaved)`, result, false);
    setStatus(`Rendered the unsaved recipe ${v.strategy} — ${result.worst}.`);
  }

  async function saveRecipe() {
    const r = await api('POST', '/api/recipes/save', { text: recipeText() });
    if (!r.ok) {
      await validateRecipe();
      throw new Error(`Not saved: ${(r.problems.find((p) => p.severity === 'error') || { text: 'the recipe has errors' }).text}`);
    }
    await loadStrategies();
    await loadRecipeList();
    await replan();
    setStatus(`Saved to ${r.file}; ${r.strategy} is now a strategy${r.available ? '' : ' (but it is not available — see the warnings)'}.`);
  }

  // ---- blind test -----------------------------------------------------------------------------------------------------------
  function candidateOptions() {
    const frag = document.createDocumentFragment();
    const ranked = state.plan ? state.plan.candidates.map((c) => c.strategy) : [];
    const g1 = el('optgroup', { label: 'Ranked for this pair' });
    for (const id of ranked) g1.append(el('option', { value: `s:${id}`, text: `${state.strategyById.get(id).displayName} (${id})` }));
    const g2 = el('optgroup', { label: 'Other strategies' });
    for (const s of state.strategies) if (!ranked.includes(s.id)) g2.append(el('option', { value: `s:${s.id}`, text: `${s.displayName} (${s.id})` }));
    const g3 = el('optgroup', { label: 'Presets' });
    for (const p of state.presets) if (state.strategyById.has(p.strategy)) g3.append(el('option', { value: `p:${p.id}`, text: `${p.name} → ${p.strategy}` }));
    const g4 = el('optgroup', { label: 'Recipe editor' });
    g4.append(el('option', { value: 'editor', text: 'The recipe in the editor (unsaved)' }));
    frag.append(g1, g2, g3, g4);
    return frag;
  }

  function addBlindRow(value) {
    const box = $('blind-candidates');
    if (box.children.length >= 4) return;
    const n = box.children.length + 1;
    const sel = el('select', { attrs: { 'aria-label': `Candidate ${n}` } }, candidateOptions());
    if (value) sel.value = value;
    const row = el('div', { class: 'row' }, el('span', { class: 'small', text: `Candidate ${n}` }), sel,
      el('button', { type: 'button', class: 'quiet', text: 'Remove', attrs: { 'aria-label': `Remove candidate ${n}` }, onclick: () => { if (box.children.length > 2) { row.remove(); renumberBlind(); } } }));
    box.append(row);
    renumberBlind();
  }

  function renumberBlind() {
    [...$('blind-candidates').children].forEach((row, i) => {
      row.firstChild.textContent = `Candidate ${i + 1}`;
      row.querySelector('select').setAttribute('aria-label', `Candidate ${i + 1}`);
    });
    $('blind-add').disabled = $('blind-candidates').children.length >= 4;
  }

  function fillBlindDefaults() {
    const box = $('blind-candidates');
    const kept = [...box.querySelectorAll('select')].map((s) => s.value);
    clear(box);
    const ranked = state.plan ? state.plan.candidates.map((c) => `s:${c.strategy}`) : [];
    const values = kept.length >= 2 ? kept : ranked.slice(0, 3);
    for (const v of values.length >= 2 ? values : ['s:crossfade', 's:phraseCut']) addBlindRow(v);
  }

  async function startBlind() {
    const candidates = [...$('blind-candidates').querySelectorAll('select')].map((s) => {
      const v = s.value;
      if (v === 'editor') return { recipe: recipeText(), params: safeRead($('recipe-vars')) };
      if (v.startsWith('p:')) return { preset: v.slice(2) };
      return { strategy: v.slice(2) };
    });
    const res = await runJob(api('POST', '/api/blind', { a: trackA(), b: trackB(), style: styleId(), contextSec: contextSec(), candidates }), 'Rendering the blind test');
    // Earlier blind slots that were never voted on would give the game away next to the new ones.
    state.slots = state.slots.filter((s) => !s.blind);
    state.current = Math.min(state.current, state.slots.length - 1);
    const first = state.slots.length;
    for (const it of res.items) state.slots.push({ name: it.label, result: it, blind: true });
    state.blind = { id: res.blindId, labels: res.items.map((i) => i.label) };
    selectSlot(first, true);
    const opts = clear($('blind-options'));
    for (const l of state.blind.labels) {
      opts.append(el('label', { class: 'inline' }, el('input', { type: 'radio', name: 'blind-pick', value: l, required: true }), l));
    }
    $('blind-vote').hidden = false;
    clear($('blind-reveal'));
    setStatus(`Blind test ready: listen to ${state.blind.labels.join(', ')} (slots ${first + 1}–${first + res.items.length}), then vote.`);
  }

  async function voteBlind(ev) {
    ev.preventDefault();
    const pick = document.querySelector('input[name="blind-pick"]:checked');
    if (!pick) throw new Error('Pick one first.');
    const r = await api('POST', `/api/blind/${encodeURIComponent(state.blind.id)}/vote`, { pick: pick.value });
    $('blind-vote').hidden = true;
    const t = el('table');
    t.append(el('caption', { text: `You picked ${r.pick} (context “${r.bucket}”)` }));
    t.append(el('thead', null, el('tr', null, ...['Label', 'Was', 'Rating', 'Learned weight', 'Metrics'].map((h) => el('th', { attrs: { scope: 'col' }, text: h })))));
    const body = el('tbody');
    for (const it of r.items) {
      body.append(el('tr', null,
        el('td', null, el('strong', { text: it.label }), it.picked ? ' ★' : ''),
        el('td', { text: `${it.displayName} (${it.strategy})${it.preset ? ` preset ${it.preset}` : ''}${it.unsavedRecipe ? ' — editor recipe' : ''}` }),
        el('td', { text: it.rating }),
        el('td', { class: 'numc', text: `×${fmt(it.weightBefore, 2)} → ×${fmt(it.weightAfter, 2)}` }),
        el('td', null, el('span', { class: `verdict v-${it.worst}`, text: it.worst }))));
      const slot = state.slots.find((s) => s.blind && s.name === it.label && s.result.contextUrl === it.render.contextUrl);
      if (slot) { slot.name = `${it.label}: ${it.displayName}`; slot.result = it.render; slot.blind = false; }
    }
    t.append(body);
    clear($('blind-reveal')).append(el('div', { class: 'table-wrap' }, t));
    state.blind = null;
    selectSlot(state.current);
    setStatus(`Vote recorded: ${r.pick} up, the others down. The planner now weighs them accordingly.`);
    await replan();
    await loadRatings();
  }

  // ---- sweep ---------------------------------------------------------------------------------------------------------------
  function fillSweepParams() {
    const s = state.strategyById.get(state.selected);
    const sel = clear($('sweep-param'));
    const numeric = s ? s.params.filter((p) => p.type === 'double' || p.type === 'int') : [];
    for (const p of numeric) sel.append(el('option', { value: p.id, text: `${p.label || p.id} (${p.id})` }));
    $('sweep-run').disabled = !numeric.length || state.busy;
    onSweepParam();
  }

  function onSweepParam() {
    const s = state.strategyById.get(state.selected);
    const p = s && s.params.find((x) => x.id === $('sweep-param').value);
    if (!p) return;
    $('sweep-from').value = p.min;
    $('sweep-to').value = p.max;
  }

  async function runSweep() {
    const param = $('sweep-param').value;
    if (!param) throw new Error('This strategy has no numeric parameter to sweep.');
    const params = readParams($('params'));
    delete params[param];
    const body = {
      a: trackA(), b: trackB(), strategy: state.selected, params, modifiers: modifierChoice(), style: styleId(), contextSec: contextSec(),
      param, from: parseFloat($('sweep-from').value), to: parseFloat($('sweep-to').value), steps: parseInt($('sweep-steps').value, 10),
    };
    if ($('preset').value) body.preset = $('preset').value;
    const res = await runJob(api('POST', '/api/sweep', body), `Sweeping ${param}`);
    state.sweep = res;
    const sel = clear($('sweep-metric'));
    const ids = Object.keys(res.points[0].metrics);
    for (const id of ids) sel.append(el('option', { value: id, text: id }));
    // Start on the first metric that actually moves.
    const moving = ids.find((id) => new Set(res.points.map((p) => p.metrics[id])).size > 1);
    sel.value = moving || ids[0];
    drawSweep();
    setStatus(`Sweep of ${param}: ${res.points.length} renders. Click a point to listen.`);
  }

  function drawSweep() {
    const box = clear($('sweep-plot'));
    const res = state.sweep;
    if (!res) return;
    const metric = $('sweep-metric').value;
    const pts = res.points.map((p) => ({ x: p.value, y: p.metrics[metric], v: p.verdicts[metric], p }));
    const ys = pts.map((p) => p.y).filter((y) => y !== null);
    const first = res.points[0].render;
    const all = [...(first.metrics || []), ...(first.contextMetrics || []).map((m) => ({ ...m, id: `context.${m.id}` }))];
    const spec = all.find((m) => m.id === metric) || {};
    const thr = [spec.warnAt, spec.failAt].filter((t) => t !== null && t !== undefined);
    let lo = Math.min(...ys, ...thr);
    let hi = Math.max(...ys, ...thr);
    if (!Number.isFinite(lo)) { lo = 0; hi = 1; }
    if (hi === lo) { hi += 1; lo -= 1; }
    const W = 640; const H = 300; const L = 60; const R = 16; const T = 34; const B = 40;
    const xs = pts.map((p) => p.x);
    const x0 = Math.min(...xs); const x1 = Math.max(...xs) === x0 ? x0 + 1 : Math.max(...xs);
    const X = (v) => L + ((v - x0) / (x1 - x0)) * (W - L - R);
    const Y = (v) => H - B - ((v - lo) / (hi - lo)) * (H - T - B);
    const NS = 'http://www.w3.org/2000/svg';
    const s = (tag, attrs, text) => { const n = document.createElementNS(NS, tag); for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, String(v)); if (text !== undefined) n.textContent = text; return n; };
    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, role: 'group', 'aria-label': `${metric} against ${res.param}` });
    svg.append(s('line', { class: 'axis', x1: L, y1: H - B, x2: W - R, y2: H - B }), s('line', { class: 'axis', x1: L, y1: T, x2: L, y2: H - B }));
    svg.append(s('text', { x: W / 2, y: H - 6, 'text-anchor': 'middle' }, res.param), s('text', { x: 6, y: 16 }, `${metric}${spec.unit ? ` (${spec.unit})` : ''}`));
    svg.append(s('text', { x: L - 6, y: Y(hi) + 4, 'text-anchor': 'end' }, fmt(hi, 2)), s('text', { x: L - 6, y: Y(lo) + 4, 'text-anchor': 'end' }, fmt(lo, 2)));
    if (spec.warnAt !== null && spec.warnAt !== undefined) svg.append(s('line', { class: 'thr warn', x1: L, x2: W - R, y1: Y(spec.warnAt), y2: Y(spec.warnAt) }));
    if (spec.failAt !== null && spec.failAt !== undefined) svg.append(s('line', { class: 'thr fail', x1: L, x2: W - R, y1: Y(spec.failAt), y2: Y(spec.failAt) }));
    const valid = pts.filter((p) => p.y !== null);
    if (valid.length > 1) svg.append(s('polyline', { class: 'line', points: valid.map((p) => `${X(p.x)},${Y(p.y)}`).join(' ') }));
    for (const p of pts) {
      svg.append(s('text', { x: X(p.x), y: H - B + 16, 'text-anchor': 'middle' }, fmt(p.x, Number.isInteger(p.x) ? 0 : 2)));
      if (p.y === null) continue;
      const c = s('circle', { class: `pt v-${p.v}`, cx: X(p.x), cy: Y(p.y), r: 7, tabindex: 0, role: 'button', 'aria-label': `${res.param} ${fmt(p.x, 3)}: ${metric} ${fmt(p.y, 3)} (${p.v}). Listen.` });
      c.append(s('title', {}, `${res.param} = ${fmt(p.x, 3)} · ${metric} = ${fmt(p.y, 3)} (${p.v})`));
      const play = () => addSlot(`${res.param}=${fmt(p.x, Number.isInteger(p.x) ? 0 : 3)}`, p.p.render, false);
      c.addEventListener('click', play);
      c.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); play(); } });
      svg.append(c);
    }
    box.append(svg);
  }

  // ---- library ---------------------------------------------------------------------------------------------------------------
  function table(node, headers, rows) {
    clear(node);
    node.append(el('thead', null, el('tr', null, ...headers.map((h) => el('th', { attrs: { scope: 'col' }, text: h })))));
    const body = el('tbody');
    if (!rows.length) body.append(el('tr', null, el('td', { attrs: { colspan: headers.length }, class: 'muted', text: 'None yet.' })));
    for (const r of rows) body.append(el('tr', null, ...r.map((c) => (c instanceof Node ? el('td', null, c) : el('td', { text: c })))));
    node.append(body);
  }

  function renderLibraryPresets() {
    table($('presets-table'), ['Preset', 'Strategy', 'Source', 'Values', ''], state.presets.map((p) => [
      `${p.name} (${p.id})`, p.strategy, p.builtIn ? 'built-in' : 'yours',
      Object.entries(p.params).map(([k, v]) => `${k}=${v}`).join(' ') || '—',
      el('span', { class: 'row' },
        el('button', { type: 'button', class: 'quiet', text: 'Use', disabled: !state.strategyById.has(p.strategy), onclick: guard(async () => { selectTab('design'); selectStrategy(p.strategy); $('preset').value = p.id; onPresetChange(); }) }),
        p.builtIn ? null : el('button', { type: 'button', class: 'quiet', text: 'Delete', onclick: guard(async () => { await api('DELETE', `/api/presets/${encodeURIComponent(p.id)}`, {}); await loadPresets(); setStatus(`Deleted preset ${p.id}.`); }) })),
    ]));
  }

  async function loadPins() {
    const r = await api('GET', '/api/pins');
    table($('pins-table'), ['A', 'B', 'Strategy', 'Preset', 'Values', ''], r.pins.map((p) => [
      p.aLabel, p.bLabel, p.strategy, p.preset || '—', Object.entries(p.params).map(([k, v]) => `${k}=${v}`).join(' ') || '—',
      el('button', { type: 'button', class: 'quiet', text: 'Clear', onclick: guard(async () => { await api('POST', '/api/pins/clear', { aIdentity: p.aIdentity, bIdentity: p.bIdentity }); await loadPins(); await replan(); }) }),
    ]));
  }

  async function loadRatings() {
    const r = await api('GET', '/api/ratings');
    $('ratings-formula').textContent = `${r.formula}; up = 1, down = 0. Weights stay within ×0.5…×1.5.`;
    table($('ratings-table'), ['Strategy', 'Context', 'Ratings', 'Mean', 'Weight'], r.ratings.map((x) => [x.strategy, x.bucket, String(x.n), fmt(x.mean, 2), `×${fmt(x.weight, 2)}`]));
  }

  // ---- strategies & styles -------------------------------------------------------------------------------------------------
  async function loadStrategies() {
    const r = await api('GET', '/api/strategies');
    state.strategies = r.strategies;
    state.strategyById = new Map(r.strategies.map((s) => [s.id, s]));
    state.modifiers = r.modifiers;
    const box = clear($('mod-list'));
    for (const m of r.modifiers) box.append(el('label', { class: 'inline' }, el('input', { type: 'checkbox', value: m.id, onchange: () => { document.querySelector('input[name="mod-mode"][value="manual"]').checked = true; } }), m.displayName));
  }

  async function loadStyles() {
    const r = await api('GET', '/api/styles');
    state.styles = r.styles;
    const sel = clear($('style'));
    sel.append(el('option', { value: '', text: 'As started (command line)' }));
    for (const s of r.styles) sel.append(el('option', { value: s.id, text: s.name }));
    updateStyleHelp();
  }

  function updateStyleHelp() {
    const s = state.styles.find((x) => x.id === $('style').value);
    $('style-help').textContent = s ? `${s.description}${s.changes.length ? ` (${s.changes.join('; ')})` : ''}` : '';
  }

  // ---- tabs & keyboard ----------------------------------------------------------------------------------------------------------
  const TABS = ['design', 'recipe', 'blind', 'sweep', 'library'];

  function selectTab(name, focus = false) {
    for (const t of TABS) {
      const on = t === name;
      const b = $(`tabbtn-${t}`);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
      b.tabIndex = on ? 0 : -1;
      $(`tab-${t}`).hidden = !on;
      if (on && focus) b.focus();
    }
    if (name === 'recipe') drawLanePlotSoon();
  }

  const drawLanePlotSoon = debounce(guard(updateLanePlot), 50);

  function onTabKey(e) {
    const i = TABS.findIndex((t) => `tabbtn-${t}` === e.target.id);
    if (i < 0) return;
    let j = -1;
    if (e.key === 'ArrowRight') j = (i + 1) % TABS.length;
    else if (e.key === 'ArrowLeft') j = (i - 1 + TABS.length) % TABS.length;
    else if (e.key === 'Home') j = 0;
    else if (e.key === 'End') j = TABS.length - 1;
    if (j >= 0) { e.preventDefault(); selectTab(TABS[j], true); }
  }

  function onGlobalKey(e) {
    const t = e.target;
    const tag = t && t.tagName;
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (t && t.isContentEditable)) return;
    if (e.key === ' ' && tag !== 'BUTTON' && tag !== 'A' && tag !== 'AUDIO' && tag !== 'SUMMARY' && !(t && t.getAttribute && t.getAttribute('role') === 'button')) {
      e.preventDefault();
      const a = $('audio');
      if (a.src) { if (a.paused) a.play().catch(() => {}); else a.pause(); }
    } else if (/^[1-9]$/.test(e.key)) {
      selectSlot(parseInt(e.key, 10) - 1);
    }
  }

  function onWaveKey(e) {
    const a = $('audio');
    if (e.key === 'ArrowLeft') { e.preventDefault(); seekTo((a.currentTime || 0) - 2); }
    else if (e.key === 'ArrowRight') { e.preventDefault(); seekTo((a.currentTime || 0) + 2); }
    else if (e.key === 'Home') { e.preventDefault(); seekTo(0); }
    else if (e.key === 'End') { e.preventDefault(); const s = currentSlot(); if (s) seekTo(s.result.durationSec); }
    else if (e.key === 'PageUp' || e.key === 'PageDown') {
      // Jump to the previous / next seam: the moments worth comparing.
      e.preventDefault();
      const s = currentSlot();
      if (!s) return;
      const now = a.currentTime || 0;
      const seams = s.result.seams || [];
      const target = e.key === 'PageDown' ? seams.find((x) => x > now + 0.05) : [...seams].reverse().find((x) => x < now - 0.05);
      if (target !== undefined) seekTo(Math.max(0, target - 1));
    }
  }

  // ---- wiring -------------------------------------------------------------------------------------------------------------------
  function wire() {
    $('track-a').addEventListener('change', guard(replan));
    $('track-b').addEventListener('change', guard(replan));
    $('swap').addEventListener('click', guard(async () => { const a = trackA(); $('track-a').value = trackB(); $('track-b').value = a; await replan(); }));
    $('style').addEventListener('change', guard(async () => { updateStyleHelp(); await replan(); setStatus(`Style: ${$('style').selectedOptions[0].textContent}. Re-planned.`); }));
    $('add-form').addEventListener('submit', guard(async (e) => {
      e.preventDefault();
      const path = $('add-path').value.trim();
      if (!path) throw new Error('Type a file or folder path.');
      const res = await runJob(api('POST', '/api/tracks', { path }), 'Analysing');
      await loadTracks(trackA(), trackB());
      setStatus(`Added ${res.added.length} track(s)${res.skipped.length ? `; skipped: ${res.skipped.join('; ')}` : ''}.`, res.skipped.length > 0 && !res.added.length);
      await replan();
    }));
    $('render').addEventListener('click', guard(renderDesign));
    $('reset-params').addEventListener('click', () => { setParams($('params'), {}); $('preset').value = ''; $('preset-delete').disabled = true; });
    $('preset').addEventListener('change', onPresetChange);
    $('preset-delete').addEventListener('click', guard(async () => {
      const id = $('preset').value;
      if (!id) return;
      await api('DELETE', `/api/presets/${encodeURIComponent(id)}`, {});
      await loadPresets();
      setStatus(`Deleted preset ${id}.`);
    }));
    $('preset-form').addEventListener('submit', guard(async (e) => {
      e.preventDefault();
      const id = $('preset-id').value.trim();
      if (!/^[a-z0-9][a-z0-9-]*$/.test(id)) throw new Error('A preset id uses lowercase letters, digits and dashes, e.g. tight-swap.');
      const body = { id, name: id, strategy: state.selected, params: readParams($('params'), true) };
      const mods = modifierChoice();
      if (mods) body.modifiers = mods;
      await api('POST', '/api/presets', body);
      await loadPresets();
      $('preset').value = id;
      $('preset-delete').disabled = false;
      setStatus(`Saved preset ${id} for ${state.selected}.`);
    }));
    $('pin').addEventListener('click', guard(pinCurrent));
    $('unpin').addEventListener('click', guard(unpin));
    $('rate-up').addEventListener('click', guard(() => rateCurrent('up')));
    $('rate-down').addEventListener('click', guard(() => rateCurrent('down')));

    const audio = $('audio');
    audio.addEventListener('play', () => { if (!raf) raf = requestAnimationFrame(tick); });
    audio.addEventListener('seeked', drawWave);
    audio.addEventListener('timeupdate', () => { if (!raf) drawWave(); });
    audio.addEventListener('error', () => { if (audio.src) setStatus('The browser could not play this render.', true); });
    const wave = $('wave');
    wave.addEventListener('click', (e) => {
      const s = currentSlot();
      if (!s) return;
      const rect = wave.getBoundingClientRect();
      seekTo(((e.clientX - rect.left) / rect.width) * s.result.durationSec);
    });
    wave.addEventListener('keydown', onWaveKey);
    new ResizeObserver(() => { waveCache = null; drawWave(); }).observe(wave);
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { renderLegend(); invalidateWave(); drawLanePlotSoon(); });

    for (const t of TABS) $(`tabbtn-${t}`).addEventListener('click', () => selectTab(t));
    document.querySelector('.tabs').addEventListener('keydown', onTabKey);
    document.addEventListener('keydown', onGlobalKey);

    $('recipe-load').addEventListener('click', guard(loadRecipe));
    $('recipe-validate').addEventListener('click', guard(validateRecipe));
    $('recipe-render').addEventListener('click', guard(renderRecipe));
    $('recipe-save').addEventListener('click', guard(saveRecipe));
    const ta = $('recipe-text');
    ta.addEventListener('input', debounce(guard(validateRecipe), 600));
    for (const ev of ['keyup', 'click', 'select']) ta.addEventListener(ev, updateCursor);
    ta.addEventListener('keydown', (e) => {
      // Tab indents inside the editor; Escape then Tab leaves it (keyboard users are never trapped).
      if (e.key === 'Escape') { ta.dataset.escaped = '1'; return; }
      if (e.key === 'Tab' && !e.shiftKey && ta.dataset.escaped !== '1') {
        e.preventDefault();
        const s = ta.selectionStart;
        ta.setRangeText('  ', s, ta.selectionEnd, 'end');
      }
      if (e.key !== 'Tab') delete ta.dataset.escaped;
    });

    $('blind-add').addEventListener('click', () => addBlindRow());
    $('blind-start').addEventListener('click', guard(startBlind));
    $('blind-vote').addEventListener('submit', guard(voteBlind));

    $('sweep-param').addEventListener('change', onSweepParam);
    $('sweep-run').addEventListener('click', guard(runSweep));
    $('sweep-metric').addEventListener('change', drawSweep);
  }

  async function init() {
    wire();
    $('download-ctx').hidden = true;
    $('download-seg').hidden = true;
    const session = await api('GET', '/api/session');
    await Promise.all([loadStrategies(), loadStyles(), loadTracks(), loadPresets(), loadRecipeList()]);
    await Promise.all([loadPins(), loadRatings()]);
    if (state.tracks.length) await replan();
    $('recipe-text').value = (await api('GET', '/api/recipes/template')).text;
    await validateRecipe();
    if (session.warnings.length) setStatus(`Warnings: ${session.warnings.join(' · ')}`, true);
    else if (state.tracks.length) setStatus(`Ready: ${state.tracks.length} track(s). Pick a strategy and press Render.`);
  }

  document.addEventListener('DOMContentLoaded', guard(init));
})();
