package ch.blugdeg.lifematic.client.ghost;

import ch.blugdeg.lifematic.client.LifematicClient;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.position.LayerRange;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Shaderfeste Bauplan-Vorschau.
 *
 * Statt Litematica die Geisterbloecke in einem eigenen Renderweg zeichnen zu lassen, werden sie direkt in
 * Sodiums Chunk-Meshes eingebaut. Fuer Sodium und Iris sind sie damit ganz normales Terrain: Shader, Schatten,
 * Culling und Performance funktionieren wie bei echten Bloecken. Damit man sie von echten Bloecken
 * unterscheiden kann, werden sie blau eingefaerbt.
 *
 * Diese Klasse laeuft nur auf dem Render-Thread. Die Sodium-Worker bekommen fertige, unveraenderliche
 * Kopien ({@link GhostSlice}).
 */
public final class GhostManager {
    private GhostManager() {}

    /** Faerbung der Geisterbloecke (ARGB, wird mit der Blockfarbe multipliziert). */
    public static final int TINT = 0xFF86B0FF;

    /** Ersatzblock fuer Bloecke ohne normales Modell (Truhen, Schilder, Betten …). */
    private static final BlockState PLACEHOLDER = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();

    private static final BlockState[] NONE = new BlockState[0];

    private static final Path SETTINGS = FabricLoader.getInstance().getConfigDir().resolve("lifematic-render.properties");

    public static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");

    private static boolean preferred = true;
    private static boolean active;
    private static boolean litematicaAdjusted;

    private static final Long2ObjectOpenHashMap<BlockState[]> CACHE = new Long2ObjectOpenHashMap<>();
    private static final LongOpenHashSet DIRTY_COLUMNS = new LongOpenHashSet();
    private static List<int[]> boxes = new ArrayList<>();
    private static String signature = "";

    // ------------------------------------------------------------------ Einstellungen

    public static void load() {
        if (!Files.exists(SETTINGS)) return;
        try (Reader r = Files.newBufferedReader(SETTINGS)) {
            Properties p = new Properties();
            p.load(r);
            preferred = Boolean.parseBoolean(p.getProperty("shaderfesteVorschau", "true"));
        } catch (IOException e) {
            LifematicClient.LOG.warn("lifematic-render.properties nicht lesbar", e);
        }
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("shaderfesteVorschau", Boolean.toString(preferred));
        try (Writer w = Files.newBufferedWriter(SETTINGS)) {
            p.store(w, "Lifematic Darstellung");
        } catch (IOException e) {
            LifematicClient.LOG.warn("lifematic-render.properties nicht speicherbar", e);
        }
    }

    /** Kann die shaderfeste Vorschau ueberhaupt laufen (Sodium vorhanden)? */
    public static boolean available() {
        return SODIUM;
    }

    public static boolean preferred() {
        return preferred;
    }

    public static void setPreferred(boolean on) {
        preferred = on;
        save();
    }

    public static boolean isActive() {
        return active;
    }

    // ------------------------------------------------------------------ Takt (Render-Thread)

    /** Einmal pro Client-Tick: Aenderungen an Platzierungen erkennen und betroffene Chunks neu bauen lassen. */
    public static void tick(Minecraft mc) {
        if (!SODIUM) return;
        if (mc.level == null) {
            CACHE.clear();
            DIRTY_COLUMNS.clear();
            signature = "";
            return;
        }

        boolean wantActive = preferred
                && Configs.Visuals.ENABLE_RENDERING.getBooleanValue()
                && Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue();

        if (wantActive != litematicaAdjusted) {
            adjustLitematica(wantActive);
        }

        List<int[]> newBoxes = collectBoxes();
        String sig = wantActive + "|" + boxesSignature(newBoxes);

        if (!sig.equals(signature)) {
            List<int[]> affected = new ArrayList<>(boxes);
            affected.addAll(newBoxes);
            active = wantActive;
            boxes = newBoxes;
            signature = sig;
            CACHE.clear();
            for (int[] b : affected) {
                SodiumBridge.rebuildBlockArea(b[0] - 1, b[1] - 1, b[2] - 1, b[3] + 1, b[4] + 1, b[5] + 1);
            }
        }

        if (!DIRTY_COLUMNS.isEmpty()) {
            ClientLevel level = mc.level;
            int minSY = level.getMinSectionY();
            int maxSY = level.getMaxSectionY();
            LongIterator it = DIRTY_COLUMNS.iterator();
            while (it.hasNext()) {
                long c = it.nextLong();
                int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
                for (int sy = minSY; sy <= maxSY; sy++) CACHE.remove(SectionPos.asLong(cx, sy, cz));
                if (active) SodiumBridge.rebuildChunks(cx - 1, minSY, cz - 1, cx + 1, maxSY, cz + 1);
            }
            DIRTY_COLUMNS.clear();
        }
    }

    /** Litematica zeichnet die Bloecke dann nicht mehr selbst, sonst waeren sie doppelt da. */
    private static void adjustLitematica(boolean ghostsOn) {
        litematicaAdjusted = ghostsOn;
        try {
            Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.setBooleanValue(!ghostsOn);
            // Fehlende Bloecke zeigen jetzt die Geisterbloecke; das teure Overlay nur noch fuer falsche Bloecke, ohne Flaechen
            Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_MISSING.setBooleanValue(!ghostsOn);
            Configs.Visuals.SCHEMATIC_OVERLAY_ENABLE_SIDES.setBooleanValue(!ghostsOn);
        } catch (Throwable t) {
            LifematicClient.LOG.warn("Litematica-Darstellung konnte nicht angepasst werden", t);
        }
    }

    private static List<int[]> collectBoxes() {
        List<int[]> out = new ArrayList<>();
        for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (!p.isEnabled() || !p.isRenderingEnabled()) continue;
            for (Box box : p.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.RENDERING_ENABLED).values()) {
                if (box == null || box.getPos1() == null || box.getPos2() == null) continue;
                BlockPos a = box.getPos1(), b = box.getPos2();
                out.add(new int[]{Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                        Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())});
            }
        }
        return out;
    }

    private static String boxesSignature(List<int[]> list) {
        StringBuilder sb = new StringBuilder();
        for (int[] b : list) {
            for (int v : b) sb.append(v).append(',');
            sb.append(';');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Hinweise von aussen

    /** Litematica hat Bloecke in einer Schematic-Chunkspalte geaendert, geladen oder entladen. */
    public static void markColumnDirty(int chunkX, int chunkZ) {
        if (SODIUM) DIRTY_COLUMNS.add(ChunkPos.pack(chunkX, chunkZ));
    }

    /** Sodium baut einen Abschnitt neu, weil sich die echte Welt dort geaendert hat. */
    public static void invalidateSection(long sectionKey) {
        CACHE.remove(sectionKey);
    }

    // ------------------------------------------------------------------ Daten fuer Sodium

    public static boolean hasGhosts(int sx, int sy, int sz) {
        return active && ghosts(sx, sy, sz) != null;
    }

    /** Geisterbloecke eines 16³-Abschnitts im Sodium-Index-Format, oder null. Nur Render-Thread. */
    public static BlockState[] ghosts(int sx, int sy, int sz) {
        if (!active) return null;
        long key = SectionPos.asLong(sx, sy, sz);
        BlockState[] g = CACHE.get(key);
        if (g == null) {
            g = compute(sx, sy, sz);
            CACHE.put(key, g);
        }
        return g == NONE ? null : g;
    }

    private static BlockState[] compute(int sx, int sy, int sz) {
        int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
        int x1 = x0 + 15, y1 = y0 + 15, z1 = z0 + 15;

        boolean hit = false;
        for (int[] b : boxes) {
            if (b[0] <= x1 && b[3] >= x0 && b[1] <= y1 && b[4] >= y0 && b[2] <= z1 && b[5] >= z0) {
                hit = true;
                break;
            }
        }
        if (!hit) return NONE;

        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        ClientLevel level = Minecraft.getInstance().level;
        if (schematic == null || level == null) return NONE;
        LayerRange layers = DataManager.getRenderLayerRange();

        BlockState[] out = null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int wx = x0 + x, wy = y0 + y, wz = z0 + z;
                    if (!layers.isPositionWithinRange(wx, wy, wz)) continue;
                    pos.set(wx, wy, wz);
                    BlockState want = schematic.getBlockState(pos);
                    if (want.isAir()) continue;
                    BlockState real = level.getBlockState(pos);
                    if (!real.isAir() && !real.canBeReplaced()) continue;
                    BlockState ghost = toGhost(want);
                    if (ghost == null) continue;
                    if (out == null) out = new BlockState[16 * 16 * 16];
                    out[index(x, y, z)] = ghost;
                }
            }
        }
        return out == null ? NONE : out;
    }

    /** Darstellung eines Bauplan-Blocks als Geisterblock (oder null, wenn er nicht gezeigt wird). */
    private static BlockState toGhost(BlockState s) {
        if (s.getBlock() instanceof LiquidBlock) return null;
        if (s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED)) {
            s = s.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        RenderShape shape = s.getRenderShape();
        if (shape == RenderShape.INVISIBLE) return null;
        if (shape != RenderShape.MODEL) return PLACEHOLDER;
        return s;
    }

    /** Gleiche Anordnung wie Sodiums LevelSlice.getLocalBlockIndex. */
    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }
}
