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

public final class LifematicClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("lifematic");
    public static final LinkConfig CONFIG = new LinkConfig();

    private static KeyMapping shareKey;
    private static boolean serverHasPlugin;

    // Bestaetigung fuers Teilen (zweimal B innerhalb von 5 Sekunden)
    private static SchematicPlacement pendingShare;
    private static long pendingSince;
    // Laufender Upload: welche Platzierung wird gerade hochgeladen?
    private static SchematicPlacement uploading;

    // Laufender Download
    private static String dlId, dlName, dlOwner, dlRotation, dlMirror;
    private static int dlVersion, dlReceived;
    private static byte[] dlData;
    private static BlockPos dlOrigin;
    private static boolean dlIsOwner;

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
            send(out -> {
                out.writeByte(Protocol.C_HELLO);
                out.writeInt(Protocol.VERSION);
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            serverHasPlugin = false;
            pendingShare = null;
            uploading = null;
            dlData = null;
        });

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("lifematic", "main"));
        shareKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.lifematic.share",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (shareKey.consumeClick()) {
                onShareKey(client);
            }
        });
    }

    // ------------------------------------------------------------------ Teilen

    private static void onShareKey(Minecraft mc) {
        if (mc.player == null) return;
        if (!serverHasPlugin && ClientPlayNetworking.canSend(LifematicPayload.TYPE)) {
            send(out -> {
                out.writeByte(Protocol.C_HELLO);
                out.writeInt(Protocol.VERSION);
            });
        }
        if (!serverHasPlugin) {
            say("Dieser Server hat kein Lifematic-Plugin.", ChatFormatting.RED);
            return;
        }
        if (uploading != null) {
            say("Ein Upload laeuft noch …", ChatFormatting.GRAY);
            return;
        }
        SchematicPlacement p = LitematicaBridge.selected();
        if (p == null) {
            say("Waehle zuerst in Litematica eine Platzierung aus (M → Platzierungen verwalten).", ChatFormatting.RED);
            return;
        }
        String received = LitematicaBridge.receivedId(p);
        String linked = CONFIG.idFor(p.getHashId() == null ? null : p.getHashId().toString());
        if (received != null && linked == null) {
            say("Das ist ein Bauplan von jemand anderem. Nur der Ersteller kann ihn aendern.", ChatFormatting.RED);
            return;
        }
        long now = System.currentTimeMillis();
        if (pendingShare != p || now - pendingSince > 5000) {
            pendingShare = p;
            pendingSince = now;
            String what = linked != null ? "aktualisieren" : "mit allen teilen";
            say("«" + p.getName() + "» " + what + "? Druecke B nochmal zum Bestaetigen.", ChatFormatting.YELLOW);
            return;
        }
        pendingShare = null;
        upload(p, linked == null ? "" : linked);
    }

    private static void upload(SchematicPlacement p, String existingId) {
        LitematicaBridge.PlacementInfo info = LitematicaBridge.describe(p);
        if (info == null || info.file() == null) {
            say("Diese Platzierung hat keine Datei oder keinen Bereich. Speichere den Bauplan zuerst als .litematic.", ChatFormatting.RED);
            return;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(info.file());
        } catch (IOException e) {
            say("Datei konnte nicht gelesen werden: " + info.file().getFileName(), ChatFormatting.RED);
            return;
        }
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
        if (ok) {
            say("Bauplan wird hochgeladen (" + (fileData.length / 1024 + 1) + " KB) …", ChatFormatting.GRAY);
        } else {
            uploading = null;
            say("Senden fehlgeschlagen.", ChatFormatting.RED);
        }
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
                    if (ok) say("«" + dlName + "» ist geladen. Mit M siehst du die Materialliste.", ChatFormatting.GREEN);
                    else say("Litematica konnte den Bauplan nicht laden.", ChatFormatting.RED);
                }
                case Protocol.S_REMOVE -> {
                    String id = in.readUTF();
                    int n = LitematicaBridge.removeReceived(id);
                    if (n > 0) say("Bauplan-Vorschau entfernt.", ChatFormatting.GRAY);
                }
                default -> LOG.debug("Unbekanntes Lifematic-Paket {}", type);
            }
        } catch (Exception e) {
            LOG.warn("Lifematic-Paket konnte nicht verarbeitet werden", e);
        }
    }

    // ------------------------------------------------------------------ Hilfen

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static boolean send(Writer w) {
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
