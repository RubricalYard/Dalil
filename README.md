# Dalil

**Practice Emirati Sign Language (ESL) with feedback on what you got wrong.**

Dalil is a web app that watches a learner perform a sign through the webcam and tells them which part of the sign needs fixing: the handshape, the hand orientation, the location, or the movement. It keeps a record of each learner's scores and suggests signs to practise again.

Course project for SWE 401: Software Engineering, Abu Dhabi University, Fall 2026 (instructor: Dr. Murad Al-Rajab).

## Team

| Member | GitHub | Main areas |
| --- | --- | --- |
| Mira Mohammad Pharaon | [@Mir4bel](https://github.com/Mir4bel) | Server, sign analysis, progress storage |
| Tasnim Amr Mesailhy | [@nemoooo3](https://github.com/nemoooo3) | Web interface, camera and hand tracking |
| Aaron Gabriel Clemente | [@RubricalYard](https://github.com/RubricalYard) | Sign data and reference models, tests, documentation |

## The problem

A sign is only correct when its handshape, orientation, location and movement are all right. Learners practising alone usually can't tell which of these they got wrong, and existing tools either show the correct sign without checking the learner's attempt, or give feedback for other sign languages (such as ASL) rather than ESL.

## Key features

- **Sign catalogue**: 67 signs with search and a category filter.
- **Demonstrations**: the instructor's clip with a hand-landmark overlay, playable at 0.5x to 1.25x speed (7 signs have a clip; the rest link to their YouTube source).
- **Practice with feedback**: a 3-second countdown, a 4-second recording, then a score out of 100 for each of the four parameters, with a plain-language hint for each one that needs work and a chart comparing the movement paths.
- **"Unable to assess"**: attempts with fewer than 5 tracked frames are refused instead of being scored.
- **Progress**: averages per parameter, the parameter to focus on, recurring mistakes, recommended signs and recent attempts.
- **Privacy**: video never leaves the browser. Only hand-point coordinates are sent to the server, learners have no accounts (just an anonymous ID kept in the browser), and saved scores can be deleted from the progress page.
- **Instructor page**: record a sign's model from the webcam, or build it from a clip of a video file.

## How it works

```
Browser                                     Java server (localhost only)
-------                                     ----------------------------
webcam -> MediaPipe Hands (in browser)
       -> 21 hand points per frame   --->   SignAnalyzer: extract features,
          (no video is sent)                compare with the instructor model,
                                            score 4 parameters
       <--- scores and hints          <---  ProgressStore: save the attempt
```

The only machine-learning component is [MediaPipe Hands](https://arxiv.org/abs/2006.10214), a pre-trained hand-landmark model that runs in the browser. The error diagnosis itself is rule-based geometry in `SignAnalyzer.java` (finger extension ratios, palm and finger directions, wrist position and a 24-point wrist path, all scaled by hand size). An attempt counts as correct when its overall score is at least 70 and every parameter scores at least 50.

## Technologies

- **Server**: Java 21 using the JDK's built-in HTTP server, with no external libraries.
- **Front end**: HTML, CSS and plain JavaScript.
- **Hand tracking**: MediaPipe Hands 0.4, loaded from the jsDelivr CDN.
- **Storage**: JSON files in `data/`, plus browser local storage for the anonymous learner ID.
- **Tests**: a plain Java test runner for the unit tests and a Node.js script for the API tests.

## Prerequisites

- **JDK 21** or newer (`java -version` should show 21+).
- **Node.js 18** or newer, only needed for the API tests (it uses the built-in `fetch`).
- A modern browser (Chrome, Edge, Firefox or Safari) and a webcam.
- **Internet access in the browser**, because MediaPipe is loaded from `cdn.jsdelivr.net`. If a network blocks it, the practice page loads but hand tracking will not start.

## Installation

```bash
git clone https://github.com/RubricalYard/Dalil.git
cd Dalil
javac -d out *.java
```

There are no packages to install. The server compiles with the JDK alone.

## Configuration

| Setting | How to set it | Default |
| --- | --- | --- |
| Instructor username | `DALIL_INSTRUCTOR_USER` environment variable | `instructor` |
| Instructor password | `DALIL_INSTRUCTOR_PASSWORD` environment variable | `change-me` (the server prints a warning if it is not set) |
| Port | first command-line argument | `8080` |
| Project folder | `-Ddalil.root=<path>` JVM option | current folder |

The instructor account is only needed to save sign models. Change the password before you use it:

```bash
# macOS / Linux
export DALIL_INSTRUCTOR_PASSWORD='choose-a-password'
```

```powershell
# Windows PowerShell
$env:DALIL_INSTRUCTOR_PASSWORD = 'choose-a-password'
```

## Running

```bash
java -cp out dalil.DalilServer          # or: java -cp out dalil.DalilServer 9000
```

Then open <http://localhost:8080> and allow camera access when the browser asks.

The server only accepts connections from the same computer (`127.0.0.1`). Browsers allow camera access on `localhost` without HTTPS.

**Using the app**

1. Pick a sign in the catalogue. Signs marked as having a model can be scored.
2. Watch the demonstration, then press **Turn on camera**.
3. Press **Record my sign**, wait for the countdown, and perform the sign while your hand stays in view.
4. Read the feedback, try again, and check **My progress** to see what to practise next.

To record a model, open a sign and choose **Record model (instructor)**, then sign in with the instructor account.

## Testing

**Unit tests** cover feature extraction, scoring, hints and JSON. They need no server or camera:

```bash
java -cp out dalil.SignAnalyzerTest
```

The last line of the output should be `9 checks, 0 failed`.

**API tests** check every endpoint, using synthetic hand data. Start the server in one terminal and run the tests in another:

```bash
java -cp out dalil.DalilServer
node tests/smoke-test.js
```

The last line of the output should be `16 passed, 0 failed`. The test temporarily adds a model for the `how-are-you` sign and deletes it again afterwards. If you changed the instructor password, set the same `DALIL_INSTRUCTOR_PASSWORD` in the test terminal. If the server runs on another port, pass its address: `node tests/smoke-test.js http://localhost:9000`.

## Project structure

```
DalilServer.java      HTTP server: routes, input validation, instructor check, static files
SignAnalyzer.java     feature extraction and scoring of the four parameters
Features.java         the feature record (used for models and attempts)
SignCatalogue.java    loads data/signs.json
ReferenceStore.java   instructor models, demonstration videos and clip times
ProgressStore.java    saved attempts, progress summary, recommendations, deletion
Json.java             small JSON parser and writer
SignAnalyzerTest.java unit tests
web/                  index.html, css/style.css, js/app.js, camera.js, api.js, video-model.js
data/signs.json       the sign catalogue (67 signs)
data/references/      instructor models (.json), demonstration videos and clip times (.clip)
data/progress.json    saved learner scores
tests/smoke-test.js   API tests
```

## API

All bodies are JSON unless stated otherwise.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/signs` | List all signs with model and video status |
| GET | `/api/signs/{id}` | Sign details and model key frame |
| GET | `/api/reference-videos/{id}` | Demonstration video |
| POST | `/api/reference-videos/{id}` | Upload a demonstration video (instructor, up to 50 MB) |
| POST | `/api/references/{id}` | Save a sign model from 5 to 300 frames (instructor) |
| GET | `/api/instructor/check` | Check instructor credentials |
| POST | `/api/attempts` | Score an attempt: `{ learnerId, signId, frames }` |
| GET | `/api/progress/{learnerId}` | Progress summary |
| DELETE | `/api/learners/{learnerId}` | Delete all of a learner's saved scores |

## Limitations

- Feedback is guidance, not a validated assessment. The scoring thresholds are first estimates that have not been checked against expert judgement.
- Only 7 of the 67 signs have models. They were built from public YouTube demonstration videos and have not been reviewed by a qualified ESL instructor.
- Both hands are drawn on screen, but only the first detected hand is scored. Signing with the opposite hand to the model may be marked wrong.
- Recommendations are signs to practise again, not separate exercises.
- Progress is stored on the computer running the server, so it does not sync across devices.

## Acknowledgements

- Hand tracking: [MediaPipe Hands](https://github.com/google-ai-edge/mediapipe) by Google.
- Demonstration clips: public YouTube videos linked from each sign (`sourceVideoUrl` in `data/signs.json`). They are included for this academic prototype only, and all rights belong to their owners.
