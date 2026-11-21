// Thin wrapper around the Dalil REST API. All paths are relative to the same origin.
const Api = (() => {
  async function request(method, url, body, headers) {
    const options = { method, headers: { ...(headers || {}) } };
    if (body !== undefined) {
      options.headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(body);
    }
    const res = await fetch(url, options);
    let data = null;
    try {
      data = await res.json();
    } catch (_) {
      // Response had no JSON body.
    }
    if (!res.ok) {
      const err = new Error((data && data.error) || `Request failed (${res.status}).`);
      err.status = res.status;
      throw err;
    }
    return data;
  }

  // auth is the value for the Authorization header, set after instructor sign-in.
  const authHeader = (auth) => (auth ? { Authorization: auth } : undefined);

  return {
    listSigns: () => request('GET', '/api/signs'),
    getSign: (id) => request('GET', `/api/signs/${encodeURIComponent(id)}`),
    submitAttempt: (learnerId, signId, frames) =>
      request('POST', '/api/attempts', { learnerId, signId, frames }),
    instructorCheck: (auth) => request('GET', '/api/instructor/check', undefined, authHeader(auth)),
    saveReference: (signId, frames, auth) =>
      request('POST', `/api/references/${encodeURIComponent(signId)}`, { frames }, authHeader(auth)),
    saveReferenceVideo: async (signId, file, auth, start, end) => {
      const range = (Number.isFinite(start) && Number.isFinite(end)) ? `?start=${start}&end=${end}` : '';
      const res = await fetch(`/api/reference-videos/${encodeURIComponent(signId)}${range}`, {
        method: 'POST', headers: { ...(auth ? { Authorization: auth } : {}), 'Content-Type': file.type || 'video/mp4' }, body: file
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) { const err = new Error(data.error || `Request failed (${res.status}).`); err.status = res.status; throw err; }
      return data;
    },
    progress: (learnerId) => request('GET', `/api/progress/${encodeURIComponent(learnerId)}`),
    deleteLearner: (learnerId) => request('DELETE', `/api/learners/${encodeURIComponent(learnerId)}`),
  };
})();
