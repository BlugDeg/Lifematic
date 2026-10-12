package ch.blugdeg.lifematic.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Merkt sich, welche eigene Litematica-Platzierung zu welchem Server-Bauplan gehoert,
 * damit ein zweites Teilen den Bauplan aktualisiert statt einen neuen anzulegen.
 */
public final class LinkConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("lifematic.json");
    private Map<String, String> links = new HashMap<>();

    public void load() {
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, String> m = GSON.fromJson(r, new TypeToken<Map<String, String>>() {}.getType());
            if (m != null) links = new HashMap<>(m);
        } catch (Exception e) {
            LifematicClient.LOG.warn("lifematic.json konnte nicht gelesen werden", e);
        }
    }

    public String idFor(String placementHash) {
        return placementHash == null ? null : links.get(placementHash);
    }

    public void link(String placementHash, String id) {
        if (placementHash == null || placementHash.isEmpty()) return;
        links.put(placementHash, id);
        save();
    }

    public void unlinkId(String id) {
        if (links.values().removeIf(v -> v.equals(id))) save();
    }

    private void save() {
        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(links, w);
        } catch (IOException e) {
            LifematicClient.LOG.warn("lifematic.json konnte nicht gespeichert werden", e);
        }
    }
}
