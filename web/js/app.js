'use strict';

// ---------- constants and shared state ----------

const COUNTDOWN_SECONDS = 3;
const RECORD_MS = 4000;
const MIN_FRAMES = 5;
const PARAM_LABELS = {
  handshape: 'Handshape',
  orientation: 'Hand orientation',
  location: 'Hand location',
  movement: 'Movement',
};

const app = document.getElementById('app');
let currentCamera = null;
let memoryLearnerId = null;
let referenceDemoCleanup = null;

// ---------- learner identity (anonymous, stored on this device only) ----------

function newId() {
  if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
  return 'id-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12);
}

function learnerId() {
  try {
    let id = localStorage.getItem('dalil.learnerId');
    if (!id) {
      id = newId();
      localStorage.setItem('dalil.learnerId', id);
    }
    return id;
  } catch (_) {
    // Storage blocked (private window, for example): keep an id for this page session only.
    if (!memoryLearnerId) memoryLearnerId = newId();
    return memoryLearnerId;
  }
}

function forgetLearnerId() {
  try {
    localStorage.removeItem('dalil.learnerId');
  } catch (_) { /* nothing stored */ }
  memoryLearnerId = null;
}

// ---------- small helpers ----------

// Builds DOM nodes safely: text is always set with textContent, never as HTML.
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : String(value));
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function setView(...nodes) {
  app.replaceChildren(...nodes);
}

function alertBox(message) {
  return el('p', { class: 'alert', role: 'alert', text: message });
}

function loadingView() {
  setView(el('p', { class: 'muted', text: 'Loading…' }));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function stopCamera() {
  if (referenceDemoCleanup) { referenceDemoCleanup(); referenceDemoCleanup = null; }
  if (currentCamera) {
    currentCamera.stop();
    currentCamera = null;
  }
}

// ---------- routing ----------

const routes = [
  [/^#\/?$/, () => renderCatalogue()],
  [/^#\/sign\/([a-z0-9-]{1,40})$/, (m) => renderPractice(m[1])],
  [/^#\/progress$/, () => renderProgress()],
  [/^#\/record\/([a-z0-9-]{1,40})$/, (m) => renderRecord(m[1])],
];

function route() {
  stopCamera();
  const hash = location.hash || '#/';
  for (const [pattern, handler] of routes) {
    const match = hash.match(pattern);
    if (match) {
      const result = handler(match);
      if (result && typeof result.catch === 'function') {
        result.catch((err) => {
          console.error(err);
          setView(alertBox('Something went wrong: ' + err.message));
        });
      }
      return;
    }
  }
  setView(el('p', {}, 'Page not found. ', el('a', { href: '#/', text: 'Back to signs' })));
}

window.addEventListener('hashchange', route);

// ---------- catalogue ----------

async function renderCatalogue() {
  loadingView();
  try {
    const { signs } = await Api.listSigns();
    const search = el('input', { type: 'search', placeholder: 'Search signs by name or description…', 'aria-label': 'Search signs' });
    const categories = [...new Set(signs.map((s) => s.category).filter(Boolean))].sort();
    const category = el('select', { 'aria-label': 'Filter by category' },
      el('option', { value: '', text: 'All categories' }),
      categories.map((c) => el('option', { value: c, text: c })));
    const grid = el('div', { class: 'grid' });
    function update() {
      const q = search.value.trim().toLowerCase();
      const filtered = signs.filter((s) => (!category.value || s.category === category.value)
        && (!q || `${s.name} ${s.description} ${s.category}`.toLowerCase().includes(q)));
      grid.replaceChildren(...(filtered.length ? filtered.map((s) =>
        el('a', { class: 'card', href: `#/sign/${s.id}` },
          el('span', { class: 'tag', text: `${s.category} · ${s.difficulty}` }),
          el('h2', { text: s.name }),
          el('p', { class: 'muted', text: s.description }),
          el('span', { class: s.hasReference ? 'ok' : 'warn',
            text: s.hasReference ? (s.hasReferenceVideo ? 'Video + scoring model ready' : 'Scoring model ready') : 'Waiting for instructor recording' })))
        : [el('p', { class: 'muted', text: 'No signs match your search.' })]));
    }
    search.addEventListener('input', update);
    category.addEventListener('change', update);
    update();
    setView(
      el('h1', { text: 'Emirati Sign Language signs' }),
      el('p', { class: 'muted', text: `${signs.length} signs available. Search or filter the catalogue, then open a sign to watch its demonstration and practise.` }),
      el('div', { class: 'catalogue-filters' }, search, category), grid);
  } catch (err) {
    setView(alertBox(err.message));
  }
}

// ---------- practice ----------

async function renderPractice(signId) {
  loadingView();
  let sign;
  try {
    sign = await Api.getSign(signId);
  } catch (err) {
    setView(alertBox(err.message));
    return;
  }

  const modelCanvas = el('canvas', { class: 'ref-canvas', width: 320, height: 320, 'aria-label': 'Model hand shape' });
  if (sign.referenceFrame) drawModel(modelCanvas, sign.referenceFrame);
  const demoVideo = el('video', { class: 'demo-video', controls: true, playsinline: true, preload: 'metadata' });
  const demoOverlay = el('canvas', { class: 'overlay demo-overlay', 'aria-hidden': 'true' });
  const demoWrap = el('div', { class: 'video-wrap demo-wrap' }, demoVideo, demoOverlay);
  const demoRate = el('select', { 'aria-label': 'Demonstration playback speed' },
    ['0.5', '0.75', '1', '1.25'].map((v) => el('option', { value: v, text: `${v}× speed` })));
  demoRate.value = '1';
  demoRate.onchange = () => { demoVideo.playbackRate = Number(demoRate.value); };
  const demoPanel = sign.hasReferenceVideo
    ? el('figure', {}, demoWrap, el('figcaption', { text: 'Instructor demonstration · landmarks follow the moving hand' }), el('div', { class: 'row demo-controls' }, demoRate))
    : el('figure', {}, modelCanvas, el('figcaption', { text: sign.hasReference ? 'Model hand shape (no demonstration video yet)' : 'No model recorded yet' }));
  if (sign.hasReferenceVideo) {
    demoVideo.src = `/api/reference-videos/${encodeURIComponent(signId)}`;
    if (Number.isFinite(sign.clipStart) && Number.isFinite(sign.clipEnd)) {
      limitVideoToClip(demoVideo, sign.clipStart, sign.clipEnd);
    }
    startReferenceOverlay(demoVideo, demoOverlay);
  }

  const results = el('section', { class: 'results', 'aria-live': 'polite' });

  const camera = cameraBlock({
    recordLabel: 'Record my sign (4 seconds)',
    enabled: sign.hasReference,
    onRecorded: async (frames, status) => {
      if (frames.length < MIN_FRAMES) {
        throw new Error(`Only ${frames.length} frames had a visible hand. Keep your whole hand in view and try again.`);
      }
      status.textContent = 'Checking your sign…';
      const result = await Api.submitAttempt(learnerId(), signId, frames);
      status.textContent = 'Here is your feedback.';
      results.replaceChildren(renderResult(result));
    },
    onBeforeRecord: () => results.replaceChildren(),
  });

  setView(
    el('a', { class: 'back', href: '#/', text: '← All signs' }),
    el('h1', { text: sign.name }),
    el('p', { class: 'tag', text: `${sign.category} · ${sign.difficulty}` }),
    el('p', { text: sign.description }),
    sign.steps.length ? el('h2', { text: 'Steps' }) : '',
    sign.steps.length ? el('ol', { class: 'list' }, sign.steps.map((s) => el('li', { text: s }))) : '',
    el('h2', { text: 'Watch and imitate' }),
    el('p', { class: 'current-sign', text: `Currently learning: ${sign.name}` }),
    sign.sourceVideoUrl
      ? el('p', { class: 'small' }, el('a', { href: sign.sourceVideoUrl, target: '_blank', rel: 'noopener noreferrer', text: 'Open original reference video on YouTube' }))
      : '',
    el('p', { class: 'muted small', text: sign.hasReferenceVideo ? 'This demonstration belongs to the sign named above. Watch the instructor, slow the clip down, then perform the same movement in your camera.' : 'An instructor video demonstration will appear here once one is uploaded.' }),
    el('div', { class: 'compare' },
      demoPanel,
      el('figure', {}, camera.videoWrap, el('figcaption', { text: 'Your live camera' }))),
    camera.controls,
    sign.hasReference ? '' : alertBox('This sign has no instructor recording yet, so it cannot be scored.'),
    el('p', { class: 'small' }, el('a', { href: `#/record/${signId}`, text: 'Record model (instructor)' })),
    results,
    el('p', { class: 'small muted',
      text: 'Privacy: your video stays in this browser and is not saved. Dalil receives only hand-point positions to score your attempt, and it keeps the scores so you can track progress. You can delete them on the progress page.' }));
}

// Draws the model's hand path and the learner's path on one small chart, so "too big" or "wrong direction" is visible.
function movementChart(detail) {
  const W = 300, H = 200, PAD = 24;
  const canvas = el('canvas', { class: 'path-canvas', width: W, height: H, 'aria-label': 'Model hand path compared with your hand path' });
  const ctx = canvas.getContext('2d');
  const model = detail.reference || [];
  const you = detail.attempt || [];
  const all = model.concat(you);
  if (!all.length) return '';

  const xs = all.map((p) => p[0]);
  const ys = all.map((p) => p[1]);
  const minX = Math.min(...xs), maxX = Math.max(...xs);
  const minY = Math.min(...ys), maxY = Math.max(...ys);
  const spanX = Math.max(maxX - minX, 0.5);
  const spanY = Math.max(maxY - minY, 0.5);
  const scale = Math.min((W - 2 * PAD) / spanX, (H - 2 * PAD) / spanY);
  const offX = (W - (maxX - minX) * scale) / 2 - minX * scale;
  const offY = (H - (maxY - minY) * scale) / 2 - minY * scale;
  const toCanvas = (p) => [p[0] * scale + offX, p[1] * scale + offY];

  function drawPath(points, color, width) {
    if (!points.length) return;
    const pts = points.map(toCanvas);
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.lineWidth = width;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    pts.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
    ctx.stroke();
    // start = circle
    ctx.beginPath();
    ctx.arc(pts[0][0], pts[0][1], width + 2, 0, 2 * Math.PI);
    ctx.fill();
    // end = arrow head, pointing along the last stretch of the path
    const last = pts[pts.length - 1];
    let from = pts[0];
    for (let i = pts.length - 2; i >= 0; i--) {
      if (Math.hypot(last[0] - pts[i][0], last[1] - pts[i][1]) > 8) { from = pts[i]; break; }
    }
    if (Math.hypot(last[0] - from[0], last[1] - from[1]) > 8) {
      const angle = Math.atan2(last[1] - from[1], last[0] - from[0]);
      const size = 8 + width;
      ctx.beginPath();
      ctx.moveTo(last[0], last[1]);
      ctx.lineTo(last[0] - size * Math.cos(angle - 0.45), last[1] - size * Math.sin(angle - 0.45));
      ctx.lineTo(last[0] - size * Math.cos(angle + 0.45), last[1] - size * Math.sin(angle + 0.45));
      ctx.closePath();
      ctx.fill();
    }
  }

  drawPath(model, '#adb5bd', 6);
  drawPath(you, '#3bc9db', 3);

  return el('figure', { class: 'path-figure' },
    canvas,
    el('figcaption', { class: 'path-legend' },
      el('span', { class: 'swatch model' }), ` Model (about ${detail.referenceSize} palm-lengths)   `,
      el('span', { class: 'swatch you' }), ` You (about ${detail.attemptSize} palm-lengths)`),
    el('p', { class: 'small muted', text: 'Circle = where the movement starts, arrow = where it ends. Left and right are from your own point of view. A palm-length is the distance from your wrist to the base of your middle finger.' }));
}

function renderResult(result) {
  const verdict = result.correct
    ? el('p', { class: 'verdict ok', text: 'Correct. Well done!' })
    : el('p', { class: 'verdict warn', text: 'Not quite yet. Work on the points below.' });

  const rows = result.params.map((p) =>
    el('div', { class: 'param' },
      el('div', { class: 'param-head' },
        el('strong', { text: PARAM_LABELS[p.name] || p.name }),
        el('span', { text: `${p.score}/100` })),
      el('div', { class: 'bar', role: 'img', 'aria-label': `${p.score} out of 100` },
        el('div', { class: 'fill', style: `width:${p.score}%` })),
      p.message
        ? el('p', { text: p.message })
        : el('p', { class: 'muted', text: 'Looks good.' }),
      p.detail && p.detail.reference ? movementChart(p.detail) : ''));

  return el('section', { class: 'card-plain' },
    el('h2', { text: `Overall score: ${result.overall}/100` }),
    verdict,
    rows);
}

// ---------- instructor recording ----------

const INSTRUCTOR_KEY = 'dalilInstructorAuth';

function storedInstructorAuth() {
  try { return sessionStorage.getItem(INSTRUCTOR_KEY); } catch (_) { return null; }
}

function storeInstructorAuth(value) {
  try {
    if (value) sessionStorage.setItem(INSTRUCTOR_KEY, value);
    else sessionStorage.removeItem(INSTRUCTOR_KEY);
  } catch (_) { /* storage blocked: the learner just signs in again */ }
}

async function renderRecord(signId) {
  loadingView();
  let sign;
  try {
    sign = await Api.getSign(signId);
  } catch (err) {
    setView(alertBox(err.message));
    return;
  }
  const auth = storedInstructorAuth();
  if (auth) renderRecordPage(signId, sign, auth);
  else renderInstructorLogin(signId, sign);
}

function renderInstructorLogin(signId, sign) {
  const user = el('input', { type: 'text', autocomplete: 'username', 'aria-label': 'Username' });
  const pass = el('input', { type: 'password', autocomplete: 'current-password', 'aria-label': 'Password' });
  const msg = el('p', { class: 'muted small', role: 'status', text: 'Sign in with the instructor account to record a model.' });
  const btn = el('button', { class: 'btn primary', type: 'button', text: 'Sign in' });
  btn.onclick = async () => {
    const auth = 'Basic ' + btoa(unescape(encodeURIComponent(`${user.value}:${pass.value}`)));
    btn.disabled = true;
    msg.textContent = 'Checking…';
    try {
      await Api.instructorCheck(auth);
      storeInstructorAuth(auth);
      renderRecordPage(signId, sign, auth);
    } catch (_) {
      msg.textContent = 'Username or password is incorrect.';
      btn.disabled = false;
    }
  };
  setView(
    el('a', { class: 'back', href: `#/sign/${signId}`, text: '← Back to practice' }),
    el('h1', { text: `Instructor sign-in: ${sign.name}` }),
    el('p', { text: 'Only instructors can record the model that learners are scored against.' }),
    el('div', { class: 'row' }, user, pass),
    el('div', { class: 'row' }, btn),
    msg);
}

// Saves a model. If the server rejects the sign-in, it is cleared so the next save asks again.
async function saveModel(signId, frames, auth) {
  try {
    await Api.saveReference(signId, frames, auth);
  } catch (err) {
    if (err.status === 401) {
      storeInstructorAuth(null);
      throw new Error('Your instructor sign-in has expired. Refresh the page and sign in again.');
    }
    throw err;
  }
}

function renderRecordPage(signId, sign, auth) {
  const camera = cameraBlock({
    recordLabel: 'Record model (4 seconds)',
    enabled: true,
    onRecorded: async (frames, statusEl) => {
      if (frames.length < MIN_FRAMES) {
        throw new Error(`Only ${frames.length} frames had a visible hand. Record again with the whole hand in view.`);
      }
      statusEl.textContent = 'Saving model…';
      await saveModel(signId, frames, auth);
      statusEl.textContent = `Saved ${frames.length} frames as the model for this sign.`;
    },
  });

  const signOut = el('button', { class: 'btn', type: 'button', text: 'Sign out of instructor' });
  signOut.onclick = () => {
    storeInstructorAuth(null);
    renderRecord(signId);
  };

  setView(
    el('a', { class: 'back', href: `#/sign/${signId}`, text: '← Back to practice' }),
    el('h1', { text: `Record model: ${sign.name}` }),
    el('p', { text: 'Ask an Emirati Sign Language instructor to perform the sign correctly in front of the camera. This recording becomes the model that learners are scored against, so re-record it if the first attempt is not clean.' }),
    el('p', { class: 'muted small', text: `Currently: ${sign.hasReference ? 'a model is recorded (recording again replaces it)' : 'no model yet'}.` }),
    el('div', { class: 'compare' },
      el('figure', {}, camera.videoWrap, el('figcaption', { text: 'Live camera' }))),
    camera.controls,
    videoBuilderBlock(signId, auth),
    el('div', { class: 'row' }, signOut));
}

// Builds the model from a video clip instead of the webcam.
function videoBuilderBlock(signId, auth) {
  const fileInput = el('input', { type: 'file', accept: 'video/mp4,video/webm,video/quicktime,video/*' });
  const video = el('video', { controls: true, playsinline: true, preload: 'auto' });
  const startInput = el('input', { type: 'number', min: '0', step: '0.1', value: '0', 'aria-label': 'Start time (seconds)' });
  const endInput = el('input', { type: 'number', min: '0', step: '0.1', value: '4', 'aria-label': 'End time (seconds)' });
  const status = el('p', { class: 'status', role: 'status', text: 'Choose a video file to start.' });
  const buildBtn = el('button', { class: 'btn primary', type: 'button', text: 'Build model from this clip', disabled: true });
  const setStart = el('button', { class: 'btn', type: 'button', text: 'Start = current time', disabled: true });
  const setEnd = el('button', { class: 'btn', type: 'button', text: 'End = current time', disabled: true });
  const playClip = el('button', { class: 'btn', type: 'button', text: 'Play clip', disabled: true });

  fileInput.onchange = () => {
    const file = fileInput.files[0];
    if (!file) return;
    video.src = URL.createObjectURL(file);
    video.onloadedmetadata = () => {
      status.textContent = `Loaded ${Math.round(video.duration)} seconds. Set the start and end of one sign, then build the model.`;
      [buildBtn, setStart, setEnd, playClip].forEach((b) => { b.disabled = false; });
    };
  };
  setStart.onclick = () => { startInput.value = video.currentTime.toFixed(1); };
  setEnd.onclick = () => { endInput.value = video.currentTime.toFixed(1); };
  playClip.onclick = () => {
    video.currentTime = Number(startInput.value) || 0;
    video.play();
    const stopAt = Number(endInput.value);
    const check = () => {
      if (video.currentTime >= stopAt) { video.pause(); video.removeEventListener('timeupdate', check); }
    };
    video.addEventListener('timeupdate', check);
  };

  buildBtn.onclick = async () => {
    const start = Number(startInput.value);
    const end = Number(endInput.value);
    buildBtn.disabled = true;
    try {
      const frames = await extractFramesFromVideo(video, { start, end, side: 'full' },
        (done, total) => { status.textContent = `Reading frames… ${done} of ${total}`; });
      if (frames.length < MIN_FRAMES) {
        throw new Error(`Only ${frames.length} frames had a visible hand. Adjust the start and end times, and keep the signer's hand fully in view.`);
      }
      status.textContent = 'Saving model…';
      await saveModel(signId, frames, auth);
      const sourceFile = fileInput.files[0];
      if (sourceFile) {
        status.textContent = 'Saving the original demonstration video…';
        await Api.saveReferenceVideo(signId, sourceFile, auth, start, end);
      }
      status.textContent = `Saved ${frames.length} landmark frames and the demonstration clip (${start}s to ${end}s) for this sign.`;
    } catch (err) {
      status.textContent = err.message;
    } finally {
      buildBtn.disabled = false;
    }
  };

  return el('div', { class: 'card-plain' },
    el('h2', { text: 'Or build the model from a video file' }),
    el('p', { class: 'muted small', text: 'Use this for a clip of one sign. Set the start and end to the part you want.' }),
    fileInput,
    el('div', { class: 'row' }, el('label', {}, 'Start (s) ', startInput), el('label', {}, 'End (s) ', endInput)),
    video,
    el('div', { class: 'row' }, setStart, setEnd, playClip),
    el('div', { class: 'row' }, buildBtn),
    status);
}

// ---------- progress ----------

async function renderProgress() {
  loadingView();
  try {
    const s = await Api.progress(learnerId());

    const nodes = [
      el('h1', { text: 'My progress' }),
      el('p', { text: `${s.correct} of ${s.attempts} attempts were correct.` }),
      el('p', { text: s.advice }),
    ];

    if (s.attempts > 0) {
      const rows = Object.entries(s.byParameter).map(([name, p]) =>
        el('tr', {},
          el('td', { text: PARAM_LABELS[name] || name }),
          el('td', { text: p.averageScore === null ? '—' : `${p.averageScore}/100` }),
          el('td', { text: String(p.mistakes) })));
      nodes.push(
        el('h2', { text: 'By parameter' }),
        el('table', {},
          el('thead', {}, el('tr', {},
            el('th', { text: 'Parameter' }), el('th', { text: 'Average score' }), el('th', { text: 'Flagged attempts' }))),
          el('tbody', {}, rows)));

      if (s.recommendedSigns.length) {
        nodes.push(
          el('h2', { text: 'Practise next' }),
          el('ul', { class: 'list' }, s.recommendedSigns.map((r) =>
            el('li', {}, el('a', { href: `#/sign/${r.signId}`, text: r.name }),
              el('span', { class: 'muted', text: ` · ${r.incorrectAttempts} incorrect attempt(s)` })))));
      }

      if (s.recurringMistakes.length) {
        nodes.push(
          el('h2', { text: 'Recurring mistakes' }),
          el('ul', { class: 'list' }, s.recurringMistakes.map((m) =>
            el('li', { text: `${m.name}: ${PARAM_LABELS[m.parameter] || m.parameter} (${m.count} times)` }))));
      }

      nodes.push(
        el('h2', { text: 'Recent attempts' }),
        el('ul', { class: 'list' }, s.recent.map((a) =>
          el('li', { text: `${a.name}: ${a.overall}/100, ${a.correct ? 'correct' : 'not yet'}` }))));
    } else {
      nodes.push(el('p', {}, el('a', { href: '#/', text: 'Choose a sign to start practising.' })));
    }

    nodes.push(el('div', { class: 'danger-zone' },
      el('h2', { text: 'Your data' }),
      el('p', { class: 'muted', text: 'Deleting removes every saved score for this device. Videos are never stored, so there is nothing else to delete.' }),
      el('button', {
        class: 'btn danger', type: 'button', text: 'Delete my saved scores',
        onclick: async (event) => {
          if (!confirm('Delete all your saved scores? This cannot be undone.')) return;
          event.currentTarget.disabled = true;
          try {
            await Api.deleteLearner(learnerId());
            forgetLearnerId();
            renderProgress();
          } catch (err) {
            alert(err.message);
            event.currentTarget.disabled = false;
          }
        },
      })));

    setView(...nodes);
  } catch (err) {
    setView(alertBox(err.message));
  }
}

// ---------- shared camera controls ----------

/**
 * Camera panel with start, record and stop buttons.
 * onRecorded(frames, statusEl) receives the landmark frames after a countdown and recording.
 */
function cameraBlock({ recordLabel, enabled, onRecorded, onBeforeRecord }) {
  const video = el('video', { playsinline: true, muted: true, autoplay: true });
  const overlay = el('canvas', { class: 'overlay', 'aria-hidden': 'true' });
  const status = el('p', { class: 'status', role: 'status', text: 'Camera is off.' });
  const startBtn = el('button', { class: 'btn primary', type: 'button', text: 'Turn on camera' });
  const recordBtn = el('button', { class: 'btn primary', type: 'button', text: recordLabel });
  const stopBtn = el('button', { class: 'btn', type: 'button', text: 'Turn off camera' });
  recordBtn.disabled = true;
  stopBtn.disabled = true;

  const videoWrap = el('div', { class: 'video-wrap mirrored' }, video, overlay);
  const mirror = el('input', { type: 'checkbox', checked: true });
  mirror.onchange = () => videoWrap.classList.toggle('mirrored', mirror.checked);
  const mirrorLabel = el('label', { class: 'mirror-toggle' }, mirror, ' Mirror camera');
  let camera = null;

  function turnOff(message = 'Camera is off.') {
    stopCamera();
    camera = null;
    startBtn.disabled = false;
    stopBtn.disabled = true;
    recordBtn.disabled = true;
    status.textContent = message;
  }

  startBtn.onclick = async () => {
    startBtn.disabled = true;
    status.textContent = 'Starting camera…';
    const cam = new HandCamera(video, overlay, (visible) => {
      if (!cam.recording) {
        status.textContent = visible ? 'Hand detected.' : 'No hand detected. Move your hand into view.';
      }
    });
    try {
      await cam.start();
      camera = cam;
      currentCamera = cam;
      stopBtn.disabled = false;
      recordBtn.disabled = !enabled;
      status.textContent = enabled ? 'Camera on. Show your hand, then record.' : 'Camera on.';
    } catch (err) {
      cam.stop();
      startBtn.disabled = false;
      status.textContent = err.message;
    }
  };

  stopBtn.onclick = () => turnOff();

  recordBtn.onclick = async () => {
    if (!camera) return;
    recordBtn.disabled = true;
    if (onBeforeRecord) onBeforeRecord();
    try {
      for (let s = COUNTDOWN_SECONDS; s > 0; s--) {
        status.textContent = `Get ready… ${s}`;
        await sleep(1000);
      }
      status.textContent = 'Recording… perform the sign now.';
      camera.startRecording();
      await sleep(RECORD_MS);
      const frames = camera.stopRecording();
      await onRecorded(frames, status);
    } catch (err) {
      status.textContent = err.message;
    } finally {
      recordBtn.disabled = !camera;
    }
  };

  const controls = el('div', { class: 'camera-block' },
    status,
    el('div', { class: 'row' }, startBtn, recordBtn, stopBtn),
    mirrorLabel);

  return { videoWrap, controls, video, overlay, turnOff, status };
}

// Restricts playback to the seconds the instructor chose, even though the whole file is stored.
function limitVideoToClip(video, start, end) {
  video.addEventListener('loadedmetadata', () => { video.currentTime = start; });
  video.addEventListener('seeking', () => {
    if (video.currentTime < start) video.currentTime = start;
    else if (video.currentTime > end) video.currentTime = end;
  });
  video.addEventListener('play', () => {
    if (video.currentTime >= end - 0.05 || video.currentTime < start) video.currentTime = start;
  });
  const watch = () => {
    if (!video.isConnected) return;
    if (!video.paused && video.currentTime >= end) {
      video.pause();
      video.currentTime = start;
    }
    requestAnimationFrame(watch);
  };
  requestAnimationFrame(watch);
}

// Tracks the instructor's actual uploaded video and draws landmarks over each moving frame.
function startReferenceOverlay(video, canvas) {
  if (typeof Hands === 'undefined') return;
  const tracker = new Hands({ locateFile: (file) => `${HAND_MODEL_CDN}/${file}` });
  tracker.setOptions({ maxNumHands: 2, modelComplexity: 1, minDetectionConfidence: 0.45, minTrackingConfidence: 0.45 });
  const ctx = canvas.getContext('2d');
  let stopped = false;
  let busy = false;
  let raf = 0;
  const resize = () => {
    const w = video.videoWidth || 640, h = video.videoHeight || 360;
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  };
  tracker.onResults((results) => {
    resize(); ctx.clearRect(0, 0, canvas.width, canvas.height);
    (results.multiHandLandmarks || []).forEach((points) => {
      if (typeof drawConnectors === 'function' && typeof drawLandmarks === 'function') {
        drawConnectors(ctx, points, HAND_CONNECTIONS, { color: '#27d7c4', lineWidth: 3 });
        drawLandmarks(ctx, points, { color: '#ffcc4d', lineWidth: 1, radius: 3 });
      }
    });
  });

 const processFrame = async () => {
  if (stopped || video.readyState < 2 || busy) return;

  busy = true;

  try {
    await tracker.send({ image: video });
  } catch (_) {
    // Ignore frames that cannot be processed.
  } finally {
    busy = false;
  }
};

const tick = () => {
  if (stopped) return;

  if (!video.paused && !video.ended) {
    processFrame();
  }

  raf = requestAnimationFrame(tick);
};

video.addEventListener('loadedmetadata', resize);
video.addEventListener('seeked', processFrame);

  raf = requestAnimationFrame(tick);
  referenceDemoCleanup = () => {
    stopped = true; cancelAnimationFrame(raf); video.pause(); tracker.close();
    canvas.getContext('2d').clearRect(0, 0, canvas.width, canvas.height);
  };
}

// ---------- drawing ----------

// Draws a model hand from stored landmarks. Points are rescaled to fill the canvas.
function drawModel(canvas, frame) {
  const ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const xs = frame.map((p) => p[0]);
  const ys = frame.map((p) => p[1]);
  const minX = Math.min(...xs), maxX = Math.max(...xs);
  const minY = Math.min(...ys), maxY = Math.max(...ys);
  const size = Math.max(maxX - minX, maxY - minY) || 1;
  const scale = 0.8 / size;
  const points = frame.map(([x, y, z]) => ({
    x: (x - minX) * scale + 0.1,
    y: (y - minY) * scale + 0.1,
    z,
  }));
  // Drawing helpers come from MediaPipe. If that script is unavailable, draw plain dots so the page still works.
  if (typeof drawConnectors !== 'function' || typeof drawLandmarks !== 'function') {
    ctx.fillStyle = '#0b7285';
    for (const p of points) {
      ctx.beginPath();
      ctx.arc(p.x * canvas.width, p.y * canvas.height, 4, 0, 2 * Math.PI);
      ctx.fill();
    }
    return;
  }
  const connections = typeof HAND_CONNECTIONS !== 'undefined' ? HAND_CONNECTIONS : [];
  drawConnectors(ctx, points, connections, { color: '#495057', lineWidth: 4 });
  drawLandmarks(ctx, points, { color: '#0b7285', lineWidth: 1, radius: 4 });
}

// ---------- start ----------

route();
