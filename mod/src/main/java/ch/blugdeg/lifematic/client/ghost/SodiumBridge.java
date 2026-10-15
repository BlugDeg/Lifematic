package ch.blugdeg.lifematic.client.ghost;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;

/** Einzige Stelle ausserhalb der Mixins, die Sodium-Klassen anfasst. Wird nur geladen, wenn Sodium da ist. */
final class SodiumBridge {
    private SodiumBridge() {}

    static void rebuildBlockArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        SodiumWorldRenderer r = SodiumWorldRenderer.instanceNullable();
        if (r != null) r.scheduleRebuildForBlockArea(minX, minY, minZ, maxX, maxY, maxZ, false);
    }

    static void rebuildChunks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        SodiumWorldRenderer r = SodiumWorldRenderer.instanceNullable();
        if (r != null) r.scheduleRebuildForChunks(minX, minY, minZ, maxX, maxY, maxZ, false);
    }
}
