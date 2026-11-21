
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact description of one hand sequence, derived from hand landmarks.
 * Raw landmarks and video are never stored; only these summary values are.
 *
 * @param handshape    finger extension ratio per finger (thumb to little), tip distance / knuckle distance
 * @param palmNormal   unit vector perpendicular to the palm (averaged over frames)
 * @param fingerDir    unit vector from wrist towards the middle knuckle (averaged over frames)
 * @param wristPos     mean wrist position in image coordinates (0..1)
 * @param trajectory   wrist path relative to its start, scaled by hand size, resampled to fixed length
 * @param pathLength   total wrist path length in hand-size units
 * @param handSize     mean wrist-to-middle-knuckle distance in image units
 * @param keyFrame     one representative frame (21 landmarks) used to draw the model hand
 */
public record Features(double[] handshape, double[] palmNormal, double[] fingerDir, double[] wristPos,
                       double[][] trajectory, double pathLength, double handSize, double[][] keyFrame) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handshape", vec(handshape));
        m.put("palmNormal", vec(palmNormal));
        m.put("fingerDir", vec(fingerDir));
        m.put("wristPos", vec(wristPos));
        m.put("trajectory", mat(trajectory));
        m.put("pathLength", pathLength);
        m.put("handSize", handSize);
        m.put("keyFrame", mat(keyFrame));
        return m;
    }

    public static Features fromMap(Map<String, Object> m) {
        return new Features(
                readVec(m.get("handshape")),
                readVec(m.get("palmNormal")),
                readVec(m.get("fingerDir")),
                readVec(m.get("wristPos")),
                readMat(m.get("trajectory")),
                num(m.get("pathLength")),
                num(m.get("handSize")),
                readMat(m.get("keyFrame")));
    }

    static List<Object> vec(double[] v) {
        List<Object> l = new ArrayList<>();
        for (double d : v) {
            l.add(d);
        }
        return l;
    }

    static List<Object> mat(double[][] m) {
        List<Object> l = new ArrayList<>();
        for (double[] r : m) {
            l.add(vec(r));
        }
        return l;
    }

    static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("Expected a number");
    }

    static double[] readVec(Object o) {
        List<Object> l = Json.asList(o);
        double[] r = new double[l.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = num(l.get(i));
        }
        return r;
    }

    static double[][] readMat(Object o) {
        List<Object> l = Json.asList(o);
        double[][] r = new double[l.size()][];
        for (int i = 0; i < r.length; i++) {
            r[i] = readVec(l.get(i));
        }
        return r;
    }
}
