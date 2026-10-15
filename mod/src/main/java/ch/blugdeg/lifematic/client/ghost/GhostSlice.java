package ch.blugdeg.lifematic.client.ghost;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Unveraenderliche Kopie der Geisterbloecke fuer einen Sodium-Bauauftrag: der Abschnitt selbst
 * und seine 26 Nachbarn (fuer Flaechen-Culling und Umgebungsverdeckung an den Raendern).
 */
public final class GhostSlice {
    /** Sodium legt 3×3×3 Abschnitte ab, der eigene liegt in der Mitte. */
    public static final int LENGTH = 3;
    public static final int CENTER = index(1, 1, 1);

    public final BlockState[][] sections = new BlockState[LENGTH * LENGTH * LENGTH][];

    /** Gleiche Anordnung wie Sodiums LevelSlice.getLocalSectionIndex. */
    public static int index(int x, int y, int z) {
        return (y * LENGTH * LENGTH) + (z * LENGTH) + x;
    }

    /** Nur Render-Thread. Liefert null, wenn in der Umgebung keine Geisterbloecke liegen. */
    public static GhostSlice capture(SectionPos origin) {
        if (!GhostManager.isActive()) return null;
        GhostSlice slice = null;
        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dy = 0; dy < LENGTH; dy++) {
                for (int dz = 0; dz < LENGTH; dz++) {
                    BlockState[] g = GhostManager.ghosts(origin.x() + dx - 1, origin.y() + dy - 1, origin.z() + dz - 1);
                    if (g == null) continue;
                    if (slice == null) slice = new GhostSlice();
                    slice.sections[index(dx, dy, dz)] = g;
                }
            }
        }
        return slice;
    }
}
