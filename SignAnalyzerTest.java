package dalil;

import java.util.Map;

/**
 * Unit tests with synthetic hands. No test framework needed:
 *   javac -d out src/main/java/dalil/*.java && javac -d out-test -cp out src/test/java/dalil/*.java
 *   java -cp out:out-test dalil.SignAnalyzerTest
 */
public final class SignAnalyzerTest {

    private static int checks = 0;
    private static int failures = 0;

    // Per-finger sideways offset of the knuckle direction, so the palm has a well-defined normal.
    private static final double[] LATERAL = {-1.0, -0.5, 0.0, 0.25, 0.5};
    private static final double L = 0.1; // hand size in image units

    public static void main(String[] args) {
        double[] open = {1.6, 2.0, 2.0, 2.0, 2.0};

        Features ref = SignAnalyzer.extract(sequence(20, open, 0.5, 0.5, 0.2));

        SignAnalyzer.AttemptResult same = SignAnalyzer.compare(ref,
                SignAnalyzer.extract(sequence(20, open, 0.5, 0.5, 0.2)));
        check(same.correct() && same.overall() >= 95, "identical attempt is correct (overall " + same.overall() + ")");

        double[] curled = {0.9, 0.9, 0.9, 0.9, 0.9};
        SignAnalyzer.AttemptResult fist = SignAnalyzer.compare(ref,
                SignAnalyzer.extract(sequence(20, curled, 0.5, 0.5, 0.2)));
        check(!fist.correct(), "curled hand is not correct");
        check(message(fist, "handshape").contains("more extended"),
                "curled hand gets a handshape hint: " + message(fist, "handshape"));

        SignAnalyzer.AttemptResult shifted = SignAnalyzer.compare(ref,
                SignAnalyzer.extract(sequence(20, open, 0.7, 0.5, 0.2)));
        check(message(shifted, "location").contains("right"),
                "hand too far toward the signer's left gets 'move toward your right' hint: " + message(shifted, "location"));
        check(message(shifted, "handshape").isEmpty(), "shifted hand keeps handshape correct");

        SignAnalyzer.AttemptResult still = SignAnalyzer.compare(ref,
                SignAnalyzer.extract(sequence(20, open, 0.5, 0.5, 0.0)));
        check(message(still, "movement").contains("still"),
                "static hand gets movement hint: " + message(still, "movement"));

        boolean threw = false;
        try {
            SignAnalyzer.extract(sequence(3, open, 0.5, 0.5, 0.2));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "three frames are rejected");

        Features restored = Features.fromMap(Json.asMap(Json.parse(Json.stringify(ref.toMap()))));
        check(Math.abs(restored.pathLength() - ref.pathLength()) < 1e-9
                        && Math.abs(restored.handshape()[0] - ref.handshape()[0]) < 1e-9,
                "features survive JSON round trip");

        Object parsed = Json.parse("{\"a\": [1, 2.5, \"x\\n\", true, null], \"b\": {}}");
        check(Json.stringify(parsed).equals("{\"a\":[1,2.5,\"x\\n\",true,null],\"b\":{}}"),
                "JSON parser and writer agree: " + Json.stringify(parsed));

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static String message(SignAnalyzer.AttemptResult r, String name) {
        for (SignAnalyzer.ParamResult p : r.params()) {
            if (p.name().equals(name)) {
                return p.message() == null ? "" : p.message();
            }
        }
        throw new AssertionError("no parameter " + name);
    }

    /** One hand pose. ratio[f] = fingertip distance / knuckle distance for finger f. */
    static double[][] hand(double[] ratio, double wx, double wy) {
        double[][] p = new double[SignAnalyzer.HAND_POINTS][3];
        for (double[] pt : p) {
            pt[0] = wx;
            pt[1] = wy;
        }
        for (int f = 0; f < 5; f++) {
            double[] d = normalize(new double[]{LATERAL[f], -1, 0});
            double[] knuckle = {wx + L * d[0], wy + L * d[1], 0};
            double[] tip = {wx + ratio[f] * L * d[0], wy + ratio[f] * L * d[1], 0};
            p[SignAnalyzer.MCP[f]] = knuckle;
            p[SignAnalyzer.TIP[f]] = tip;
            for (int k = 1; k <= 2; k++) {
                int idx = SignAnalyzer.MCP[f] + k;
                if (idx < SignAnalyzer.HAND_POINTS && idx != SignAnalyzer.TIP[f]) {
                    double t = k / 3.0;
                    p[idx] = new double[]{knuckle[0] + t * (tip[0] - knuckle[0]),
                            knuckle[1] + t * (tip[1] - knuckle[1]), 0};
                }
            }
        }
        return p;
    }

    /** Wrist moves linearly by dx over n frames. */
    static double[][][] sequence(int n, double[] ratio, double wx, double wy, double dx) {
        double[][][] frames = new double[n][][];
        for (int t = 0; t < n; t++) {
            double u = (double) t / (n - 1);
            frames[t] = hand(ratio, wx + dx * u, wy);
        }
        return frames;
    }

    private static double[] normalize(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / n, v[1] / n, v[2] / n};
    }

    private static void check(boolean cond, String msg) {
        checks++;
        if (cond) {
            System.out.println("ok    " + msg);
        } else {
            failures++;
            System.out.println("FAIL  " + msg);
        }
    }
}
