package ch.blugdeg.lifematic;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;

/** Speichert Bauplaene und Antworten der Spieler in data.yml. */
public final class BlueprintStore {
    private final File dataFile;
    public final File blueprintDir;
    public final Map<String, Blueprint> blueprints = new LinkedHashMap<>();
    /** Spieler -> beantwortete Fragen ("id:version"), damit nicht dauernd gefragt wird. */
    public final Map<UUID, Set<String>> answered = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public BlueprintStore(File pluginDir) {
        this.dataFile = new File(pluginDir, "data.yml");
        this.blueprintDir = new File(pluginDir, "blueprints");
        //noinspection ResultOfMethodCallIgnored
        blueprintDir.mkdirs();
    }

    public String newId() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        while (true) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
            if (!blueprints.containsKey(sb.toString())) return sb.toString();
        }
    }

    public File fileOf(Blueprint bp) {
        return new File(blueprintDir, bp.fileName);
    }

    public void load() {
        blueprints.clear();
        answered.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection bs = y.getConfigurationSection("blueprints");
        if (bs != null) {
            for (String id : bs.getKeys(false)) {
                ConfigurationSection s = bs.getConfigurationSection(id);
                if (s == null) continue;
                Blueprint bp = new Blueprint();
                bp.id = id;
                bp.name = s.getString("name", id);
                bp.owner = UUID.fromString(s.getString("owner", new UUID(0, 0).toString()));
                bp.ownerName = s.getString("ownerName", "?");
                bp.world = s.getString("world", "world");
                bp.fileName = s.getString("file", id + ".litematic");
                bp.version = s.getInt("version", 1);
                bp.ox = s.getInt("origin.x"); bp.oy = s.getInt("origin.y"); bp.oz = s.getInt("origin.z");
                bp.rotation = s.getString("rotation", "NONE");
                bp.mirror = s.getString("mirror", "NONE");
                bp.minX = s.getInt("box.minX"); bp.minY = s.getInt("box.minY"); bp.minZ = s.getInt("box.minZ");
                bp.maxX = s.getInt("box.maxX"); bp.maxY = s.getInt("box.maxY"); bp.maxZ = s.getInt("box.maxZ");
                bp.helpersAllowed = s.getBoolean("helpersAllowed", true);
                for (String h : s.getStringList("helpers")) {
                    try { bp.helpers.add(UUID.fromString(h)); } catch (IllegalArgumentException ignored) {}
                }
                bp.created = s.getLong("created");
                blueprints.put(id, bp);
            }
        }
        ConfigurationSection ps = y.getConfigurationSection("answered");
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                try { answered.put(UUID.fromString(k), new HashSet<>(ps.getStringList(k))); } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public void save() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        for (Blueprint bp : blueprints.values()) {
            String p = "blueprints." + bp.id + ".";
            y.set(p + "name", bp.name);
            y.set(p + "owner", bp.owner.toString());
            y.set(p + "ownerName", bp.ownerName);
            y.set(p + "world", bp.world);
            y.set(p + "file", bp.fileName);
            y.set(p + "version", bp.version);
            y.set(p + "origin.x", bp.ox); y.set(p + "origin.y", bp.oy); y.set(p + "origin.z", bp.oz);
            y.set(p + "rotation", bp.rotation);
            y.set(p + "mirror", bp.mirror);
            y.set(p + "box.minX", bp.minX); y.set(p + "box.minY", bp.minY); y.set(p + "box.minZ", bp.minZ);
            y.set(p + "box.maxX", bp.maxX); y.set(p + "box.maxY", bp.maxY); y.set(p + "box.maxZ", bp.maxZ);
            y.set(p + "helpersAllowed", bp.helpersAllowed);
            List<String> hs = new ArrayList<>();
            for (UUID u : bp.helpers) hs.add(u.toString());
            y.set(p + "helpers", hs);
            y.set(p + "created", bp.created);
        }
        for (Map.Entry<UUID, Set<String>> e : answered.entrySet()) {
            y.set("answered." + e.getKey(), new ArrayList<>(e.getValue()));
        }
        y.save(dataFile);
    }
}
