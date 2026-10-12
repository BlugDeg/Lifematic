package ch.blugdeg.lifematic;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

public final class LifematicPlugin extends JavaPlugin implements Listener, PluginMessageListener, TabExecutor {

    private BlueprintStore store;

    /** Laufende Uploads pro Spieler. */
    private final Map<UUID, Upload> uploads = new HashMap<>();
    /** Welche Bauplaene (id -> version) ein Spieler gerade in seinem Litematica hat. */
    private final Map<UUID, Map<String, Integer>> loaded = new HashMap<>();
    /** Spieler mit Mod (haben HELLO geschickt). */
    private final Set<UUID> modPlayers = new HashSet<>();
    /** In welchem Bereich steht der Spieler gerade? */
    private final Map<UUID, String> currentArea = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    /** Spieler, die in dieser Sitzung schon den Mod-Hinweis bekommen haben. */
    private final Set<UUID> toldAboutMod = new HashSet<>();

    /** Fortschritt: welche Soll-Positionen stimmen schon? */
    private final Map<String, BitSet> correctBits = new HashMap<>();
    private final Map<String, Integer> cursor = new HashMap<>();
    private final Map<String, Material[]> materialCache = new HashMap<>();

    private static final int AREA_MARGIN = 6;

    private static final class Upload {
        String existingId, name, fileName, rotation, mirror;
        int size, ox, oy, oz, minX, minY, minZ, maxX, maxY, maxZ;
        byte[] data;
        int received;
        BitSet chunks = new BitSet();
        long started = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ Lebenszyklus

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new BlueprintStore(getDataFolder());
        store.load();
        getLogger().info(store.blueprints.size() + " Bauplaene geladen.");
        for (Blueprint bp : store.blueprints.values()) loadExpectedAsync(bp);

        getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, Protocol.CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand cmd = getCommand("lifematic");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Fortschritt laufend nachrechnen und Anzeigen aktualisieren
        getServer().getScheduler().runTaskTimer(this, this::progressTick, 40L, 1L);
        getServer().getScheduler().runTaskTimer(this, this::refreshBars, 40L, 20L);
        // haengende Uploads aufraeumen
        getServer().getScheduler().runTaskTimer(this, () -> uploads.values().removeIf(u -> System.currentTimeMillis() - u.started > 120_000), 1200L, 1200L);

        for (Player p : Bukkit.getOnlinePlayers()) checkArea(p, p.getLocation());
    }

    @Override
    public void onDisable() {
        saveStore();
        for (Map.Entry<UUID, BossBar> e : bars.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.hideBossBar(e.getValue());
        }
        bars.clear();
    }

    private void saveStore() {
        try {
            store.save();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "data.yml konnte nicht gespeichert werden", e);
        }
    }

    private void loadExpectedAsync(Blueprint bp) {
        File f = store.fileOf(bp);
        int version = bp.version;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try (InputStream in = new FileInputStream(f)) {
                ExpectedBlocks eb = ExpectedBlocks.load(in, bp.ox, bp.oy, bp.oz, bp.rotation, bp.mirror);
                getServer().getScheduler().runTask(this, () -> {
                    if (bp.version != version || !store.blueprints.containsKey(bp.id)) return;
                    bp.expected = eb;
                    bp.correct = -1;
                    correctBits.put(bp.id, new BitSet(eb.size()));
                    cursor.put(bp.id, 0);
                    Material[] mats = new Material[eb.typeNames.length];
                    for (int i = 0; i < mats.length; i++) mats[i] = Material.matchMaterial(eb.typeNames[i]);
                    materialCache.put(bp.id, mats);
                    if (eb.size() > 0 && (eb.minX != bp.minX || eb.maxX != bp.maxX || eb.minZ != bp.minZ || eb.maxZ != bp.maxZ)) {
                        getLogger().warning("Bauplan " + bp.id + ": berechneter Bereich " + eb.minX + "," + eb.minY + "," + eb.minZ
                                + " .. " + eb.maxX + "," + eb.maxY + "," + eb.maxZ + " weicht vom Litematica-Bereich ab. Fortschritt kann ungenau sein.");
                    }
                });
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Bauplan " + bp.id + " konnte nicht gelesen werden", ex);
            }
        });
    }

    // ------------------------------------------------------------------ Netzwerk

    private void send(Player p, ByteArrayOutputStream bos) {
        p.sendPluginMessage(this, Protocol.CHANNEL, bos.toByteArray());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Protocol.CHANNEL.equals(channel) || message.length == 0) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            byte type = in.readByte();
            switch (type) {
                case Protocol.C_HELLO -> {
                    int v = in.readInt();
                    modPlayers.add(player.getUniqueId());
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(bos);
                    out.writeByte(Protocol.S_HELLO);
                    out.writeInt(Protocol.VERSION);
                    send(player, bos);
                    if (v != Protocol.VERSION) {
                        player.sendMessage(Component.text("Deine Lifematic-Mod passt nicht zur Server-Version. Bitte neu herunterladen.", NamedTextColor.RED));
                    }
                    // Bereich neu pruefen, jetzt mit Mod
                    currentArea.remove(player.getUniqueId());
                    checkArea(player, player.getLocation());
                }
                case Protocol.C_UPLOAD_BEGIN -> handleUploadBegin(player, in);
                case Protocol.C_UPLOAD_CHUNK -> handleUploadChunk(player, in);
                case Protocol.C_UPLOAD_END -> handleUploadEnd(player);
                case Protocol.C_REQUEST -> {
                    String id = in.readUTF();
                    Blueprint bp = store.blueprints.get(id);
                    if (bp != null) sendBlueprint(player, bp);
                }
                default -> getLogger().fine("Unbekanntes Paket " + type + " von " + player.getName());
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Fehlerhaftes Paket von " + player.getName(), e);
        }
    }

    private void handleUploadBegin(Player player, DataInputStream in) throws IOException {
        Upload u = new Upload();
        u.existingId = in.readUTF();
        u.name = in.readUTF();
        u.fileName = in.readUTF();
        u.size = in.readInt();
        u.ox = in.readInt(); u.oy = in.readInt(); u.oz = in.readInt();
        u.rotation = in.readUTF();
        u.mirror = in.readUTF();
        u.minX = in.readInt(); u.minY = in.readInt(); u.minZ = in.readInt();
        u.maxX = in.readInt(); u.maxY = in.readInt(); u.maxZ = in.readInt();

        if (!player.hasPermission("lifematic.share")) {
            uploadResult(player, false, "Du darfst keine Bauplaene teilen.", "");
            return;
        }
        int maxKb = getConfig().getInt("max-dateigroesse-kb", 4096);
        if (u.size <= 0 || u.size > maxKb * 1024) {
            uploadResult(player, false, "Datei zu gross (max. " + maxKb + " KB).", "");
            return;
        }
        if (!u.existingId.isEmpty()) {
            Blueprint old = store.blueprints.get(u.existingId);
            if (old == null) {
                u.existingId = ""; // Bauplan wurde inzwischen geloescht -> neu anlegen
            } else if (!old.owner.equals(player.getUniqueId()) && !player.hasPermission("lifematic.admin")) {
                uploadResult(player, false, "Nur " + old.ownerName + " darf diesen Bauplan aendern.", "");
                return;
            }
        }
        if (u.existingId.isEmpty() && !player.hasPermission("lifematic.admin")) {
            long count = store.blueprints.values().stream().filter(b -> b.owner.equals(player.getUniqueId())).count();
            int max = getConfig().getInt("max-bauplaene-pro-spieler", 5);
            if (count >= max) {
                uploadResult(player, false, "Du hast schon " + count + " Bauplaene geteilt (max. " + max + "). Loesche zuerst einen.", "");
                return;
            }
        }
        long volume = (long) (u.maxX - u.minX + 1) * (u.maxY - u.minY + 1) * (u.maxZ - u.minZ + 1);
        if (u.maxX < u.minX || u.maxY < u.minY || u.maxZ < u.minZ || volume > 50_000_000L) {
            uploadResult(player, false, "Ungueltiger Bereich.", "");
            return;
        }
        u.data = new byte[u.size];
        uploads.put(player.getUniqueId(), u);
    }

    private void handleUploadChunk(Player player, DataInputStream in) throws IOException {
        Upload u = uploads.get(player.getUniqueId());
        if (u == null) return;
        int offset = in.readInt();
        int len = in.readInt();
        if (offset < 0 || len < 0 || len > Protocol.UPLOAD_CHUNK || offset + len > u.size) {
            uploads.remove(player.getUniqueId());
            uploadResult(player, false, "Upload beschaedigt.", "");
            return;
        }
        in.readFully(u.data, offset, len);
        int idx = offset / Protocol.UPLOAD_CHUNK;
        if (!u.chunks.get(idx)) {
            u.chunks.set(idx);
            u.received += len;
        }
    }

    private void handleUploadEnd(Player player) {
        Upload u = uploads.remove(player.getUniqueId());
        if (u == null) return;
        if (u.received != u.size) {
            uploadResult(player, false, "Upload unvollstaendig (" + u.received + " von " + u.size + " Bytes).", "");
            return;
        }
        // Datei pruefen, bevor sie gespeichert wird
        ExpectedBlocks test;
        try {
            test = ExpectedBlocks.load(new ByteArrayInputStream(u.data), u.ox, u.oy, u.oz, u.rotation, u.mirror);
        } catch (Exception e) {
            uploadResult(player, false, "Die Datei ist kein gueltiger Litematica-Bauplan.", "");
            return;
        }

        Blueprint bp;
        boolean isNew = u.existingId.isEmpty();
        if (isNew) {
            bp = new Blueprint();
            bp.id = store.newId();
            bp.owner = player.getUniqueId();
            bp.ownerName = player.getName();
            bp.created = System.currentTimeMillis();
            bp.version = 1;
        } else {
            bp = store.blueprints.get(u.existingId);
            bp.version++;
            // alte Datei loeschen
            //noinspection ResultOfMethodCallIgnored
            store.fileOf(bp).delete();
        }
        bp.name = u.name.isBlank() ? "Bauplan" : u.name;
        bp.world = player.getWorld().getName();
        bp.fileName = bp.id + "_v" + bp.version + ".litematic";
        bp.ox = u.ox; bp.oy = u.oy; bp.oz = u.oz;
        bp.rotation = u.rotation; bp.mirror = u.mirror;
        bp.minX = u.minX; bp.minY = u.minY; bp.minZ = u.minZ;
        bp.maxX = u.maxX; bp.maxY = u.maxY; bp.maxZ = u.maxZ;
        try {
            Files.write(store.fileOf(bp).toPath(), u.data);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Bauplan konnte nicht gespeichert werden", e);
            uploadResult(player, false, "Speichern auf dem Server fehlgeschlagen.", "");
            return;
        }
        store.blueprints.put(bp.id, bp);
        saveStore();
        loadExpectedAsync(bp);

        loaded.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(bp.id, bp.version);
        uploadResult(player, true, (isNew ? "Geteilt: " : "Aktualisiert: ") + bp.name + " (" + test.size() + " Bloecke)", bp.id);
        getLogger().info(player.getName() + (isNew ? " teilt " : " aktualisiert ") + bp.name + " [" + bp.id + "] v" + bp.version);

        // Wer die alte Version geladen hat, bekommt die neue automatisch
        if (!isNew) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.equals(player)) continue;
                Map<String, Integer> l = loaded.get(p.getUniqueId());
                if (l != null && l.containsKey(bp.id)) {
                    sendBlueprint(p, bp);
                    p.sendMessage(Component.text("Bauplan «" + bp.name + "» wurde von " + bp.ownerName + " aktualisiert.", NamedTextColor.GRAY));
                }
            }
        }
        // Bereiche aller Spieler neu bewerten
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (bp.id.equals(currentArea.get(p.getUniqueId()))) currentArea.remove(p.getUniqueId());
            checkArea(p, p.getLocation());
        }
    }

    private void uploadResult(Player p, boolean ok, String msg, String id) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(Protocol.S_UPLOAD_RESULT);
            out.writeBoolean(ok);
            out.writeUTF(msg);
            out.writeUTF(id);
            send(p, bos);
        } catch (IOException ignored) {}
        p.sendMessage(Component.text(msg, ok ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void sendBlueprint(Player p, Blueprint bp) {
        if (!hasMod(p)) {
            tellAboutMod(p);
            return;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(store.fileOf(bp).toPath());
        } catch (IOException e) {
            p.sendMessage(Component.text("Bauplan-Datei fehlt auf dem Server.", NamedTextColor.RED));
            return;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(Protocol.S_FILE_BEGIN);
            out.writeUTF(bp.id);
            out.writeInt(bp.version);
            out.writeUTF(bp.name);
            out.writeUTF(bp.ownerName);
            out.writeInt(data.length);
            out.writeInt(bp.ox); out.writeInt(bp.oy); out.writeInt(bp.oz);
            out.writeUTF(bp.rotation);
            out.writeUTF(bp.mirror);
            out.writeBoolean(bp.owner.equals(p.getUniqueId()));
            send(p, bos);
            for (int off = 0; off < data.length; off += Protocol.DOWNLOAD_CHUNK) {
                int len = Math.min(Protocol.DOWNLOAD_CHUNK, data.length - off);
                ByteArrayOutputStream cb = new ByteArrayOutputStream(len + 16);
                DataOutputStream co = new DataOutputStream(cb);
                co.writeByte(Protocol.S_FILE_CHUNK);
                co.writeInt(off);
                co.writeInt(len);
                co.write(data, off, len);
                send(p, cb);
            }
            ByteArrayOutputStream eb = new ByteArrayOutputStream();
            DataOutputStream eo = new DataOutputStream(eb);
            eo.writeByte(Protocol.S_FILE_END);
            eo.writeUTF(bp.id);
            send(p, eb);
        } catch (IOException ignored) {}
        loaded.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(bp.id, bp.version);
        markAnswered(p, bp);
    }

    private void sendRemove(Player p, String id) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(Protocol.S_REMOVE);
            out.writeUTF(id);
            send(p, bos);
        } catch (IOException ignored) {}
    }

    private boolean hasMod(Player p) {
        return modPlayers.contains(p.getUniqueId()) || p.getListeningPluginChannels().contains(Protocol.CHANNEL);
    }

    private void tellAboutMod(Player p) {
        String link = getConfig().getString("mod-download", "https://mc.it-en.ch");
        p.sendMessage(Component.text("Um Bauplaene als Vorschau zu sehen, brauchst du die Lifematic-Mod: ", NamedTextColor.GOLD)
                .append(Component.text(link, NamedTextColor.AQUA, TextDecoration.UNDERLINED).clickEvent(ClickEvent.openUrl(link))));
    }

    // ------------------------------------------------------------------ Bereiche

    private Blueprint areaAt(Location loc) {
        String w = loc.getWorld().getName();
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        Blueprint best = null;
        for (Blueprint bp : store.blueprints.values()) {
            if (bp.contains(w, x, y, z)) return bp;
            if (best == null && bp.near(w, x, y, z, AREA_MARGIN)) best = bp;
        }
        return best;
    }

    private Blueprint strictAreaAt(Location loc) {
        String w = loc.getWorld().getName();
        for (Blueprint bp : store.blueprints.values()) {
            if (bp.contains(w, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) return bp;
        }
        return null;
    }

    private void checkArea(Player p, Location to) {
        Blueprint bp = areaAt(to);
        String now = bp == null ? null : bp.id;
        String before = currentArea.get(p.getUniqueId());
        if (Objects.equals(now, before)) return;
        if (now == null) {
            currentArea.remove(p.getUniqueId());
            BossBar bar = bars.remove(p.getUniqueId());
            if (bar != null) p.hideBossBar(bar);
            return;
        }
        currentArea.put(p.getUniqueId(), now);
        onEnter(p, bp);
    }

    private void onEnter(Player p, Blueprint bp) {
        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(barTitle(bp), barProgress(bp), BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);
            bars.put(p.getUniqueId(), bar);
            p.showBossBar(bar);
        } else {
            bar.name(barTitle(bp));
            bar.progress(barProgress(bp));
        }

        Map<String, Integer> l = loaded.get(p.getUniqueId());
        boolean hasLoaded = l != null && Objects.equals(l.get(bp.id), bp.version);
        if (hasLoaded) return;

        if (!hasMod(p)) {
            if (toldAboutMod.add(p.getUniqueId())) {
                p.sendMessage(header(bp));
                tellAboutMod(p);
            }
            return;
        }
        if (isAnswered(p, bp)) return;

        p.sendMessage(header(bp));
        Component load = Component.text("[Bauplan laden]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/lifematic laden " + bp.id))
                .hoverEvent(HoverEvent.showText(Component.text("Zeigt die Geisterbloecke in deinem Litematica")));
        Component no = Component.text("[Nein danke]", NamedTextColor.GRAY)
                .clickEvent(ClickEvent.runCommand("/lifematic nein " + bp.id))
                .hoverEvent(HoverEvent.showText(Component.text("Nicht mehr fragen, bis der Bauplan geaendert wird")));
        p.sendMessage(Component.text("  ").append(load).append(Component.text("  ")).append(no));
    }

    private Component header(Blueprint bp) {
        int pct = bp.percent();
        Component c = Component.text("Hier entsteht ", NamedTextColor.GOLD)
                .append(Component.text(bp.name, NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(" von " + bp.ownerName, NamedTextColor.GOLD));
        if (pct >= 0) c = c.append(Component.text(" · " + pct + " % fertig", NamedTextColor.WHITE));
        c = c.append(bp.helpersAllowed
                ? Component.text(" · Helfer willkommen", NamedTextColor.GREEN)
                : Component.text(" · nur mit Einladung", NamedTextColor.RED));
        return c;
    }

    private Component barTitle(Blueprint bp) {
        int pct = bp.percent();
        String p = pct >= 0 ? pct + " %" : "wird berechnet";
        return Component.text(bp.name + " · " + bp.ownerName + " · " + p + " · "
                + (bp.helpersAllowed ? "Helfer willkommen" : "nur mit Einladung"));
    }

    private float barProgress(Blueprint bp) {
        int pct = bp.percent();
        return pct < 0 ? 0f : Math.min(1f, pct / 100f);
    }

    private void refreshBars() {
        for (Map.Entry<UUID, BossBar> e : bars.entrySet()) {
            String id = currentArea.get(e.getKey());
            Blueprint bp = id == null ? null : store.blueprints.get(id);
            if (bp == null) continue;
            e.getValue().name(barTitle(bp));
            e.getValue().progress(barProgress(bp));
            e.getValue().color(bp.helpersAllowed ? BossBar.Color.GREEN : BossBar.Color.RED);
        }
    }

    private boolean isAnswered(Player p, Blueprint bp) {
        Set<String> s = store.answered.get(p.getUniqueId());
        return s != null && s.contains(bp.id + ":" + bp.version);
    }

    private void markAnswered(Player p, Blueprint bp) {
        Set<String> s = store.answered.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        s.removeIf(e -> e.startsWith(bp.id + ":"));
        s.add(bp.id + ":" + bp.version);
        saveStore();
    }

    // ------------------------------------------------------------------ Fortschritt

    private void progressTick() {
        int interval = Math.max(2, getConfig().getInt("fortschritt-intervall-sekunden", 20));
        for (Blueprint bp : store.blueprints.values()) {
            ExpectedBlocks eb = bp.expected;
            BitSet bits = correctBits.get(bp.id);
            Material[] mats = materialCache.get(bp.id);
            if (eb == null || bits == null || mats == null || eb.size() == 0) continue;
            World w = Bukkit.getWorld(bp.world);
            if (w == null) continue;
            int budget = Math.max(200, eb.size() / (interval * 20) + 1);
            int i = cursor.getOrDefault(bp.id, 0);
            for (int n = 0; n < budget; n++) {
                if (i >= eb.size()) {
                    i = 0;
                    bp.correct = bits.cardinality();
                }
                int x = eb.xs[i], y = eb.ys[i], z = eb.zs[i];
                if (w.isChunkLoaded(x >> 4, z >> 4)) {
                    Material want = mats[eb.type[i]];
                    Material have = w.getBlockAt(x, y, z).getType();
                    bits.set(i, want != null && have == want);
                }
                i++;
            }
            cursor.put(bp.id, i);
            if (bp.correct < 0 && i >= eb.size()) bp.correct = bits.cardinality();
        }
    }

    /** Fehlende Bloecke pro Typ (nur geladene Chunks werden erfasst). */
    private List<Map.Entry<String, Integer>> missing(Blueprint bp) {
        ExpectedBlocks eb = bp.expected;
        BitSet bits = correctBits.get(bp.id);
        Map<String, Integer> m = new HashMap<>();
        if (eb == null || bits == null) return new ArrayList<>();
        for (int i = bits.nextClearBit(0); i < eb.size(); i = bits.nextClearBit(i + 1)) {
            m.merge(eb.typeNames[eb.type[i]], 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(m.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        return list;
    }

    // ------------------------------------------------------------------ Ereignisse

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        getServer().getScheduler().runTaskLater(this, () -> {
            if (e.getPlayer().isOnline()) checkArea(e.getPlayer(), e.getPlayer().getLocation());
        }, 60L);
    }

    /** Die Mod meldet ihren Kanal an, sobald der Spieler verbunden ist. */
    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent e) {
        if (!Protocol.CHANNEL.equals(e.getChannel())) return;
        Player p = e.getPlayer();
        if (!modPlayers.add(p.getUniqueId())) return;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(Protocol.S_HELLO);
            out.writeInt(Protocol.VERSION);
            send(p, bos);
        } catch (IOException ignored) {}
        currentArea.remove(p.getUniqueId());
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) checkArea(p, p.getLocation());
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        uploads.remove(u);
        loaded.remove(u);
        modPlayers.remove(u);
        currentArea.remove(u);
        bars.remove(u);
        toldAboutMod.remove(u);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        checkArea(e.getPlayer(), e.getTo());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        checkArea(e.getPlayer(), e.getTo());
    }

    /** Schleichen + F im Bereich: Infos anzeigen (geht auch ohne Mod). */
    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!e.getPlayer().isSneaking()) return;
        Blueprint bp = areaAt(e.getPlayer().getLocation());
        if (bp == null) return;
        e.setCancelled(true);
        showInfo(e.getPlayer(), bp);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (blockedHere(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (blockedHere(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    private boolean blockedHere(Player p, Block b) {
        if (!getConfig().getBoolean("schutz-wenn-helfer-aus", true)) return false;
        if (p.hasPermission("lifematic.admin")) return false;
        Blueprint bp = strictAreaAt(b.getLocation());
        if (bp == null || bp.mayBuild(p.getUniqueId())) return false;
        p.sendActionBar(Component.text("Hier baut " + bp.ownerName + " ohne Helfer. Frag nach einer Einladung.", NamedTextColor.RED));
        return true;
    }

    // ------------------------------------------------------------------ Befehle

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "hilfe" : args[0].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player pl ? pl : null;
        switch (sub) {
            case "liste", "list" -> {
                if (store.blueprints.isEmpty()) {
                    sender.sendMessage(Component.text("Noch keine Bauplaene geteilt.", NamedTextColor.GRAY));
                    return true;
                }
                sender.sendMessage(Component.text("Geteilte Bauplaene:", NamedTextColor.GOLD));
                for (Blueprint bp : store.blueprints.values()) {
                    int pct = bp.percent();
                    sender.sendMessage(Component.text(" " + bp.id + "  ", NamedTextColor.GRAY)
                            .append(Component.text(bp.name, NamedTextColor.YELLOW))
                            .append(Component.text(" von " + bp.ownerName + " · " + (pct >= 0 ? pct + " %" : "?")
                                    + " · " + bp.world + " " + bp.minX + " " + bp.minY + " " + bp.minZ, NamedTextColor.GRAY)));
                }
            }
            case "info" -> {
                Blueprint bp = target(sender, args, 1);
                if (bp != null) showInfo(sender, bp);
            }
            case "laden", "load" -> {
                if (p == null) return true;
                Blueprint bp = target(sender, args, 1);
                if (bp == null) return true;
                if (!p.getWorld().getName().equals(bp.world)) {
                    p.sendMessage(Component.text("Dieser Bauplan steht in der Welt " + bp.world + ".", NamedTextColor.RED));
                    return true;
                }
                sendBlueprint(p, bp);
                if (hasMod(p)) p.sendMessage(Component.text("Bauplan wird geladen …", NamedTextColor.GREEN));
            }
            case "nein", "no" -> {
                if (p == null) return true;
                Blueprint bp = target(sender, args, 1);
                if (bp == null) return true;
                markAnswered(p, bp);
                p.sendMessage(Component.text("Alles klar, ich frage nicht mehr. Mit /bauplan laden " + bp.id + " geht es jederzeit.", NamedTextColor.GRAY));
            }
            case "entladen", "unload" -> {
                if (p == null) return true;
                Blueprint bp = target(sender, args, 1);
                if (bp == null) return true;
                sendRemove(p, bp.id);
                Map<String, Integer> l = loaded.get(p.getUniqueId());
                if (l != null) l.remove(bp.id);
                p.sendMessage(Component.text("Vorschau entfernt.", NamedTextColor.GRAY));
            }
            case "helfer", "helpers" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("/bauplan helfer an|aus [id]  ·  /bauplan helfer add|entfernen <Spieler> [id]", NamedTextColor.GRAY));
                    return true;
                }
                String mode = args[1].toLowerCase(Locale.ROOT);
                if (mode.equals("an") || mode.equals("aus") || mode.equals("on") || mode.equals("off")) {
                    Blueprint bp = target(sender, args, 2);
                    if (bp == null || !mayManage(sender, bp)) return true;
                    bp.helpersAllowed = mode.equals("an") || mode.equals("on");
                    saveStore();
                    refreshBars();
                    sender.sendMessage(Component.text(bp.name + ": Helfer " + (bp.helpersAllowed ? "willkommen" : "nur mit Einladung"), NamedTextColor.GREEN));
                } else if (mode.equals("add") || mode.equals("entfernen") || mode.equals("remove")) {
                    if (args.length < 3) {
                        sender.sendMessage(Component.text("Spielername fehlt.", NamedTextColor.RED));
                        return true;
                    }
                    Blueprint bp = target(sender, args, 3);
                    if (bp == null || !mayManage(sender, bp)) return true;
                    OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[2]);
                    if (op == null) op = Bukkit.getPlayerExact(args[2]);
                    if (op == null) {
                        sender.sendMessage(Component.text("Spieler " + args[2] + " ist unbekannt (muss schon einmal online gewesen sein).", NamedTextColor.RED));
                        return true;
                    }
                    if (mode.equals("add")) bp.helpers.add(op.getUniqueId());
                    else bp.helpers.remove(op.getUniqueId());
                    saveStore();
                    sender.sendMessage(Component.text(args[2] + (mode.equals("add") ? " darf jetzt mitbauen." : " ist kein Helfer mehr."), NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Unbekannt: " + mode, NamedTextColor.RED));
                }
            }
            case "umbenennen", "rename" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("/bauplan umbenennen <Name>  (im Bereich)", NamedTextColor.GRAY));
                    return true;
                }
                Blueprint bp = p == null ? null : areaAt(p.getLocation());
                if (bp == null) {
                    sender.sendMessage(Component.text("Stell dich in den Bereich des Bauplans.", NamedTextColor.RED));
                    return true;
                }
                if (!mayManage(sender, bp)) return true;
                bp.name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                saveStore();
                sender.sendMessage(Component.text("Neuer Name: " + bp.name, NamedTextColor.GREEN));
            }
            case "loeschen", "löschen", "delete" -> {
                Blueprint bp = target(sender, args, 1);
                if (bp == null || !mayManage(sender, bp)) return true;
                store.blueprints.remove(bp.id);
                //noinspection ResultOfMethodCallIgnored
                store.fileOf(bp).delete();
                correctBits.remove(bp.id);
                cursor.remove(bp.id);
                materialCache.remove(bp.id);
                saveStore();
                for (Player op : Bukkit.getOnlinePlayers()) {
                    Map<String, Integer> l = loaded.get(op.getUniqueId());
                    if (l != null && l.remove(bp.id) != null) sendRemove(op, bp.id);
                    if (bp.id.equals(currentArea.get(op.getUniqueId()))) {
                        currentArea.remove(op.getUniqueId());
                        BossBar bar = bars.remove(op.getUniqueId());
                        if (bar != null) op.hideBossBar(bar);
                    }
                }
                sender.sendMessage(Component.text("Bauplan «" + bp.name + "» geloescht.", NamedTextColor.GREEN));
            }
            default -> {
                sender.sendMessage(Component.text("Lifematic – geteilte Bauplaene", NamedTextColor.GOLD, TextDecoration.BOLD));
                sender.sendMessage(Component.text(" Teilen: in Litematica platzieren, dann Taste B (zweimal)", NamedTextColor.GRAY));
                sender.sendMessage(Component.text(" /bauplan liste · info · laden · entladen · nein", NamedTextColor.GRAY));
                sender.sendMessage(Component.text(" /bauplan helfer an|aus · helfer add|entfernen <Spieler>", NamedTextColor.GRAY));
                sender.sendMessage(Component.text(" /bauplan umbenennen <Name> · loeschen [id]", NamedTextColor.GRAY));
                sender.sendMessage(Component.text(" Im Bereich: Schleichen + F zeigt die Infos", NamedTextColor.GRAY));
            }
        }
        return true;
    }

    private Blueprint target(CommandSender sender, String[] args, int idx) {
        if (args.length > idx) {
            Blueprint bp = store.blueprints.get(args[idx]);
            if (bp == null) sender.sendMessage(Component.text("Kein Bauplan mit der ID " + args[idx] + ".", NamedTextColor.RED));
            return bp;
        }
        if (sender instanceof Player pl) {
            Blueprint bp = areaAt(pl.getLocation());
            if (bp == null) sender.sendMessage(Component.text("Du stehst in keinem Bauplan-Bereich. Gib die ID an (/bauplan liste).", NamedTextColor.RED));
            return bp;
        }
        sender.sendMessage(Component.text("ID angeben.", NamedTextColor.RED));
        return null;
    }

    private boolean mayManage(CommandSender sender, Blueprint bp) {
        if (sender.hasPermission("lifematic.admin")) return true;
        if (sender instanceof Player pl && pl.getUniqueId().equals(bp.owner)) return true;
        sender.sendMessage(Component.text("Nur " + bp.ownerName + " darf das.", NamedTextColor.RED));
        return false;
    }

    private void showInfo(CommandSender s, Blueprint bp) {
        s.sendMessage(Component.text("—— " + bp.name + " ——", NamedTextColor.GOLD, TextDecoration.BOLD));
        s.sendMessage(Component.text("Gestartet von ", NamedTextColor.GRAY).append(Component.text(bp.ownerName, NamedTextColor.WHITE))
                .append(Component.text(" · Version " + bp.version + " · ID " + bp.id, NamedTextColor.GRAY)));
        int pct = bp.percent();
        int total = bp.expected == null ? 0 : bp.expected.size();
        s.sendMessage(Component.text("Fortschritt: ", NamedTextColor.GRAY).append(Component.text(
                (pct >= 0 ? pct + " %" : "wird berechnet") + (total > 0 && bp.correct >= 0 ? " (" + bp.correct + " von " + total + " Bloecken)" : ""), NamedTextColor.WHITE)));
        StringBuilder hn = new StringBuilder();
        for (UUID u : bp.helpers) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            if (hn.length() > 0) hn.append(", ");
            hn.append(op.getName() == null ? u.toString().substring(0, 8) : op.getName());
        }
        s.sendMessage(Component.text("Helfer: ", NamedTextColor.GRAY).append(bp.helpersAllowed
                ? Component.text("alle willkommen", NamedTextColor.GREEN)
                : Component.text("nur eingeladen" + (hn.length() > 0 ? " (" + hn + ")" : ""), NamedTextColor.RED)));
        List<Map.Entry<String, Integer>> miss = missing(bp);
        if (!miss.isEmpty()) {
            s.sendMessage(Component.text("Es fehlen noch:", NamedTextColor.GRAY));
            int shown = 0;
            for (Map.Entry<String, Integer> e : miss) {
                if (shown++ >= 10) break;
                int n = e.getValue();
                String stacks = n >= 64 ? " (" + (n / 64) + " × 64 + " + (n % 64) + ")" : "";
                s.sendMessage(Component.text("  " + n + "× ", NamedTextColor.WHITE)
                        .append(Component.translatable(blockTranslationKey(e.getKey()), NamedTextColor.YELLOW))
                        .append(Component.text(stacks, NamedTextColor.DARK_GRAY)));
            }
            if (miss.size() > 10) s.sendMessage(Component.text("  … und " + (miss.size() - 10) + " weitere Sorten. Volle Liste: Litematica (M) → Materialliste", NamedTextColor.DARK_GRAY));
        }
    }

    /** "minecraft:stone_bricks" -> "block.minecraft.stone_bricks" (zeigt den Namen in der Sprache des Spielers). */
    private static String blockTranslationKey(String id) {
        String ns = "minecraft", path = id;
        int c = id.indexOf(':');
        if (c >= 0) { ns = id.substring(0, c); path = id.substring(c + 1); }
        return "block." + ns + "." + path;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("liste", "info", "laden", "entladen", "nein", "helfer", "umbenennen", "loeschen", "hilfe"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("helfer")) return filter(List.of("an", "aus", "add", "entfernen"), args[1]);
        if (args.length == 2) return filter(new ArrayList<>(store.blueprints.keySet()), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("helfer") && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("entfernen"))) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> opts, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) out.add(o);
        return out;
    }
}
