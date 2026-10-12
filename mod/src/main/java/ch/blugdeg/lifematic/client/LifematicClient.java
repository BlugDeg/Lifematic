package ch.blugdeg.lifematic.client;

import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LifematicClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("lifematic");
    public static final LinkConfig CONFIG = new LinkConfig();

    private static KeyMapping menuKey;
    static boolean serverHasPlugin;
    private static boolean litematicaHidden;

    // Laufender Upload: welche Platzierung wird gerade hochgeladen?
    static SchematicPlacement uploading;

    // Laufender Download
    private static String dlId, dlName, dlOwner, dlRotation, dlMirror;
    private static int dlVersion, dlReceived;
    private static byte[] dlData;
    private static BlockPos dlOrigin;
    private static boolean dlIsOwner;

    /** Bauplaene auf dem Server (Antwort auf C_LIST). */
    public record ServerEntry(String id, int version, String name, String owner, String world, int percent,
                              boolean helpersAllowed, boolean mine, BlockPos center) {}
    static List<ServerEntry> serverList = new ArrayList<>();

    /** Materialliste vom Server (Antwort auf C_MATERIALS). */
    public record Materials(String id, int total, int correct, List<Map.Entry<String, Integer>> missing) {}
    static final Map<String, Materials> materials = new HashMap<>();

    @Override
    public void onInitializeClient() {
        CONFIG.load();

        PayloadTypeRegistry.serverboundPlay().register(LifematicPayload.TYPE, LifematicPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LifematicPayload.TYPE, LifematicPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(LifematicPayload.TYPE,
                (payload, context) -> context.client().execute(() -> handle(payload.data())));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            serverHasPlugin = false;
            dlData = null;
            serverList = new ArrayList<>();
            materials.clear();
            client.execute(LitematicaBridge::hideLitematicaControls);
            sendHello();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            serverHasPlugin = false;
            uploading = null;
            dlData = null;
        });

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("lifematic", "main"));
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.lifematic.menu",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!litematicaHidden) {
                litematicaHidden = true;
                LitematicaBridge.hideLitematicaControls();
            }
            while (menuKey.consumeClick()) {
                if (client.player != null && client.gui.screen() == null) {
                    client.gui.setScreen(new LifematicScreen());
                }
            }
        });
    }

    static void sendHello() {
        send(out -> {
            out.writeByte(Protocol.C_HELLO);
            out.writeInt(Protocol.VERSION);
        });
    }

    // ------------------------------------------------------------------ Aktionen fuer die Menues

    static void requestList() {
        if (!serverHasPlugin) sendHello();
        send(out -> out.writeByte(Protocol.C_LIST));
    }

    static void requestMaterials(String id) {
        send(out -> {
            out.writeByte(Protocol.C_MATERIALS);
            out.writeUTF(id);
        });
    }

    static void requestBlueprint(String id) {
        send(out -> {
            out.writeByte(Protocol.C_REQUEST);
            out.writeUTF(id);
        });
    }

    /** Laedt die Platzierung als neuen Bauplan hoch oder aktualisiert den verknuepften. */
    static String share(SchematicPlacement p) {
        if (!serverHasPlugin) return "Dieser Server hat kein Lifematic-Plugin.";
        if (uploading != null) return "Ein Upload laeuft noch …";
        String received = LitematicaBridge.receivedId(p);
        String linked = LitematicaBridge.linkedId(p);
        if (received != null && linked == null) return "Das ist ein Bauplan von jemand anderem.";
        LitematicaBridge.PlacementInfo info = LitematicaBridge.describe(p);
        if (info == null) return "Litematica liefert fuer diese Platzierung keinen Bereich.";
        if (info.file() == null) return "Diese Platzierung hat keine Datei. Oeffne den Bauplan ueber «Bauplan oeffnen …».";
        byte[] data;
        try {
            data = Files.readAllBytes(info.file());
        } catch (IOException e) {
            return "Datei konnte nicht gelesen werden: " + info.file().getFileName();
        }
        String existingId = linked == null ? "" : linked;
        uploading = p;
        final byte[] fileData = data;
        boolean ok = send(out -> {
            out.writeByte(Protocol.C_UPLOAD_BEGIN);
            out.writeUTF(existingId);
            out.writeUTF(info.name() == null ? "" : info.name());
            out.writeUTF(info.file().getFileName().toString());
            out.writeInt(fileData.length);
            out.writeInt(info.origin().getX());
            out.writeInt(info.origin().getY());
            out.writeInt(info.origin().getZ());
            out.writeUTF(info.rotation().name());
            out.writeUTF(info.mirror().name());
            out.writeInt(info.min().getX());
            out.writeInt(info.min().getY());
            out.writeInt(info.min().getZ());
            out.writeInt(info.max().getX());
            out.writeInt(info.max().getY());
            out.writeInt(info.max().getZ());
        });
        for (int off = 0; ok && off < fileData.length; off += Protocol.UPLOAD_CHUNK) {
            final int o = off;
            final int len = Math.min(Protocol.UPLOAD_CHUNK, fileData.length - off);
            ok = send(out -> {
                out.writeByte(Protocol.C_UPLOAD_CHUNK);
                out.writeInt(o);
                out.writeInt(len);
                out.write(fileData, o, len);
            });
        }
        if (ok) ok = send(out -> out.writeByte(Protocol.C_UPLOAD_END));
        if (!ok) {
            uploading = null;
            return "Senden fehlgeschlagen.";
        }
        return (existingId.isEmpty() ? "Wird geteilt" : "Wird aktualisiert") + " (" + (fileData.length / 1024 + 1) + " KB) …";
    }

    // ------------------------------------------------------------------ Empfangen

    private static void handle(byte[] msg) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(msg))) {
            byte type = in.readByte();
            switch (type) {
                case Protocol.S_HELLO -> {
                    serverHasPlugin = true;
                    int v = in.readInt();
                    if (v != Protocol.VERSION) say("Lifematic-Mod und Server-Plugin passen nicht zusammen. Bitte Mod aktualisieren.", ChatFormatting.RED);
                }
                case Protocol.S_UPLOAD_RESULT -> {
                    boolean ok = in.readBoolean();
                    in.readUTF(); // Text kommt bereits als Chatnachricht vom Server
                    String id = in.readUTF();
                    if (ok && uploading != null && uploading.getHashId() != null && !id.isEmpty()) {
                        CONFIG.link(uploading.getHashId().toString(), id);
                    }
                    uploading = null;
                    LifematicScreen.refreshIfOpen();
                }
                case Protocol.S_FILE_BEGIN -> {
                    dlId = in.readUTF();
                    dlVersion = in.readInt();
                    dlName = in.readUTF();
                    dlOwner = in.readUTF();
                    int size = in.readInt();
                    dlOrigin = new BlockPos(in.readInt(), in.readInt(), in.readInt());
                    dlRotation = in.readUTF();
                    dlMirror = in.readUTF();
                    dlIsOwner = in.readBoolean();
                    if (size <= 0 || size > 64 * 1024 * 1024) {
                        dlData = null;
                        return;
                    }
                    dlData = new byte[size];
                    dlReceived = 0;
                }
                case Protocol.S_FILE_CHUNK -> {
                    if (dlData == null) return;
                    int off = in.readInt();
                    int len = in.readInt();
                    if (off < 0 || len < 0 || off + len > dlData.length) {
                        dlData = null;
                        return;
                    }
                    in.readFully(dlData, off, len);
                    dlReceived += len;
                }
                case Protocol.S_FILE_END -> {
                    String id = in.readUTF();
                    if (dlData == null || !id.equals(dlId)) return;
                    if (dlReceived != dlData.length) {
                        say("Bauplan unvollstaendig empfangen.", ChatFormatting.RED);
                        dlData = null;
                        return;
                    }
                    boolean ok = LitematicaBridge.addReceived(dlId, dlVersion, dlName, dlOwner, dlData,
                            dlOrigin, dlRotation, dlMirror, dlIsOwner);
                    dlData = null;
                    if (ok) say("«" + dlName + "» ist geladen. Taste R oeffnet das Lifematic-Menue.", ChatFormatting.GREEN);
                    else say("Der Bauplan konnte nicht geladen werden.", ChatFormatting.RED);
                    LifematicScreen.refreshIfOpen();
                }
                case Protocol.S_REMOVE -> {
                    String id = in.readUTF();
                    int n = LitematicaBridge.removeReceived(id);
                    if (n > 0) say("Bauplan-Vorschau entfernt.", ChatFormatting.GRAY);
                    LifematicScreen.refreshIfOpen();
                }
                case Protocol.S_SYNC -> {
                    int n = in.readInt();
                    Map<String, Integer> valid = new HashMap<>();
                    for (int i = 0; i < n; i++) valid.put(in.readUTF(), in.readInt());
                    Map<String, Integer> have = LitematicaBridge.receivedVersions();
                    List<String> keep = new ArrayList<>();
                    for (Map.Entry<String, Integer> e : have.entrySet()) {
                        Integer v = valid.get(e.getKey());
                        if (v == null) {
                            LitematicaBridge.removeReceived(e.getKey());
                            CONFIG.unlinkId(e.getKey());
                        } else {
                            keep.add(e.getKey());
                            if (!v.equals(e.getValue())) requestBlueprint(e.getKey());
                        }
                    }
                    send(out -> {
                        out.writeByte(Protocol.C_HAVE);
                        out.writeInt(keep.size());
                        for (String id : keep) {
                            out.writeUTF(id);
                            out.writeInt(valid.get(id));
                        }
                    });
                }
                case Protocol.S_LIST -> {
                    int n = in.readInt();
                    List<ServerEntry> list = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        String id = in.readUTF();
                        int ver = in.readInt();
                        String name = in.readUTF();
                        String owner = in.readUTF();
                        String world = in.readUTF();
                        int pct = in.readInt();
                        boolean helpers = in.readBoolean();
                        boolean mine = in.readBoolean();
                        BlockPos c = new BlockPos(in.readInt(), in.readInt(), in.readInt());
                        list.add(new ServerEntry(id, ver, name, owner, world, pct, helpers, mine, c));
                    }
                    serverList = list;
                    LifematicScreen.refreshIfOpen();
                }
                case Protocol.S_MATERIALS -> {
                    String id = in.readUTF();
                    int total = in.readInt();
                    int correct = in.readInt();
                    int n = in.readInt();
                    List<Map.Entry<String, Integer>> miss = new ArrayList<>();
                    for (int i = 0; i < n; i++) miss.add(Map.entry(in.readUTF(), in.readInt()));
                    materials.put(id, new Materials(id, total, correct, miss));
                    LifematicScreen.refreshIfOpen();
                }
                default -> LOG.debug("Unbekanntes Lifematic-Paket {}", type);
            }
        } catch (Exception e) {
            LOG.warn("Lifematic-Paket konnte nicht verarbeitet werden", e);
        }
    }

    // ------------------------------------------------------------------ Hilfen

    @FunctionalInterface
    interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    static boolean send(Writer w) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            w.write(out);
            out.flush();
            ClientPlayNetworking.send(new LifematicPayload(bos.toByteArray()));
            return true;
        } catch (Exception e) {
            LOG.warn("Senden an den Server fehlgeschlagen", e);
            return false;
        }
    }

    static void say(String text, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[Lifematic] ").withStyle(ChatFormatting.DARK_AQUA)
                    .append(Component.literal(text).withStyle(color)));
        }
    }
}
