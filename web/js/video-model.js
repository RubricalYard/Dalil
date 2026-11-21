// Builds a sign model from a video file. The browser plays the chosen clip, runs MediaPipe Hands
// on sampled frames of the full picture and returns landmark frames in the same shape as the
// webcam recording. Use a clip with one signer in view.
const VIDEO_SAMPLE_FPS = 15;
const VIDEO_MAX_FRAMES = 150;         // same cap as the webcam recording
const VIDEO_CROP_SCALE = 2;           // enlarge the frame so small hands are easier to track

let videoHands = null;
let pendingResolve = null;

function getVideoHands() {
  if (videoHands) return videoHands;
  if (typeof Hands === 'undefined') {
    throw new Error('The hand-tracking library did not load. Check your internet connection and reload the page.');
  }
  videoHands = new Hands({ locateFile: (file) => `${HAND_MODEL_CDN}/${file}` });
  videoHands.setOptions({
    maxNumHands: 1,
    modelComplexity: 1,
    minDetectionConfidence: 0.5,
    minTrackingConfidence: 0.5,
  });
  videoHands.onResults((results) => {
    if (pendingResolve) {
      const resolve = pendingResolve;
      pendingResolve = null;
      resolve(results);
    }
  });
  return videoHands;
}

function seekVideo(video, time) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('The video stopped responding while reading frames.')), 5000);
    const done = () => {
      clearTimeout(timer);
      video.removeEventListener('seeked', done);
      resolve();
    };
    video.addEventListener('seeked', done);
    video.currentTime = time;
  });
}

function toFullFrame(landmarks) {
  return landmarks.map((p) => [p.x, p.y, p.z]);
}

/**
 * Extracts landmark frames from [start, end] seconds of the loaded video.
 * onProgress(done, total) is called after each sampled frame.
 */
async function extractFramesFromVideo(video, { start, end }, onProgress) {
  if (!video.videoWidth || !video.videoHeight) {
    throw new Error('The video has not loaded yet. Wait for it to load, then try again.');
  }
  if (!(end > start)) throw new Error('The end time must be after the start time.');
  const hands = getVideoHands();

  const srcW = video.videoWidth;
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(srcW * VIDEO_CROP_SCALE);
  canvas.height = Math.round(video.videoHeight * VIDEO_CROP_SCALE);
  const ctx = canvas.getContext('2d');

  const total = Math.min(VIDEO_MAX_FRAMES, Math.max(1, Math.floor((end - start) * VIDEO_SAMPLE_FPS)));
  const frames = [];
  video.pause();
  for (let i = 0; i < total; i++) {
    const t = start + ((end - start) * i) / total;
    await seekVideo(video, t);
    ctx.drawImage(video, 0, 0, srcW, video.videoHeight, 0, 0, canvas.width, canvas.height);
    const resultPromise = new Promise((resolve) => { pendingResolve = resolve; });
    await hands.send({ image: canvas });
    const results = await resultPromise;
    const landmarks = (results.multiHandLandmarks || [])[0];
    if (landmarks) frames.push(toFullFrame(landmarks));
    if (onProgress) onProgress(i + 1, total);
  }
  return frames;
}
