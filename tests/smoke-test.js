// End-to-end API check. Start the server first, then run:
//   node tests/smoke-test.js [http://localhost:8080]
// It records a synthetic model for a sign that has none, scores attempts against it,
// checks the progress summary, then removes the synthetic model and the learner's data.
// Synthetic hands are only for testing the pipeline. They are not sign-language data.

const fs = require('fs');
const path = require('path');

const BASE = process.argv[2] || 'http://localhost:8080';
const SIGN = 'example-c';
const LEARNER = 'smoke-' + Date.now().toString(36) + '-test';
const REF_FILE = path.join(__dirname, '..', 'data', 'references', `${SIGN}.json`);

let passed = 0;
let failed = 0;
function check(cond, msg) {
  if (cond) { passed++; console.log('ok    ' + msg); }
  else { failed++; console.log('FAIL  ' + msg); }
}

// Same geometry as the Java unit tests: one hand pose from fingertip/knuckle ratios.
function hand(ratio, wx, wy) {
  const L = 0.1;
  const lateral = [-1.0, -0.5, 0.0, 0.25, 0.5];
  const pts = Array.from({ length: 21 }, () => [wx, wy, 0]);
  const mcp = [2, 5, 9, 13, 17], tip = [4, 8, 12, 16, 20];
  for (let f = 0; f < 5; f++) {
    const len = Math.hypot(lateral[f], -1);
    const dx = lateral[f] / len, dy = -1 / len;
    const knuckle = [wx + L * dx, wy + L * dy, 0];
    const fingertip = [wx + ratio[f] * L * dx, wy + ratio[f] * L * dy, 0];
    pts[mcp[f]] = knuckle;
    pts[tip[f]] = fingertip;
  }
  return pts;
}

function sequence(n, ratio, wx, wy, dx) {
  return Array.from({ length: n }, (_, t) => hand(ratio, wx + dx * (t / (n - 1)), wy));
}

const INSTRUCTOR = 'Basic ' + Buffer.from(
  (process.env.DALIL_INSTRUCTOR_USER || 'instructor') + ':' + (process.env.DALIL_INSTRUCTOR_PASSWORD || 'change-me')).toString('base64');

async function api(method, url, body, auth) {
  const headers = body ? { 'Content-Type': 'application/json' } : {};
  if (auth) headers.Authorization = auth;
  const res = await fetch(BASE + url, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await res.json().catch(() => null);
  return { status: res.status, json };
}

(async () => {
  const home = await fetch(BASE + '/');
  check(home.status === 200, 'home page is served');

  const list = await api('GET', '/api/signs');
  check(list.status === 200 && list.json.signs.length >= 3, 'catalogue lists signs');

  const before = await api('GET', `/api/signs/${SIGN}`);
  check(before.status === 200, 'sign detail is served');
  if (before.json.hasReference) {
    console.log('skip  ' + SIGN + ' already has a model. Remove data/references/' + SIGN + '.json to run the full test.');
    console.log(`\n${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
  }

  const noModel = await api('POST', '/api/attempts', { learnerId: LEARNER, signId: SIGN, frames: sequence(10, [1, 1, 1, 1, 1], 0.5, 0.5, 0) });
  check(noModel.status === 409, 'attempt without a model is refused (409)');

  const open = [1.6, 2.0, 2.0, 2.0, 2.0];
  const model = sequence(20, open, 0.5, 0.5, 0.2);
  const noLogin = await api('POST', `/api/references/${SIGN}`, { frames: model });
  check(noLogin.status === 401, 'model save without instructor sign-in is refused (401)');
  const rec = await api('POST', `/api/references/${SIGN}`, { frames: model }, INSTRUCTOR);
  check(rec.status === 200 && rec.json.ok, 'model recording is saved');
  try {
    const bad = await api('POST', `/api/references/${SIGN}`, { frames: [[[0, 0, 0]]] }, INSTRUCTOR);
    check(bad.status === 400, 'too-short recording is rejected (400)');

    const same = await api('POST', '/api/attempts', { learnerId: LEARNER, signId: SIGN, frames: sequence(20, open, 0.5, 0.5, 0.2) });
    check(same.status === 200 && same.json.correct === true, `matching attempt is correct (overall ${same.json && same.json.overall})`);

    const fist = await api('POST', '/api/attempts', { learnerId: LEARNER, signId: SIGN, frames: sequence(20, [0.9, 0.9, 0.9, 0.9, 0.9], 0.5, 0.5, 0.2) });
    const hs = fist.json.params.find((p) => p.name === 'handshape');
    check(fist.json.correct === false && hs.message && hs.message.includes('more extended'), 'wrong handshape gets specific feedback');

    const moved = await api('POST', '/api/attempts', { learnerId: LEARNER, signId: SIGN, frames: sequence(20, open, 0.8, 0.5, 0.2) });
    const loc = moved.json.params.find((p) => p.name === 'location');
    check(loc.message && loc.message.includes('left'), 'hand in wrong place gets a location hint');

    const progress = await api('GET', `/api/progress/${LEARNER}`);
    check(progress.status === 200 && progress.json.attempts === 3 && progress.json.correct === 1,
      `progress counts 3 attempts, 1 correct (got ${progress.json.attempts}/${progress.json.correct})`);
    check(progress.json.focus !== null, `progress names a focus area: ${progress.json.focus}`);

    const badLearner = await api('GET', '/api/progress/x');
    check(badLearner.status === 400, 'short learner id is rejected (400)');

    const traversal = await fetch(BASE + '/..%2F..%2Fetc%2Fpasswd');
    check(traversal.status === 404, 'path traversal is blocked (404)');

    const del = await api('DELETE', `/api/learners/${LEARNER}`);
    check(del.status === 200 && del.json.deleted === true, 'learner data can be deleted');
    const after = await api('GET', `/api/progress/${LEARNER}`);
    check(after.json.attempts === 0, 'progress is empty after deletion');
  } finally {
    // Remove the synthetic model so the project ships without fake sign data.
    if (fs.existsSync(REF_FILE)) fs.unlinkSync(REF_FILE);
    await api('DELETE', `/api/learners/${LEARNER}`);
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
})().catch((err) => {
  console.error('Smoke test crashed:', err);
  process.exit(1);
});
