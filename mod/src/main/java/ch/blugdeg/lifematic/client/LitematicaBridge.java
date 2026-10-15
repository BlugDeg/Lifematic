package ch.blugdeg.lifematic.client;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.position.LayerRange;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import fi.dy.masa.malilib.interfaces.IStringConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Alles, was direkt mit Litematica spricht, steckt hier drin. */
public final class LitematicaBridge {
    private LitematicaBridge() {}

    /** Ordner, in den empfangene Bauplaene geschrieben werden. */
    public static Path receiveDir() throws IOException {
        Path dir = DataManager.getSchematicsBaseDirectory().resolve("lifematic");
        Files.createDirectories(dir);
        return dir;
    }

    public static SchematicPlacement selected() {
        return DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
    }

    /** Daten der aktuell gewaehlten Platzierung fuer den Upload. */
    public record PlacementInfo(String name, Path file, BlockPos origin, Rotation rotation, Mirror mirror,
                                BlockPos min, BlockPos max, String hashId) {}

    public static PlacementInfo describe(SchematicPlacement p) {
        // Litematica berechnet den Gesamtbereich nur, wenn dessen Rahmen angezeigt wird.
        // Darum selbst aus den Teilbereichen zusammensetzen.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (Box box : p.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).values()) {
            if (box == null || box.getPos1() == null || box.getPos2() == null) continue;
            BlockPos a = box.getPos1(), b = box.getPos2();
            minX = Math.min(minX, Math.min(a.getX(), b.getX())); maxX = Math.max(maxX, Math.max(a.getX(), b.getX()));
            minY = Math.min(minY, Math.min(a.getY(), b.getY())); maxY = Math.max(maxY, Math.max(a.getY(), b.getY()));
            minZ = Math.min(minZ, Math.min(a.getZ(), b.getZ())); maxZ = Math.max(maxZ, Math.max(a.getZ(), b.getZ()));
            any = true;
        }
        if (!any) return null;
        String hash = p.getHashId() == null ? "" : p.getHashId().toString();
        return new PlacementInfo(p.getName(), p.getSchematicFile(), p.getOrigin(), p.getRotation(), p.getMirror(),
                new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), hash);
    }

    /** Ist diese Platzierung ein empfangener Bauplan? Dann liefert die Methode dessen ID. */
    public static String receivedId(SchematicPlacement p) {
        Path f = p.getSchematicFile();
        if (f == null) return null;
        try {
            if (!f.toAbsolutePath().normalize().startsWith(receiveDir().toAbsolutePath().normalize())) return null;
        } catch (IOException e) {
            return null;
        }
        String n = f.getFileName().toString();
        int us = n.indexOf("_v");
        return us > 0 ? n.substring(0, us) : null;
    }

    /** Welche empfangenen Bauplaene (id -> version) sind gerade in Litematica? */
    public static java.util.Map<String, Integer> receivedVersions() {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            String id = receivedId(p);
            if (id == null || p.getSchematicFile() == null) continue;
            String n = p.getSchematicFile().getFileName().toString();
            int a = n.indexOf("_v"), b = n.lastIndexOf(".litematic");
            try {
                m.put(id, Integer.parseInt(n.substring(a + 2, b)));
            } catch (RuntimeException ignored) {
                m.put(id, 0);
            }
        }
        return m;
    }

    /** Entfernt alle empfangenen Platzierungen eines Bauplans. */
    public static int removeReceived(String id) {
        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        List<SchematicPlacement> toRemove = new ArrayList<>();
        for (SchematicPlacement p : mgr.getAllSchematicsPlacements()) {
            if (id.equals(receivedId(p))) toRemove.add(p);
        }
        for (SchematicPlacement p : toRemove) mgr.removeSchematicPlacement(p);
        return toRemove.size();
    }

    /** Legt einen empfangenen Bauplan als Platzierung in Litematica an. */
    public static boolean addReceived(String id, int version, String name, String owner, byte[] data,
                                      BlockPos origin, String rotation, String mirror, boolean isOwner) throws IOException {
        removeReceived(id);
        Path file = receiveDir().resolve(id + "_v" + version + ".litematic");
        Files.write(file, data);

        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(file);
        if (schematic == null) return false;

        String display = isOwner ? name : name + " (" + owner + ")";
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, display, true, true);
        if (placement.isLocked()) placement.toggleLocked();
        placement.setRotation(parseRotation(rotation), (IMessageConsumer) null);
        placement.setMirror(parseMirror(mirror), (IMessageConsumer) null);
        placement.setOrigin(origin, (IStringConsumer) null);
        // Fremde Bauplaene sperren, damit niemand sie aus Versehen verschiebt
        if (!isOwner && !placement.isLocked()) placement.toggleLocked();

        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        mgr.addSchematicPlacement(placement, true);
        mgr.setSelectedSchematicPlacement(placement);
        if (isOwner && placement.getHashId() != null) {
            LifematicClient.CONFIG.link(placement.getHashId().toString(), id);
        }
        return true;
    }

    static Rotation parseRotation(String s) {
        try { return Rotation.valueOf(s); } catch (IllegalArgumentException e) { return Rotation.NONE; }
    }

    static Mirror parseMirror(String s) {
        try { return Mirror.valueOf(s); } catch (IllegalArgumentException e) { return Mirror.NONE; }
    }

    // ------------------------------------------------------------------ Lifematic-Menue

    /** Server-ID der eigenen, schon geteilten Platzierung (oder null). */
    public static String linkedId(SchematicPlacement p) {
        return p.getHashId() == null ? null : LifematicClient.CONFIG.idFor(p.getHashId().toString());
    }

    /** Litematicas eigene Tasten und den Werkzeug-Stock abschalten: bedient wird alles ueber Lifematic. */
    public static void hideLitematicaControls() {
        int n = 0;
        try {
            for (IHotkey hk : Hotkeys.HOTKEY_LIST) {
                hk.getKeybind().clearKeys();
                n++;
            }
            for (Class<?> c : Configs.class.getDeclaredClasses()) {
                try {
                    Field f = c.getField("HOTKEY_LIST");
                    Object v = f.get(null);
                    if (v instanceof List<?> list) {
                        for (Object o : list) {
                            if (o instanceof IHotkey hk) {
                                hk.getKeybind().clearKeys();
                                n++;
                            }
                        }
                    }
                } catch (NoSuchFieldException | IllegalAccessException ignored) {
                }
            }
            Configs.Generic.TOOL_ITEM_ENABLED.setBooleanValue(false);
        } catch (Throwable t) {
            LifematicClient.LOG.warn("Litematica-Tasten konnten nicht abgeschaltet werden", t);
        }
        LifematicClient.LOG.info("Lifematic: {} Litematica-Tasten abgeschaltet", n);
    }

    /** Alle .litematic-Dateien im schematics-Ordner (ohne empfangene Server-Bauplaene). */
    public static List<Path> localFiles() {
        List<Path> out = new ArrayList<>();
        try {
            Path base = DataManager.getSchematicsBaseDirectory();
            Path recv = receiveDir().toAbsolutePath().normalize();
            try (Stream<Path> st = Files.walk(base, 4)) {
                st.filter(f -> f.getFileName().toString().endsWith(".litematic"))
                  .filter(f -> !f.toAbsolutePath().normalize().startsWith(recv))
                  .sorted()
                  .forEach(out::add);
            }
        } catch (IOException e) {
            LifematicClient.LOG.warn("schematics-Ordner nicht lesbar", e);
        }
        return out;
    }

    /** Oeffnet eine Datei als neue Platzierung an der Position des Spielers. */
    public static String open(Path file) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "Kein Spieler";
        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(file);
        if (schematic == null) return "Datei konnte nicht gelesen werden.";
        String name = file.getFileName().toString().replace(".litematic", "");
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, mc.player.blockPosition(), name, true, true);
        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        mgr.addSchematicPlacement(placement, false);
        mgr.setSelectedSchematicPlacement(placement);
        return "«" + name + "» steht jetzt bei dir.";
    }

    public static List<SchematicPlacement> all() {
        return DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
    }

    public static void select(SchematicPlacement p) {
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(p);
    }

    public static void selectNext() {
        List<SchematicPlacement> list = all();
        if (list.isEmpty()) return;
        SchematicPlacement cur = selected();
        int i = cur == null ? -1 : list.indexOf(cur);
        select(list.get((i + 1) % list.size()));
    }

    public static boolean canEdit(SchematicPlacement p) {
        return p != null && !p.isLocked() && !isForeign(p);
    }

    /** Bauplan von jemand anderem (vom Server empfangen, nicht selbst geteilt)? */
    public static boolean isForeign(SchematicPlacement p) {
        return p != null && receivedId(p) != null && linkedId(p) == null;
    }

    /** Eigene Platzierung ver- oder entriegeln. Fremde bleiben immer verriegelt. */
    public static void toggleLock(SchematicPlacement p) {
        if (p == null || isForeign(p)) return;
        p.toggleLocked();
    }

    /** Verriegelte eigene Bauplaene lassen sich nicht entfernen; fremde Vorschauen schon (betrifft nur dich). */
    public static boolean canRemove(SchematicPlacement p) {
        return p != null && (isForeign(p) || !p.isLocked());
    }

    /** Oeffnet den schematics-Ordner im Datei-Explorer. */
    public static void openSchematicsFolder() {
        try {
            com.mojang.blaze3d.Blaze3D.openUri(DataManager.getSchematicsBaseDirectory().toAbsolutePath().toUri());
        } catch (Throwable t) {
            LifematicClient.LOG.warn("schematics-Ordner konnte nicht geoeffnet werden", t);
        }
    }

    public static void move(SchematicPlacement p, int dx, int dy, int dz) {
        if (!canEdit(p)) return;
        p.setOrigin(p.getOrigin().offset(dx, dy, dz), (IStringConsumer) null);
    }

    public static void moveToPlayer(SchematicPlacement p) {
        Minecraft mc = Minecraft.getInstance();
        if (!canEdit(p) || mc.player == null) return;
        p.setOrigin(mc.player.blockPosition(), (IStringConsumer) null);
    }

    public static void rotate(SchematicPlacement p) {
        if (!canEdit(p)) return;
        p.setRotation(p.getRotation().getRotated(Rotation.CLOCKWISE_90), (IMessageConsumer) null);
    }

    public static void mirror(SchematicPlacement p) {
        if (!canEdit(p)) return;
        Mirror next = switch (p.getMirror()) {
            case NONE -> Mirror.LEFT_RIGHT;
            case LEFT_RIGHT -> Mirror.FRONT_BACK;
            default -> Mirror.NONE;
        };
        p.setMirror(next, (IMessageConsumer) null);
    }

    public static void remove(SchematicPlacement p) {
        if (!canRemove(p)) return;
        DataManager.getSchematicPlacementManager().removeSchematicPlacement(p);
    }

    // Anzeige

    public static boolean renderingOn() {
        return Configs.Visuals.ENABLE_RENDERING.getBooleanValue();
    }

    public static void toggleRendering() {
        Configs.Visuals.ENABLE_RENDERING.setBooleanValue(!renderingOn());
    }

    public static boolean singleLayer() {
        return DataManager.getRenderLayerRange().getLayerMode() != LayerMode.ALL;
    }

    public static void toggleSingleLayer() {
        LayerRange r = DataManager.getRenderLayerRange();
        if (r.getLayerMode() == LayerMode.ALL) {
            r.setAxis(Direction.Axis.Y);
            r.setLayerMode(LayerMode.SINGLE_LAYER, false);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) r.setLayerSingle(mc.player.blockPosition().getY());
        } else {
            follow = false;
            r.setLayerMode(LayerMode.ALL, false);
        }
    }

    // Ebene folgt dem Spieler
    private static boolean follow;
    private static int followedY = Integer.MIN_VALUE;

    public static boolean followPlayer() {
        return follow;
    }

    public static void setFollowPlayer(boolean on) {
        follow = on;
        followedY = Integer.MIN_VALUE;
        if (on) tickFollow();
    }

    /** Jeden Tick: Ebene auf die aktuelle Fusshoehe setzen, wenn «Ebene folgt mir» an ist. */
    public static void tickFollow() {
        if (!follow) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int y = mc.player.blockPosition().getY();
        if (y == followedY) return;
        followedY = y;
        LayerRange r = DataManager.getRenderLayerRange();
        if (r.getLayerMode() == LayerMode.ALL) {
            r.setAxis(Direction.Axis.Y);
            r.setLayerMode(LayerMode.SINGLE_LAYER, false);
        }
        r.setLayerSingle(y);
    }

    public static void moveLayer(int amount) {
        follow = false;
        LayerRange r = DataManager.getRenderLayerRange();
        if (r.getLayerMode() == LayerMode.ALL) toggleSingleLayer();
        r.moveLayer(amount);
    }

    public static void layerToPlayer() {
        LayerRange r = DataManager.getRenderLayerRange();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (r.getLayerMode() == LayerMode.ALL) toggleSingleLayer();
        r.setLayerSingle(mc.player.blockPosition().getY());
    }

    public static String layerText() {
        LayerRange r = DataManager.getRenderLayerRange();
        return r.getLayerMode() == LayerMode.ALL ? "alle Ebenen" : "Ebene Y " + r.getLayerSingle();
    }
}
