
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Stores one reference recording per sign as a JSON file in data/references. */
public final class ReferenceStore {

    private final Path dir;

    public ReferenceStore(Path dir) {
        this.dir = dir;
    }

    public Path videoPath(String signId) {
        for (String ext : new String[]{".mp4", ".webm", ".mov"}) {
            Path candidate = dir.resolve(signId + ext);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return dir.resolve(signId + ".mp4");
    }

    public synchronized void putVideo(String signId, byte[] bytes, String contentType) throws IOException {
        String extension = contentType.contains("webm") ? ".webm" : contentType.contains("quicktime") ? ".mov" : ".mp4";
        Path target = dir.resolve(signId + extension);
        Path tmp = dir.resolve(signId + extension + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        for (String ext : new String[]{".mp4", ".webm", ".mov"}) {
            Path old = dir.resolve(signId + ext);
            if (!old.equals(target)) Files.deleteIfExists(old);
        }
    }

    /** Remembers which part of the uploaded video is the sign (seconds). */
    public synchronized void putClip(String signId, double start, double end) throws IOException {
        Files.writeString(dir.resolve(signId + ".clip"), start + "," + end, StandardCharsets.UTF_8);
    }

    public synchronized void deleteClip(String signId) throws IOException {
        Files.deleteIfExists(dir.resolve(signId + ".clip"));
    }

    /** Returns {start, end} in seconds, or null if the whole video should play. */
    public synchronized double[] getClip(String signId) throws IOException {
        Path f = dir.resolve(signId + ".clip");
        if (!Files.isRegularFile(f)) return null;
        try {
            String[] p = Files.readString(f, StandardCharsets.UTF_8).trim().split(",");
            double start = Double.parseDouble(p[0]);
            double end = Double.parseDouble(p[1]);
            return (Double.isFinite(start) && Double.isFinite(end) && end > start && start >= 0)
                    ? new double[]{start, end} : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public synchronized Optional<Features> get(String signId) throws IOException {
        Path f = dir.resolve(signId + ".json");
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        return Optional.of(Features.fromMap(Json.asMap(Json.parse(Files.readString(f, StandardCharsets.UTF_8)))));
    }

    public synchronized void put(String signId, Features features) throws IOException {
        Path tmp = dir.resolve(signId + ".json.tmp");
        Files.writeString(tmp, Json.stringify(features.toMap()), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(signId + ".json"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
