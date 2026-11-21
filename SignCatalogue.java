package dalil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** The list of signs, loaded from data/signs.json. */
public final class SignCatalogue {

    static final Pattern ID = Pattern.compile("[a-z0-9-]{1,40}");

    public record Sign(String id, String name, String category, String difficulty,
                       String description, List<String> steps, String sourceVideoUrl) {
    }

    private final Map<String, Sign> byId = new LinkedHashMap<>();

    private SignCatalogue() {
    }

    public static SignCatalogue load(Path file) throws IOException {
        Map<String, Object> root = Json.asMap(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
        SignCatalogue c = new SignCatalogue();
        for (Object o : Json.asList(root.get("signs"))) {
            Map<String, Object> m = Json.asMap(o);
            String id = Json.str(m, "id");
            if (!ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid sign id in catalogue: " + id);
            }
            List<String> steps = new ArrayList<>();
            for (Object s : Json.asList(m.get("steps"))) {
                steps.add(String.valueOf(s));
            }
            c.byId.put(id, new Sign(id, Json.str(m, "name"), Json.str(m, "category"),
                    Json.str(m, "difficulty"), Json.str(m, "description"), List.copyOf(steps),
                    m.get("sourceVideoUrl") == null ? "" : String.valueOf(m.get("sourceVideoUrl"))));
        }
        return c;
    }

    public List<Sign> all() {
        return List.copyOf(byId.values());
    }

    public Optional<Sign> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }
}
