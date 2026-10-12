package ch.blugdeg.lifematic.client;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import fi.dy.masa.malilib.interfaces.IStringConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        Box box = p.getEclosingBox();
        if (box == null || box.getPos1() == null || box.getPos2() == null) return null;
        BlockPos a = box.getPos1(), b = box.getPos2();
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        String hash = p.getHashId() == null ? "" : p.getHashId().toString();
        return new PlacementInfo(p.getName(), p.getSchematicFile(), p.getOrigin(), p.getRotation(), p.getMirror(), min, max, hash);
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
}
