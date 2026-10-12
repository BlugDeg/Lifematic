package ch.blugdeg.lifematic;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Ein geteilter Bauplan mit Platzierung in einer Welt. */
public final class Blueprint {
    public String id;
    public String name;
    public UUID owner;
    public String ownerName;
    public String world;
    public String fileName;          // Dateiname im Ordner blueprints/
    public int version = 1;
    public int ox, oy, oz;           // Ursprung der Litematica-Platzierung
    public String rotation = "NONE"; // NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90
    public String mirror = "NONE";   // NONE, LEFT_RIGHT, FRONT_BACK
    public int minX, minY, minZ, maxX, maxY, maxZ; // umschliessender Bereich (inklusiv)
    public boolean helpersAllowed = true;
    public Set<UUID> helpers = new HashSet<>();
    public long created;

    // Laufzeitdaten (nicht gespeichert)
    public transient ExpectedBlocks expected;
    public transient int correct = -1;

    public boolean contains(String worldName, int x, int y, int z) {
        return worldName.equals(world) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Bereich leicht vergroessert, damit die Frage schon kurz vor dem Bauplatz kommt. */
    public boolean near(String worldName, int x, int y, int z, int margin) {
        return worldName.equals(world) && x >= minX - margin && x <= maxX + margin
                && y >= minY - margin && y <= maxY + margin && z >= minZ - margin && z <= maxZ + margin;
    }

    public boolean mayBuild(UUID player) {
        return player.equals(owner) || helpersAllowed || helpers.contains(player);
    }

    public int percent() {
        if (expected == null || expected.size() == 0 || correct < 0) return -1;
        return (int) Math.floor(100.0 * correct / expected.size());
    }
}
