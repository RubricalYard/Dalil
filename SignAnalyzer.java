package dalil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares a learner's hand sequence with a recorded reference on four parameters:
 * handshape, orientation, location and movement. Works on hand landmarks only, so no video is needed.
 *
 * Landmark indices follow MediaPipe Hands (0 = wrist, 4/8/12/16/20 = fingertips,
 * 2/5/9/13/17 = knuckles of thumb, index, middle, ring and little finger).
 */
public final class SignAnalyzer {

    public static final int HAND_POINTS = 21;
    public static final int MIN_FRAMES = 5;
    public static final int MAX_FRAMES = 300;
    /** Overall score needed to count an attempt as correct. */
    public static final int PASS_SCORE = 70;
    /** Every parameter must reach this score for an attempt to count as correct. */
    public static final int MIN_PARAM_SCORE = 50;

    static final int TRAJECTORY_POINTS = 24;
    static final String[] FINGERS = {"Thumb", "Index finger", "Middle finger", "Ring finger", "Little finger"};
    static final int[] MCP = {2, 5, 9, 13, 17};
    static final int[] TIP = {4, 8, 12, 16, 20};

    /** Score (0-100) and feedback for one parameter. message is null when the parameter is fine. */
    public record ParamResult(String name, int score, String message, Map<String, Object> detail) {
        public ParamResult(String name, int score, String message) {
            this(name, score, message, null);
        }
    }

    public record AttemptResult(int overall, boolean correct, List<ParamResult> params) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("overall", overall);
            m.put("correct", correct);
            List<Object> ps = new ArrayList<>();
            for (ParamResult p : params) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", p.name());
                pm.put("score", p.score());
                pm.put("message", p.message());
                if (p.detail() != null) {
                    pm.put("detail", p.detail());
                }
                ps.add(pm);
            }
            m.put("params", ps);
            return m;
        }
    }

    private SignAnalyzer() {
    }

    /** Builds comparable features from a sequence of frames, each with 21 landmarks of (x, y, z). */
    public static Features extract(double[][][] frames) {
        int n = frames.length;
        if (n < MIN_FRAMES) {
            throw new IllegalArgumentException("The hand was detected in only " + n + " frames (need at least "
                    + MIN_FRAMES + "). Keep your whole hand in view and try again.");
        }
        double[] shape = new double[5];
        double[] palm = new double[3];
        double[] fingers = new double[3];
        double[] wrist = new double[2];
        double[][] path = new double[n][2];
        double handSize = 0;

        for (int t = 0; t < n; t++) {
            double[][] p = frames[t];
            double[] w = p[0];
            for (int f = 0; f < 5; f++) {
                double knuckle = dist(p[MCP[f]], w);
                shape[f] += knuckle > 1e-9 ? dist(p[TIP[f]], w) / knuckle : 0;
            }
            add(palm, normalize(cross(sub(p[5], w), sub(p[17], w))));
            add(fingers, normalize(sub(p[9], w)));
            wrist[0] += w[0];
            wrist[1] += w[1];
            handSize += dist(w, p[9]);
            path[t][0] = w[0];
            path[t][1] = w[1];
        }

        handSize /= n;
        if (handSize < 1e-4) {
            throw new IllegalArgumentException("Hand landmarks look invalid. Please try again.");
        }
        for (int f = 0; f < 5; f++) {
            shape[f] /= n;
        }
        wrist[0] /= n;
        wrist[1] /= n;

        // Wrist path starts at the origin and is scaled by hand size, so distance from the camera does not matter.
        double[][] rel = new double[n][2];
        double length = 0;
        for (int t = 0; t < n; t++) {
            rel[t][0] = (path[t][0] - path[0][0]) / handSize;
            rel[t][1] = (path[t][1] - path[0][1]) / handSize;
            if (t > 0) {
                length += dist(rel[t], rel[t - 1]);
            }
        }

        double[][] keyFrame = new double[HAND_POINTS][];
        for (int k = 0; k < HAND_POINTS; k++) {
            keyFrame[k] = frames[n / 2][k].clone();
        }

        return new Features(shape, normalize(palm), normalize(fingers), wrist,
                resample(rel, TRAJECTORY_POINTS), length, handSize, keyFrame);
    }

    /** Scores an attempt against a reference. Feedback is given per parameter. */
    public static AttemptResult compare(Features ref, Features att) {
        List<ParamResult> params = new ArrayList<>();
        params.add(handshape(ref, att));
        params.add(orientation(ref, att));
        params.add(location(ref, att));
        params.add(movement(ref, att));
        int overall = score(params.stream().mapToInt(ParamResult::score).average().orElse(0));
        boolean correct = overall >= PASS_SCORE && params.stream().allMatch(p -> p.score() >= MIN_PARAM_SCORE);
        return new AttemptResult(overall, correct, params);
    }

    static ParamResult handshape(Features r, Features a) {
        double total = 0;
        List<String> issues = new ArrayList<>();
        for (int f = 0; f < 5; f++) {
            double d = a.handshape()[f] - r.handshape()[f];
            total += Math.abs(d);
            if (Math.abs(d) > 0.25) {
                issues.add(FINGERS[f] + (d < 0 ? " should be more extended" : " should be more curled"));
            }
        }
        int s = score(100 * (1 - (total / 5) / 0.6));
        String msg = issues.isEmpty() ? null : "Adjust handshape: " + String.join("; ", issues) + ".";
        return new ParamResult("handshape", s, msg);
    }

    static ParamResult orientation(Features r, Features a) {
        double palm = angleDeg(r.palmNormal(), a.palmNormal());
        double fingers = angleDeg(r.fingerDir(), a.fingerDir());
        double avg = (palm + fingers) / 2;
        int s = score(100 * (1 - avg / 60));
        String msg = null;
        if (palm > 120) {
            msg = "Your palm faces the opposite way from the model. Check you are using the same hand and palm side.";
        } else if (avg > 25) {
            if (fingers >= palm) {
                double dx = a.fingerDir()[0] - r.fingerDir()[0];
                double dy = a.fingerDir()[1] - r.fingerDir()[1];
                if (Math.abs(dx) >= Math.abs(dy)) {
                    msg = dx > 0
                            ? "Your fingers point too far toward your left; angle them more toward your right."
                            : "Your fingers point too far toward your right; angle them more toward your left.";
                } else {
                    msg = dy > 0
                            ? "Your fingers point too far down; angle them more upward."
                            : "Your fingers point too far up; angle them more downward.";
                }
            } else {
                msg = "Your palm is about " + Math.round(palm)
                        + " degrees off the model; rotate it to match the model.";
            }
        }
        return new ParamResult("orientation", s, msg);
    }

    static ParamResult location(Features r, Features a) {
        double dx = a.wristPos()[0] - r.wristPos()[0];
        double dy = a.wristPos()[1] - r.wristPos()[1];
        double d = Math.hypot(dx, dy);
        int s = score(100 * (1 - d / 0.25));
        String msg = null;
        if (d > 0.06) {
            if (Math.abs(dx) >= Math.abs(dy)) {
                msg = dx > 0 ? "Move your hand toward your right." : "Move your hand toward your left.";
            } else {
                msg = dy > 0 ? "Move your hand up." : "Move your hand down.";
            }
        }
        return new ParamResult("location", s, msg);
    }

    /** A path whose bounding box is smaller than this (in palm-lengths) counts as "hand held still". */
    static final double STILL_EXTENT = 0.5;

    static ParamResult movement(Features r, Features a) {
        double[][] rt = r.trajectory();
        double[][] at = a.trajectory();
        double err = 0;
        double worstErr = -1;
        int worst = 0;
        for (int k = 0; k < TRAJECTORY_POINTS; k++) {
            double e = dist(rt[k], at[k]);
            err += e;
            if (e > worstErr) {
                worstErr = e;
                worst = k;
            }
        }
        err /= TRAJECTORY_POINTS;
        int s = score(100 * (1 - err / 0.6));

        // Sizes are in palm-lengths (wrist to base of the middle finger), so camera distance does not matter.
        double modelSize = extent(rt);
        double yourSize = extent(at);
        boolean modelMoves = modelSize >= STILL_EXTENT;
        boolean youMove = yourSize >= STILL_EXTENT;
        String modelDesc = describeMove(rt, modelSize);
        String yourDesc = describeMove(at, yourSize);

        String msg = null;
        if (modelMoves && !youMove) {
            msg = "This sign has a movement, but your hand stayed almost still. The model's hand "
                    + modelDesc + ". Make the same movement.";
        } else if (!modelMoves && youMove) {
            msg = "This sign has almost no movement, but your hand moved. Keep your hand still while you sign.";
        } else if (modelMoves) {
            double ratio = yourSize / modelSize;
            if (ratio > 1.6) {
                msg = "Your movement is about " + oneDecimal(ratio) + " times bigger than the model's. The model's hand "
                        + modelDesc + ". Make your movement smaller.";
            } else if (ratio < 1 / 1.6) {
                msg = "Your movement is only about " + Math.round(ratio * 100) + "% of the model's size. The model's hand "
                        + modelDesc + ". Make your movement bigger.";
            } else if (err > 0.25) {
                msg = "The path of your hand does not match the model. The model's hand " + modelDesc
                        + ", but yours " + yourDesc + ". The biggest difference is " + whereInMove(worst) + ".";
            }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reference", chartPoints(rt));
        detail.put("attempt", chartPoints(at));
        detail.put("referenceSize", Math.round(modelSize * 10) / 10.0);
        detail.put("attemptSize", Math.round(yourSize * 10) / 10.0);
        return new ParamResult("movement", s, msg, detail);
    }

    /** Size of the smallest box that holds the whole path (its diagonal), in palm-lengths. */
    static double extent(double[][] t) {
        double minX = t[0][0];
        double maxX = t[0][0];
        double minY = t[0][1];
        double maxY = t[0][1];
        for (double[] p : t) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        return Math.hypot(maxX - minX, maxY - minY);
    }

    /** Plain-language description of a path, such as "moves in a straight line, heading to your left, about 2.0 palm-lengths". */
    static String describeMove(double[][] t, double extent) {
        double[] net = sub(t[t.length - 1], t[0]);
        double netLen = norm(net);
        double len = 0;
        double minX = t[0][0];
        double maxX = t[0][0];
        double minY = t[0][1];
        double maxY = t[0][1];
        for (int i = 1; i < t.length; i++) {
            len += dist(t[i], t[i - 1]);
            minX = Math.min(minX, t[i][0]);
            maxX = Math.max(maxX, t[i][0]);
            minY = Math.min(minY, t[i][1]);
            maxY = Math.max(maxY, t[i][1]);
        }
        double straightness = len < 1e-9 ? 1 : netLen / len;
        String size = ", about " + (extent >= 3 ? String.valueOf(Math.round(extent)) : oneDecimal(extent))
                + " palm-lengths";

        if (straightness >= 0.7) {
            return "moves in a straight line, heading " + direction(net[0], net[1]) + size;
        }
        if (straightness < 0.35) {
            double w = maxX - minX;
            double h = maxY - minY;
            if (Math.min(w, h) >= 0.45 * Math.max(w, h)) {
                return "moves in a circle or loop" + size;
            }
            String axis = w >= 2 * h ? "sideways (left and right)" : h >= 2 * w ? "up and down" : "diagonally";
            return "moves back and forth " + axis + size;
        }
        return "moves in a curve, heading " + direction(net[0], net[1]) + size;
    }

    /**
     * Direction of a move from the signer's own point of view. In the camera image the signer's right
     * side is on the left (smaller x), and image y grows downward.
     */
    static String direction(double dx, double dy) {
        double deg = Math.toDegrees(Math.atan2(-dy, -dx));
        if (deg < 0) {
            deg += 360;
        }
        String[] names = {"to your right", "up and to your right", "up", "up and to your left",
                "to your left", "down and to your left", "down", "down and to your right"};
        return names[(int) Math.round(deg / 45.0) % 8];
    }

    static String whereInMove(int k) {
        double u = (double) k / (TRAJECTORY_POINTS - 1);
        if (u < 0.34) {
            return "near the start of the movement";
        }
        return u < 0.67 ? "in the middle of the movement" : "near the end of the movement";
    }

    static String oneDecimal(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Path points for the result chart: x grows toward the signer's right, y grows downward. */
    static List<Object> chartPoints(double[][] t) {
        List<Object> out = new ArrayList<>();
        for (double[] p : t) {
            List<Object> pt = new ArrayList<>();
            pt.add(-p[0] + 0.0);
            pt.add(p[1] + 0.0);
            out.add(pt);
        }
        return out;
    }

    // ---------- geometry helpers ----------

    /** Resamples a polyline to m points evenly spaced by arc length. */
    static double[][] resample(double[][] pts, int m) {
        double[] cum = new double[pts.length];
        for (int i = 1; i < pts.length; i++) {
            cum[i] = cum[i - 1] + dist(pts[i], pts[i - 1]);
        }
        double total = cum[pts.length - 1];
        double[][] out = new double[m][2];
        if (total < 1e-9) {
            for (double[] o : out) {
                o[0] = pts[0][0];
                o[1] = pts[0][1];
            }
            return out;
        }
        int j = 0;
        for (int k = 0; k < m; k++) {
            double target = total * k / (m - 1);
            while (j < pts.length - 2 && cum[j + 1] < target) {
                j++;
            }
            double seg = cum[j + 1] - cum[j];
            double a = seg < 1e-12 ? 0 : (target - cum[j]) / seg;
            out[k][0] = pts[j][0] + a * (pts[j + 1][0] - pts[j][0]);
            out[k][1] = pts[j][1] + a * (pts[j + 1][1] - pts[j][1]);
        }
        return out;
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] sub(double[] a, double[] b) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] - b[i];
        }
        return r;
    }

    static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    static double[] normalize(double[] a) {
        double n = norm(a);
        double[] r = new double[a.length];
        if (n < 1e-12) {
            return r;
        }
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] / n;
        }
        return r;
    }

    static double dist(double[] a, double[] b) {
        return norm(sub(a, b));
    }

    static void add(double[] acc, double[] v) {
        for (int i = 0; i < acc.length; i++) {
            acc[i] += v[i];
        }
    }

    static double angleDeg(double[] a, double[] b) {
        double na = norm(a);
        double nb = norm(b);
        if (na < 1e-12 || nb < 1e-12) {
            return 0;
        }
        double c = dot(a, b) / (na * nb);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }

    static int score(double v) {
        return (int) Math.round(Math.max(0, Math.min(100, v)));
    }
}
