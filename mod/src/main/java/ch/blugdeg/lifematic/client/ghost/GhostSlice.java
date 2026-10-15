package ch.blugdeg.lifematic.client.ghost;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Unveraenderliche Kopie fuer einen Sodium-Bauauftrag: Geisterbloecke des Abschnitts und seiner 26 Nachbarn
 * (fuer das Culling zwischen benachbarten Geisterbloecken) sowie die Markierungen falscher Bloecke im Abschnitt.
 */
public final class GhostSlice {
    public static final int LENGTH = 3;
    public static final int CENTER = index(1, 1, 1);

    public final int originX, originY, originZ;
    public final BlockState[][] ghosts = new BlockState[LENGTH * LENGTH * LENGTH][];
    public byte[] marks;

    private GhostSlice(SectionPos origin) {
        this.originX = origin.x();
        this.originY = origin.y();
        this.originZ = origin.z();
    }

    public static int index(int x, int y, int z) {
        return (y * LENGTH * LENGTH) + (z * LENGTH) + x;
    }

    /** Geisterblock an einer Weltposition innerhalb der 3×3×3 Abschnitte, oder null. */
    public BlockState ghostAt(int x, int y, int z) {
        int dx = (x >> 4) - this.originX + 1, dy = (y >> 4) - this.originY + 1, dz = (z >> 4) - this.originZ + 1;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= LENGTH || dy >= LENGTH || dz >= LENGTH) return null;
        BlockState[] g = this.ghosts[index(dx, dy, dz)];
        return g == null ? null : g[GhostManager.index(x & 15, y & 15, z & 15)];
    }

    /** Nur Render-Thread. Liefert null, wenn es hier nichts zu zeigen gibt. */
    public static GhostSlice capture(SectionPos origin) {
        if (!GhostManager.isActive()) return null;
        GhostSlice slice = null;
        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dy = 0; dy < LENGTH; dy++) {
                for (int dz = 0; dz < LENGTH; dz++) {
                    GhostManager.SectionData d = GhostManager.data(origin.x() + dx - 1, origin.y() + dy - 1, origin.z() + dz - 1);
                    boolean center = dx == 1 && dy == 1 && dz == 1;
                    if (d.ghosts() == null && !(center && d.marks() != null)) continue;
                    if (slice == null) slice = new GhostSlice(origin);
                    slice.ghosts[index(dx, dy, dz)] = d.ghosts();
                    if (center) slice.marks = d.marks();
                }
            }
        }
        return slice;
    }
}
