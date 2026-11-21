import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Dalil web server. Uses only the JDK (com.sun.net.httpserver), so it runs offline with no build tools.
 * Binds to 127.0.0.1 only, so it is not reachable from other machines on the network.
 *
 * Run from the project folder:  java -cp out dalil.DalilServer [port]
 */
public final class DalilServer {

    private static final Pattern LEARNER_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    // Instructor sign-in for saving models. Set these before starting the server.
    static final String INSTRUCTOR_USER = envOr("DALIL_INSTRUCTOR_USER", "instructor");
    static final String INSTRUCTOR_PASSWORD = envOr("DALIL_INSTRUCTOR_PASSWORD", "change-me");
    private static final int MAX_BODY_BYTES = 2_000_000;
    private static final int MAX_VIDEO_BYTES = 50_000_000;

    private final SignCatalogue catalogue;
    private final ReferenceStore references;
    private final ProgressStore progress;
    private final Path webRoot;

    private DalilServer(SignCatalogue catalogue, ReferenceStore references, ProgressStore progress, Path webRoot) {
        this.catalogue = catalogue;
        this.references = references;
        this.progress = progress;
        this.webRoot = webRoot;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path root = Paths.get(System.getProperty("dalil.root", ".")).toAbsolutePath().normalize();
        Path data = root.resolve("data");
        Files.createDirectories(data.resolve("references"));
        if (System.getenv("DALIL_INSTRUCTOR_PASSWORD") == null) {
            System.out.println("Warning: DALIL_INSTRUCTOR_PASSWORD is not set. Using the default instructor password 'change-me'.");
        }

        DalilServer app = new DalilServer(
                SignCatalogue.load(data.resolve("signs.json")),
                new ReferenceStore(data.resolve("references")),
                new ProgressStore(data.resolve("progress.json")),
                root.resolve("web").normalize());

        com.sun.net.httpserver.HttpServer server =
                HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Dalil is running at http://localhost:" + port);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                route(ex, path.substring("/api/".length()));
            } else {
                serveStatic(ex, path);
            }
        } catch (ApiException e) {
            sendJson(ex, e.status, Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e);
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", "Something went wrong on the server."));
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex, String rest) throws IOException {
        String method = ex.getRequestMethod();
        String[] parts = rest.split("/");

        if (parts.length == 1 && parts[0].equals("signs") && method.equals("GET")) {
            List<Object> list = new ArrayList<>();
            for (SignCatalogue.Sign s : catalogue.all()) {
                list.add(signView(s, false));
            }
            sendJson(ex, 200, Map.of("signs", list));

        } else if (parts.length == 2 && parts[0].equals("signs") && method.equals("GET")) {
            sendJson(ex, 200, signView(requireSign(parts[1]), true));

        } else if (parts.length == 2 && parts[0].equals("instructor") && parts[1].equals("check") && method.equals("GET")) {
            requireInstructor(ex);
            sendJson(ex, 200, Map.of("ok", true));

        } else if (parts.length == 2 && parts[0].equals("reference-videos") && method.equals("GET")) {
            SignCatalogue.Sign sign = requireSign(parts[1]);
            Path video = references.videoPath(sign.id());
            if (!Files.isRegularFile(video)) throw new ApiException(404, "No demonstration video for this sign.");
            String type = Files.probeContentType(video);
            ex.getResponseHeaders().set("Content-Type", type == null ? "video/mp4" : type);
            ex.getResponseHeaders().set("Accept-Ranges", "bytes");
            byte[] bytes = Files.readAllBytes(video);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }

        } else if (parts.length == 2 && parts[0].equals("reference-videos") && method.equals("POST")) {
            requireInstructor(ex);
            SignCatalogue.Sign sign = requireSign(parts[1]);
            String type = ex.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.startsWith("video/")) throw new ApiException(415, "Upload a video file.");
            byte[] bytes;
            try (InputStream in = ex.getRequestBody()) { bytes = in.readNBytes(MAX_VIDEO_BYTES + 1); }
            if (bytes.length < 100 || bytes.length > MAX_VIDEO_BYTES) throw new ApiException(413, "Video must be between 100 bytes and 50 MB.");
            references.putVideo(sign.id(), bytes, type);
            double start = queryDouble(ex, "start");
            double end = queryDouble(ex, "end");
            if (Double.isFinite(start) && Double.isFinite(end) && start >= 0 && end > start && end <= 86_400) {
                references.putClip(sign.id(), start, end);
            } else {
                references.deleteClip(sign.id());
            }
            sendJson(ex, 200, Map.of("ok", true, "signId", sign.id()));

        } else if (parts.length == 2 && parts[0].equals("references") && method.equals("POST")) {
            // Instructor recording. Stores summary features and one key frame.
            requireInstructor(ex);
            SignCatalogue.Sign s = requireSign(parts[1]);
            Map<String, Object> body = readJsonObject(ex);
            Features f = SignAnalyzer.extract(parseFrames(body.get("frames")));
            references.put(s.id(), f);
            sendJson(ex, 200, Map.of("ok", true, "signId", s.id()));

        } else if (parts.length == 1 && parts[0].equals("attempts") && method.equals("POST")) {
            Map<String, Object> body = readJsonObject(ex);
            String learnerId = requireLearner(body.get("learnerId"));
            SignCatalogue.Sign s = requireSign(String.valueOf(body.get("signId")));
            Features ref = references.get(s.id()).orElseThrow(
                    () -> new ApiException(409, "This sign has no instructor recording yet, so it cannot be scored."));
            Features att = SignAnalyzer.extract(parseFrames(body.get("frames")));
            SignAnalyzer.AttemptResult result = SignAnalyzer.compare(ref, att);
            progress.addAttempt(learnerId, s.id(), result);
            sendJson(ex, 200, result.toMap());

        } else if (parts.length == 2 && parts[0].equals("progress") && method.equals("GET")) {
            String learnerId = requireLearner(parts[1]);
            sendJson(ex, 200, progress.summary(learnerId, catalogue));

        } else if (parts.length == 2 && parts[0].equals("learners") && method.equals("DELETE")) {
            String learnerId = requireLearner(parts[1]);
            sendJson(ex, 200, Map.of("deleted", progress.delete(learnerId)));

        } else {
            throw new ApiException(404, "Not found");
        }
    }

    private Map<String, Object> signView(SignCatalogue.Sign s, boolean withFrame) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("name", s.name());
        m.put("category", s.category());
        m.put("difficulty", s.difficulty());
        m.put("description", s.description());
        m.put("sourceVideoUrl", s.sourceVideoUrl());
        m.put("steps", s.steps());
        Optional<Features> ref = references.get(s.id());
        m.put("hasReference", ref.isPresent());
        boolean hasVideo = Files.isRegularFile(references.videoPath(s.id()));
        m.put("hasReferenceVideo", hasVideo);
        if (hasVideo) {
            double[] clip = references.getClip(s.id());
            if (clip != null) {
                m.put("clipStart", clip[0]);
                m.put("clipEnd", clip[1]);
            }
        }
        if (withFrame && ref.isPresent()) {
            m.put("referenceFrame", ref.get().toMap().get("keyFrame"));
        }
        return m;
    }

    private SignCatalogue.Sign requireSign(String id) {
        if (!SignCatalogue.ID.matcher(id).matches()) {
            throw new ApiException(400, "Invalid sign id.");
        }
        return catalogue.find(id).orElseThrow(() -> new ApiException(404, "Unknown sign: " + id));
    }

    private static String requireLearner(Object id) {
        String s = String.valueOf(id);
        if (!LEARNER_ID.matcher(s).matches()) {
            throw new ApiException(400, "Invalid learner id.");
        }
        return s;
    }

    /** Validates landmark frames: 5 to 300 frames, each with 21 points of finite (x, y, z). */
    static double[][][] parseFrames(Object raw) {
        if (!(raw instanceof List<?>)) {
            throw new IllegalArgumentException("frames must be an array");
        }
        List<Object> list = Json.asList(raw);
        if (list.size() < SignAnalyzer.MIN_FRAMES || list.size() > SignAnalyzer.MAX_FRAMES) {
            throw new IllegalArgumentException("frames must contain between " + SignAnalyzer.MIN_FRAMES
                    + " and " + SignAnalyzer.MAX_FRAMES + " frames");
        }
        double[][][] out = new double[list.size()][SignAnalyzer.HAND_POINTS][3];
        for (int t = 0; t < list.size(); t++) {
            List<Object> pts = Json.asList(list.get(t));
            if (pts.size() != SignAnalyzer.HAND_POINTS) {
                throw new IllegalArgumentException("each frame must have " + SignAnalyzer.HAND_POINTS + " points");
            }
            for (int k = 0; k < SignAnalyzer.HAND_POINTS; k++) {
                List<Object> xyz = Json.asList(pts.get(k));
                if (xyz.size() < 3) {
                    throw new IllegalArgumentException("each point must have x, y and z");
                }
                for (int c = 0; c < 3; c++) {
                    if (!(xyz.get(c) instanceof Double d) || !Double.isFinite(d)) {
                        throw new IllegalArgumentException("landmark values must be finite numbers");
                    }
                    out[t][k][c] = d;
                }
            }
        }
        return out;
    }

    /** Reads a numeric query parameter, or NaN if it is missing or not a number. */
    private static double queryDouble(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) return Double.NaN;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return Double.parseDouble(pair.substring(eq + 1));
                } catch (NumberFormatException e) {
                    return Double.NaN;
                }
            }
        }
        return Double.NaN;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    private static void requireInstructor(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (INSTRUCTOR_USER + ":" + INSTRUCTOR_PASSWORD).getBytes(StandardCharsets.UTF_8));
        boolean ok = header != null
                && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            throw new ApiException(401, "Instructor sign-in required.");
        }
    }

    private static Map<String, Object> readJsonObject(HttpExchange ex) throws IOException {
        return Json.asMap(Json.parse(readBody(ex)));
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new ApiException(413, "Request is too large.");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            throw new ApiException(405, "Method not allowed");
        }
        String rel = path.equals("/") ? "index.html" : path.substring(1);
        Path file = webRoot.resolve(rel).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
            throw new ApiException(404, "Not found");
        }
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    static final class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
