// Webcam plus MediaPipe Hands. Frames are processed in the browser. Only landmark
// coordinates are kept, and only while a recording is running.
const HAND_MODEL_CDN = 'https://cdn.jsdelivr.net/npm/@mediapipe/hands@0.4.1675469240';
const MAX_RECORD_FRAMES = 150; // about 5 seconds at 30 fps

class HandCamera {
  constructor(video, overlay, onHandChange) {
    this.video = video;
    this.overlay = overlay;
    this.onHandChange = onHandChange || (() => {});
    this.stream = null;
    this.hands = null;
    this.running = false;
    this.busy = false;
    this.recording = false;
    this.frames = [];
    this.lastHandVisible = null;
  }

  async start() {
    if (this.running) return;
    if (typeof Hands === 'undefined') {
      throw new Error('The hand-tracking library did not load. Check your internet connection and reload the page.');
    }
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      throw new Error('This browser cannot access the camera.');
    }
    this.stream = await navigator.mediaDevices.getUserMedia({
      video: { width: 640, height: 480, facingMode: 'user' },
      audio: false,
    });
    this.video.srcObject = this.stream;
    await this.video.play();

    this.hands = new Hands({ locateFile: (file) => `${HAND_MODEL_CDN}/${file}` });
    this.hands.setOptions({
      maxNumHands: 2,
      modelComplexity: 1,
      minDetectionConfidence: 0.6,
      minTrackingConfidence: 0.5,
    });
    this.hands.onResults((results) => this._onResults(results));

    this.running = true;
    this._tick();
  }

  stop() {
    this.running = false;
    this.recording = false;
    if (this.stream) {
      this.stream.getTracks().forEach((track) => track.stop());
      this.stream = null;
    }
    this.video.srcObject = null;
    if (this.hands) {
      try { this.hands.close(); } catch (_) { /* already closed */ }
      this.hands = null;
    }
    const ctx = this.overlay.getContext('2d');
    ctx.clearRect(0, 0, this.overlay.width, this.overlay.height);
  }

  startRecording() {
    this.frames = [];
    this.recording = true;
  }

  stopRecording() {
    this.recording = false;
    return this.frames;
  }

  async _tick() {
    if (!this.running) return;
    if (!this.busy && this.hands && this.video.readyState >= 2) {
      this.busy = true;
      try {
        await this.hands.send({ image: this.video });
      } catch (err) {
        console.warn('Skipped a video frame:', err);
      } finally {
        this.busy = false;
      }
    }
    requestAnimationFrame(() => this._tick());
  }

  _onResults(results) {
    const allHands = results.multiHandLandmarks || [];
    // Scoring uses the first hand only. Two-handed scoring is not implemented yet.
    const landmarks = allHands[0] || null;
    this._draw(allHands);

    if (this.recording && landmarks && this.frames.length < MAX_RECORD_FRAMES) {
      this.frames.push(landmarks.map((p) => [p.x, p.y, p.z]));
    }

    const visible = landmarks !== null;
    if (visible !== this.lastHandVisible) {
      this.lastHandVisible = visible;
      this.onHandChange(visible);
    }
  }

  _draw(allHands) {
    const w = this.video.videoWidth;
    const h = this.video.videoHeight;
    if (!w || !h) return;
    if (this.overlay.width !== w || this.overlay.height !== h) {
      this.overlay.width = w;
      this.overlay.height = h;
    }
    const ctx = this.overlay.getContext('2d');
    ctx.clearRect(0, 0, w, h);
    const connections = typeof HAND_CONNECTIONS !== 'undefined' ? HAND_CONNECTIONS : [];
    for (const landmarks of allHands) {
      drawConnectors(ctx, landmarks, connections, { color: '#3bc9db', lineWidth: 3 });
      drawLandmarks(ctx, landmarks, { color: '#ff922b', lineWidth: 1, radius: 3 });
    }
  }
}
