package ch.blugdeg.lifematic.client.ghost;

import net.minecraft.core.BlockPos;

import java.util.Arrays;

/**
 * Pro Sodium-Worker: welche Bloecke des gerade gebauten Abschnitts sind Geisterbloecke?
 * Wird beim Kopieren der Weltdaten gefuellt und beim Zeichnen der Bloecke abgefragt.
 */
public final class GhostThread {
    private GhostThread() {}

    private static final class State {
        final boolean[] mask = new boolean[16 * 16 * 16];
        boolean any;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    public static void begin() {
        State s = STATE.get();
        if (s.any) {
            Arrays.fill(s.mask, false);
            s.any = false;
        }
    }

    public static void mark(int index) {
        State s = STATE.get();
        s.mask[index] = true;
        s.any = true;
    }

    public static boolean isGhost(BlockPos pos) {
        State s = STATE.get();
        return s.any && s.mask[GhostManager.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)];
    }
}
