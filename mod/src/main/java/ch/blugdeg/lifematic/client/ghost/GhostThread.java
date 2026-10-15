package ch.blugdeg.lifematic.client.ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Zustand pro Sodium-Worker waehrend ein Abschnitt gebaut wird.
 * Geisterbloecke stehen NICHT in Sodiums Weltkopie: Echte Bloecke werden so nie von Geistern verdeckt
 * (sonst fehlen ihre Flaechen und man sieht wie mit X-Ray durch Waende).
 */
public final class GhostThread {
    private GhostThread() {}

    private static final class State {
        GhostSlice slice;
        boolean currentIsGhost;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    public static void begin(GhostSlice slice) {
        State s = STATE.get();
        s.slice = slice;
        s.currentIsGhost = false;
    }

    /** Hauptschleife des Meshers: Geist statt Luft liefern, wenn der Bauplan hier einen Block will. */
    public static BlockState substitute(int x, int y, int z, BlockState real) {
        State s = STATE.get();
        s.currentIsGhost = false;
        if (s.slice == null) return real;
        if (!real.isAir() && !real.canBeReplaced()) return real;
        BlockState g = s.slice.ghostAt(x, y, z);
        if (g == null) return real;
        s.currentIsGhost = true;
        return g;
    }

    public static boolean currentIsGhost() {
        return STATE.get().currentIsGhost;
    }

    /**
     * Face-Culling: Fuer einen Geisterblock zaehlen benachbarte Geister als Nachbar (spart unsichtbare Flaechen).
     * Fuer echte Bloecke zaehlt nur die echte Welt.
     */
    public static BlockState neighborForCulling(BlockPos neighbor, BlockState real) {
        State s = STATE.get();
        if (!s.currentIsGhost || s.slice == null) return real;
        if (!real.isAir() && !real.canBeReplaced()) return real;
        BlockState g = s.slice.ghostAt(neighbor.getX(), neighbor.getY(), neighbor.getZ());
        return g == null ? real : g;
    }

    /** Einfaerbung fuer den Block, der gerade gezeichnet wird (0 = keine). */
    public static int tintFor(BlockPos pos) {
        State s = STATE.get();
        if (s.currentIsGhost) return GhostManager.TINT;
        if (s.slice == null || s.slice.marks == null) return 0;
        return switch (s.slice.marks[GhostManager.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)]) {
            case GhostManager.MARK_WRONG -> GhostManager.TINT_WRONG;
            case GhostManager.MARK_STATE -> GhostManager.TINT_STATE;
            case GhostManager.MARK_EXTRA -> GhostManager.TINT_EXTRA;
            default -> 0;
        };
    }
}
