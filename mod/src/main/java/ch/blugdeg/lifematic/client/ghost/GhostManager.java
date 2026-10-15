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
    /** Faerbung falscher Bloecke: rot = falscher Block, orange = falsch ausgerichtet, violett = gehoert nicht hin. */
    public static final int TINT_WRONG = 0xFFFF5A4A, TINT_STATE = 0xFFFFB040, TINT_EXTRA = 0xFFE060FF;

    /** Ersatzblock fuer Bloecke ohne normales Modell (Truhen, Schilder, Betten …). */
    private static final BlockState PLACEHOLDER = Blocks.GLASS.defaultBlockState();

    private static final Path SETTINGS = FabricLoader.getInstance().getConfigDir().resolve("lifematic-render.properties");

    public static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");

    private static boolean preferred = true;
    private static boolean active;
    private static boolean litematicaAdjusted;

    private static final Long2ObjectOpenHashMap<SectionData> CACHE = new Long2ObjectOpenHashMap<>();
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

        showHint(mc);
    }

    private static BlockPos lastHintPos;
    private static int hintTicks;

    /** Schaut der Spieler auf einen falschen Block, steht in der Aktionsleiste, was hingehoert. */
    private static void showHint(Minecraft mc) {
        if (!active || mc.player == null || !(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)
                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            lastHintPos = null;
            return;
        }
        BlockPos p = hit.getBlockPos();
        String msg = describeMismatch(p);
        if (msg == null) {
            lastHintPos = null;
            return;
        }
        if (!p.equals(lastHintPos) || ++hintTicks >= 30) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(msg)
                    .withStyle(net.minecraft.ChatFormatting.RED), true);
            lastHintPos = p.immutable();
            hintTicks = 0;
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

    /** Geisterbloecke und Markierungen falscher Bloecke eines 16³-Abschnitts. */
    public record SectionData(BlockState[] ghosts, byte[] marks) {}

    public static final byte MARK_WRONG = 1, MARK_STATE = 2, MARK_EXTRA = 3;

    private static final SectionData EMPTY = new SectionData(null, null);

    /** Eigenschaften, die sich im Spiel von selbst aendern und darum nicht als «falsch» gelten. */
    private static final java.util.Set<String> VOLATILE = java.util.Set.of(
            "powered", "power", "open", "waterlogged", "north", "south", "east", "west", "up", "down",
            "occupied", "enabled", "triggered", "lit", "age", "distance", "persistent", "moisture",
            "extended", "short", "locked", "delay", "mode", "note", "instrument", "has_book", "bottom",
            "in_wall", "attached", "disarmed", "level", "honey_level", "candles", "eggs", "hatch");

    public static boolean hasContent(int sx, int sy, int sz) {
        if (!active) return false;
        SectionData d = data(sx, sy, sz);
        return d.ghosts() != null;
    }

    /** Daten eines Abschnitts (Cache). Nur Render-Thread. */
    public static SectionData data(int sx, int sy, int sz) {
        if (!active) return EMPTY;
        long key = SectionPos.asLong(sx, sy, sz);
        SectionData d = CACHE.get(key);
        if (d == null) {
            d = compute(sx, sy, sz);
            CACHE.put(key, d);
        }
        return d;
    }

    private static boolean inBoxes(int x, int y, int z) {
        for (int[] b : boxes) {
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) return true;
        }
        return false;
    }

    private static SectionData compute(int sx, int sy, int sz) {
        int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
        int x1 = x0 + 15, y1 = y0 + 15, z1 = z0 + 15;

        boolean hit = false;
        for (int[] b : boxes) {
            if (b[0] <= x1 && b[3] >= x0 && b[1] <= y1 && b[4] >= y0 && b[2] <= z1 && b[5] >= z0) {
                hit = true;
                break;
            }
        }
        if (!hit) return EMPTY;

        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        ClientLevel level = Minecraft.getInstance().level;
        if (schematic == null || level == null) return EMPTY;
        if (!schematic.getChunkSource().hasChunk(sx, sz)) return EMPTY;
        LayerRange layers = DataManager.getRenderLayerRange();

        BlockState[] ghosts = null;
        byte[] marks = null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int wx = x0 + x, wy = y0 + y, wz = z0 + z;
                    if (!layers.isPositionWithinRange(wx, wy, wz)) continue;
                    pos.set(wx, wy, wz);
                    BlockState want = schematic.getBlockState(pos);
                    BlockState real = level.getBlockState(pos);
                    boolean realEmpty = real.isAir() || real.canBeReplaced();
                    int i = index(x, y, z);

                    if (realEmpty) {
                        if (want.isAir()) continue;
                        BlockState ghost = toGhost(want);
                        if (ghost == null) continue;
                        if (ghosts == null) ghosts = new BlockState[16 * 16 * 16];
                        ghosts[i] = ghost;
                        continue;
                    }

                    byte mark = 0;
                    if (want.isAir()) {
                        if (inBoxes(wx, wy, wz)) mark = MARK_EXTRA;
                    } else if (real.getBlock() != want.getBlock()) {
                        if (!(want.getBlock() instanceof LiquidBlock)) mark = MARK_WRONG;
                    } else if (!sameRelevantState(real, want)) {
                        mark = MARK_STATE;
                    }
                    if (mark != 0) {
                        if (marks == null) marks = new byte[16 * 16 * 16];
                        marks[i] = mark;
                    }
                }
            }
        }
        return (ghosts == null && marks == null) ? EMPTY : new SectionData(ghosts, marks);
    }

    private static boolean sameRelevantState(BlockState a, BlockState b) {
        for (net.minecraft.world.level.block.state.properties.Property<?> p : a.getProperties()) {
            if (VOLATILE.contains(p.getName())) continue;
            if (!b.hasProperty(p)) continue;
            if (!a.getValue(p).equals(b.getValue(p))) return false;
        }
        return true;
    }

    /** Was gehoert laut Bauplan an diese Stelle, und stimmt es? Fuer den Hinweis beim Anschauen. */
    public static String describeMismatch(BlockPos pos) {
        if (!active) return null;
        SectionData d = data(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        if (d.marks() == null) return null;
        byte m = d.marks()[index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)];
        if (m == 0) return null;
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return null;
        BlockState want = schematic.getBlockState(pos);
        return switch (m) {
            case MARK_EXTRA -> "Hier gehoert laut Bauplan nichts hin";
            case MARK_WRONG -> "Falscher Block – hier gehoert hin: " + want.getBlock().getName().getString();
            default -> "Richtiger Block, falsch ausgerichtet: " + stateText(want);
        };
    }

    private static String stateText(BlockState s) {
        StringBuilder sb = new StringBuilder(s.getBlock().getName().getString());
        for (net.minecraft.world.level.block.state.properties.Property<?> p : s.getProperties()) {
            if (VOLATILE.contains(p.getName())) continue;
            sb.append(' ').append(p.getName()).append('=').append(s.getValue(p));
        }
        return sb.toString();
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
