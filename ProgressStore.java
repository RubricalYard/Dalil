
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves scores per learner in data/progress.json and builds the progress summary.
 * Stores scores and feedback categories only, never video or landmarks.
 */
public final class ProgressStore {

    private static final int MAX_ATTEMPTS_PER_LEARNER = 500;
    private static final List<String> PARAMS = List.of("handshape", "orientation", "location", "movement");
    private static final Map<String, String> LABELS = Map.of(
            "handshape", "handshape",
            "orientation", "hand orientation",
            "location", "hand location",
            "movement", "movement");

    private final Path file;
    private final Map<String, List<Object>> data = new LinkedHashMap<>();

    public ProgressStore(Path file) throws IOException {
        this.file = file;
        if (Files.isRegularFile(file)) {
            Map<String, Object> root = Json.asMap(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            for (Map.Entry<String, Object> e : root.entrySet()) {
                data.put(e.getKey(), Json.asList(e.getValue()));
            }
        }
    }

    public synchronized void addAttempt(String learnerId, String signId, SignAnalyzer.AttemptResult result)
            throws IOException {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("signId", signId);
        a.put("at", Instant.now().toString());
        a.put("overall", result.overall());
        a.put("correct", result.correct());
        Map<String, Object> params = new LinkedHashMap<>();
        for (SignAnalyzer.ParamResult p : result.params()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("score", p.score());
            pm.put("issue", p.message());
            params.put(p.name(), pm);
        }
        a.put("params", params);

        List<Object> list = data.computeIfAbsent(learnerId, k -> new ArrayList<>());
        list.add(a);
        while (list.size() > MAX_ATTEMPTS_PER_LEARNER) {
            list.remove(0);
        }
        save();
    }

    public synchronized Map<String, Object> summary(String learnerId, SignCatalogue catalogue) {
        List<Object> attempts = data.getOrDefault(learnerId, new ArrayList<>());
        int correct = 0;
        Map<String, int[]> stats = new LinkedHashMap<>(); // parameter -> {count, scoreSum, mistakes}
        for (String p : PARAMS) {
            stats.put(p, new int[3]);
        }
        Map<String, Integer> failsBySign = new LinkedHashMap<>();
        Map<String, Integer> recurring = new LinkedHashMap<>(); // "signId|parameter" -> count

        for (Object o : attempts) {
            Map<String, Object> a = Json.asMap(o);
            String signId = String.valueOf(a.get("signId"));
            if (Boolean.TRUE.equals(a.get("correct"))) {
                correct++;
            } else {
                failsBySign.merge(signId, 1, Integer::sum);
            }
            Map<String, Object> params = Json.asMap(a.get("params"));
            for (String p : PARAMS) {
                Map<String, Object> pm = Json.asMap(params.get(p));
                int[] st = stats.get(p);
                st[0]++;
                st[1] += ((Number) pm.get("score")).intValue();
                if (pm.get("issue") != null) {
                    st[2]++;
                    recurring.merge(signId + "|" + p, 1, Integer::sum);
                }
            }
        }



        List<Object> recommended = new ArrayList<>();
        failsBySign.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("signId", e.getKey());
                    m.put("incorrectAttempts", e.getValue());
                    recommended.add(m);
                });

        List<Object> repeated = new ArrayList<>();
        recurring.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> {
                    String[] parts = e.getKey().split("\\|");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("signId", parts[0]);
                    m.put("parameter", parts[1]);
                    m.put("count", e.getValue());
                    repeated.add(m);
                });

        List<Object> recent = new ArrayList<>();
        for (int i = attempts.size() - 1; i >= 0 && recent.size() < 10; i--) {
            Map<String, Object> a = Json.asMap(attempts.get(i));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("signId", a.get("signId"));
            m.put("at", a.get("at"));
            m.put("overall", a.get("overall"));
            m.put("correct", a.get("correct"));
            recent.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attempts", attempts.size());
        out.put("correct", correct);
        out.put("recommendedSigns", recommended);
        out.put("recurringMistakes", repeated);
        out.put("recent", recent);
        return out;
    }

    public synchronized boolean delete(String learnerId) throws IOException {
        boolean removed = data.remove(learnerId) != null;
        if (removed) {
            save();
        }
        return removed;
    }



    private void save() throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.stringify(data), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
