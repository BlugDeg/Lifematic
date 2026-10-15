package ch.blugdeg.lifematic.client;

import ch.blugdeg.lifematic.client.ghost.GhostManager;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Das Lifematic-Menue (Taste R). Reiter oben, Unterseiten fuer Listen. */
public final class LifematicScreen extends Screen {
    private enum Page { TABS, FILES, SERVER, MATERIALS, PROJECTS }

    /** Reiter der Hauptansicht. Der zuletzt offene bleibt beim naechsten Oeffnen gewaehlt. */
    private enum Tab {
        PLANS("Bauplaene"), PLACEMENT("Platzierung"), DISPLAY("Darstellung"), SETTINGS("Einstellungen");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, GOLD = 0xFFFFAA00, GREEN = 0xFF55FF55, RED = 0xFFFF5555;
    private static final int CONTENT_Y = 74;

    private static Tab lastTab = Tab.PLANS;

    private Page page = Page.TABS;
    private Tab tab = lastTab;
    private int scroll = 0;
    private String status = "";
    private boolean confirmShare = false;
    private String materialsId;
    private final List<Line> lines = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();

    private record Line(String text, int color, ItemStack icon) {
        Line(String text, int color) { this(text, color, null); }
    }

    /** Zentrierter Hinweistext an fester Position. */
    private record Label(String text, int y, int color) {}

    public LifematicScreen() {
        super(Component.literal("Lifematic"));
    }

    public static void refreshIfOpen() {
        if (Minecraft.getInstance().gui.screen() instanceof LifematicScreen s) s.rebuild();
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    private void go(Page p) {
        page = p;
        scroll = 0;
        confirmShare = false;
        status = "";
        if (p == Page.SERVER) LifematicClient.requestList();
        rebuild();
    }

    private void show(Tab t) {
        tab = t;
        lastTab = t;
        confirmShare = false;
        status = "";
        page = Page.TABS;
        rebuild();
    }

    private Button button(String label, int x, int y, int w, Button.OnPress action) {
        return this.addRenderableWidget(Button.builder(Component.literal(label), action).pos(x, y).size(w, 20).build());
    }

    @Override
    protected void init() {
        lines.clear();
        labels.clear();
        switch (page) {
            case TABS -> initTabs();
            case FILES -> initFiles();
            case SERVER -> initServer();
            case MATERIALS -> initMaterials();
            case PROJECTS -> initProjects();
        }
    }

    // ------------------------------------------------------------------ Hauptansicht mit Reitern

    private void initTabs() {
        int cx = this.width / 2;
        SchematicPlacement p = LitematicaBridge.selected();

        if (p == null) {
            lines.add(new Line("Kein Bauplan gewaehlt", GRAY));
        } else {
            String recv = LitematicaBridge.receivedId(p);
            String linked = LitematicaBridge.linkedId(p);
            String state = linked != null ? "geteilt" : recv != null ? "vom Server" : "nur bei dir";
            if (p.isLocked()) state += "  ·  verriegelt";
            BlockPos o = p.getOrigin();
            lines.add(new Line(p.getName() + "  ·  " + state, GOLD));
            lines.add(new Line("Position " + o.getX() + " / " + o.getY() + " / " + o.getZ()
                    + "  ·  Drehung " + rotText(p) + "  ·  Spiegelung " + mirrorText(p), GRAY));
        }

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            button(t.label, cx - 155 + i * 79, 44, 74, b -> show(t)).active = t != tab;
        }

        switch (tab) {
            case PLANS -> initPlans(cx, p);
            case PLACEMENT -> initPlacement(cx, p);
            case DISPLAY -> initDisplay(cx);
            case SETTINGS -> initSettings(cx);
        }

        button("Schliessen", cx - 50, this.height - 28, 100, b -> this.onClose());

        if (!LifematicClient.serverHasPlugin && status.isEmpty()) {
            status = "Keine Verbindung zum Lifematic-Plugin – Teilen nicht moeglich";
            LifematicClient.sendHello();
        }
    }

    private void initPlans(int cx, SchematicPlacement p) {
        int y = CONTENT_Y;
        button("Bauplan hinzufuegen …", cx - 155, y, 100, b -> go(Page.FILES));
        button("Aktivierte Projekte (" + LitematicaBridge.all().size() + ")", cx - 50, y, 100, b -> go(Page.PROJECTS))
                .active = !LitematicaBridge.all().isEmpty();
        button("Server-Bauplaene", cx + 55, y, 100, b -> go(Page.SERVER));
        y += 32;

        labels.add(new Label("Gewaehlter Bauplan", y - 2, GRAY));
        y += 10;
        boolean shareable = p != null && (LitematicaBridge.receivedId(p) == null || LitematicaBridge.linkedId(p) != null);
        String shareLabel = confirmShare ? "Wirklich teilen?" : (p != null && LitematicaBridge.linkedId(p) != null ? "Aktualisieren" : "Mit allen teilen");
        button(shareLabel, cx - 105, y, 100, b -> {
            SchematicPlacement sp = LitematicaBridge.selected();
            if (sp == null) return;
            if (!confirmShare) {
                confirmShare = true;
            } else {
                confirmShare = false;
                status = LifematicClient.share(sp);
            }
            rebuild();
        }).active = shareable && LifematicClient.serverHasPlugin && LifematicClient.uploading == null;
        String matId = p == null ? null : (LitematicaBridge.linkedId(p) != null ? LitematicaBridge.linkedId(p) : LitematicaBridge.receivedId(p));
        button("Materialliste", cx + 5, y, 100, b -> {
            materialsId = matId;
            LifematicClient.requestMaterials(matId);
            go(Page.MATERIALS);
        }).active = matId != null && LifematicClient.serverHasPlugin;
    }

    private void initPlacement(int cx, SchematicPlacement p) {
        int y = CONTENT_Y;
        boolean edit = LitematicaBridge.canEdit(p);
        String[] dirLabels = {"X −", "X +", "Y −", "Y +", "Z −", "Z +"};
        int[][] d = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        for (int i = 0; i < 6; i++) {
            final int[] dd = d[i];
            button(dirLabels[i], cx - 155 + i * 52, y, 48, b -> {
                int f = shift() ? 10 : 1;
                LitematicaBridge.move(LitematicaBridge.selected(), dd[0] * f, dd[1] * f, dd[2] * f);
                rebuild();
            }).active = edit;
        }
        y += 24;
        button("Hierher setzen", cx - 155, y, 100, b -> {
            LitematicaBridge.moveToPlayer(LitematicaBridge.selected());
            rebuild();
        }).active = edit;
        button("Drehen ↻", cx - 50, y, 100, b -> {
            LitematicaBridge.rotate(LitematicaBridge.selected());
            rebuild();
        }).active = edit;
        button("Spiegeln", cx + 55, y, 100, b -> {
            LitematicaBridge.mirror(LitematicaBridge.selected());
            rebuild();
        }).active = edit;
        y += 24;
        labels.add(new Label("Shift + Klick = 10 Bloecke", y + 2, GRAY));
        y += 18;

        boolean foreign = p != null && LitematicaBridge.isForeign(p);
        String lockLabel = p == null ? "Verriegeln" : foreign ? "Verriegelt (fremder Bauplan)" : p.isLocked() ? "Entriegeln" : "Verriegeln";
        button(lockLabel, cx - 155, y, 150, b -> {
            LitematicaBridge.toggleLock(LitematicaBridge.selected());
            rebuild();
        }).active = p != null && !foreign;
        button("Entfernen", cx + 5, y, 150, b -> {
            SchematicPlacement sp = LitematicaBridge.selected();
            String rid = sp == null ? null : LitematicaBridge.receivedId(sp);
            if (rid != null) LitematicaBridge.removeReceived(rid);
            else LitematicaBridge.remove(sp);
            status = "Vorschau entfernt (auf dem Server bleibt der Bauplan).";
            rebuild();
        }).active = LitematicaBridge.canRemove(p);
        if (p != null && p.isLocked() && !foreign) {
            labels.add(new Label("Verriegelt: verschieben, drehen, spiegeln und entfernen gesperrt", y + 28, GRAY));
        }
    }

    private void initDisplay(int cx) {
        int y = CONTENT_Y;
        labels.add(new Label("Anzeige: " + LitematicaBridge.layerText(), y - 10, GRAY));
        button("Vorschau: " + (LitematicaBridge.renderingOn() ? "an" : "aus"), cx - 155, y, 100, b -> {
            LitematicaBridge.toggleRendering();
            rebuild();
        });
        button(LitematicaBridge.singleLayer() ? "Alle Ebenen" : "Nur eine Ebene", cx - 50, y, 100, b -> {
            LitematicaBridge.toggleSingleLayer();
            rebuild();
        });
        button("Ebene folgt mir: " + (LitematicaBridge.followPlayer() ? "an" : "aus"), cx + 55, y, 100, b -> {
            LitematicaBridge.setFollowPlayer(!LitematicaBridge.followPlayer());
            rebuild();
        });
        y += 24;
        button("Ebene ▼", cx - 155, y, 100, b -> {
            LitematicaBridge.moveLayer(shift() ? -10 : -1);
            rebuild();
        });
        button("Ebene ▲", cx - 50, y, 100, b -> {
            LitematicaBridge.moveLayer(shift() ? 10 : 1);
            rebuild();
        });
        y += 30;
        labels.add(new Label("Shift + Klick = 10 Ebenen", y, GRAY));
        labels.add(new Label("Tasten: Shift + R = Vorschau an/aus  ·  Shift + Numpad + / − = Ebene", y + 12, GRAY));
    }

    private void initSettings(int cx) {
        int y = CONTENT_Y;
        String mode = !GhostManager.available() ? "Darstellung: Litematica (Sodium fehlt)"
                : GhostManager.preferred() ? "Darstellung: shaderfest" : "Darstellung: Litematica";
        button(mode, cx - 155, y, 310, b -> {
            GhostManager.setPreferred(!GhostManager.preferred());
            status = GhostManager.preferred()
                    ? "Geisterbloecke laufen jetzt ueber Sodium und vertragen Shader."
                    : "Litematica zeichnet die Vorschau wieder selbst.";
            rebuild();
        }).active = GhostManager.available();
        labels.add(new Label("shaderfest: halbtransparente Geister ueber Sodium, falsche Bloecke eingefaerbt", y + 28, GRAY));
        labels.add(new Label("Litematica: klassische Darstellung (ohne Shader empfohlen)", y + 40, GRAY));
    }

    /** Shift gedrueckt? Dann 10 statt 1 Block (wie in Litematica). */
    private static boolean shift() {
        return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
    }

    private static String rotText(SchematicPlacement p) {
        return switch (p.getRotation()) {
            case CLOCKWISE_90 -> "90°";
            case CLOCKWISE_180 -> "180°";
            case COUNTERCLOCKWISE_90 -> "270°";
            default -> "0°";
        };
    }

    private static String mirrorText(SchematicPlacement p) {
        return switch (p.getMirror()) {
            case LEFT_RIGHT -> "Nord-Sued";
            case FRONT_BACK -> "Ost-West";
            default -> "keine";
        };
    }

    // ------------------------------------------------------------------ Projekte

    private static String idOf(SchematicPlacement p) {
        String l = LitematicaBridge.linkedId(p);
        return l != null ? l : LitematicaBridge.receivedId(p);
    }

    private void initProjects() {
        int cx = this.width / 2;
        List<SchematicPlacement> list = LitematicaBridge.all();
        SchematicPlacement sel = LitematicaBridge.selected();
        lines.add(new Line("Aktivierte Projekte – auswaehlen, ein-/ausblenden, Materialliste", GOLD));
        if (list.isEmpty()) lines.add(new Line("Noch kein Bauplan geladen.", GRAY));
        int perPage = Math.max(3, (this.height - 110) / 24);
        int y = 40;
        for (int i = scroll; i < list.size() && i < scroll + perPage; i++) {
            SchematicPlacement p = list.get(i);
            String name = (p == sel ? "▶ " : "") + p.getName();
            if (name.length() > 28) name = name.substring(0, 28) + "…";
            button(name, cx - 155, y, 170, b -> {
                LitematicaBridge.select(p);
                rebuild();
            });
            button(p.isEnabled() ? "Sichtbar" : "Versteckt", cx + 19, y, 66, b -> {
                p.setEnabled(!p.isEnabled());
                rebuild();
            });
            String id = idOf(p);
            button("Material", cx + 89, y, 66, b -> {
                LitematicaBridge.select(p);
                materialsId = id;
                LifematicClient.requestMaterials(id);
                go(Page.MATERIALS);
            }).active = id != null && LifematicClient.serverHasPlugin;
            y += 24;
        }
        if (list.stream().anyMatch(p -> idOf(p) == null)) {
            status = "Materialliste gibt es fuer geteilte Bauplaene.";
        }
        pager(list.size(), perPage);
    }

    // ------------------------------------------------------------------ Dateien

    private void initFiles() {
        int cx = this.width / 2;
        List<Path> files = LitematicaBridge.localFiles();
        lines.add(new Line("Bauplan hinzufuegen (Ordner .minecraft/schematics)", GOLD));
        button("Ordner oeffnen", cx + 55, 2, 100, b -> LitematicaBridge.openSchematicsFolder());
        if (files.isEmpty()) lines.add(new Line("Keine .litematic-Dateien gefunden.", GRAY));
        int perPage = Math.max(3, (this.height - 110) / 24);
        int y = 40;
        for (int i = scroll; i < files.size() && i < scroll + perPage; i++) {
            Path f = files.get(i);
            String name = f.getFileName().toString().replace(".litematic", "");
            button(name.length() > 40 ? name.substring(0, 40) + "…" : name, cx - 155, y, 310, b -> {
                String msg = LitematicaBridge.open(f);
                go(Page.TABS);
                status = msg;
            });
            y += 24;
        }
        pager(files.size(), perPage);
    }

    // ------------------------------------------------------------------ Server

    private void initServer() {
        int cx = this.width / 2;
        lines.add(new Line("Bauplaene auf dem Server", GOLD));
        List<LifematicClient.ServerEntry> list = LifematicClient.serverList;
        if (list.isEmpty()) lines.add(new Line(LifematicClient.serverHasPlugin ? "Noch keine geteilten Bauplaene." : "Server ohne Lifematic-Plugin.", GRAY));
        Map<String, Integer> have = LitematicaBridge.receivedVersions();
        int perPage = Math.max(3, (this.height - 110) / 24);
        int y = 40;
        for (int i = scroll; i < list.size() && i < scroll + perPage; i++) {
            LifematicClient.ServerEntry e = list.get(i);
            String pct = e.percent() >= 0 ? e.percent() + " %" : "?";
            String label = e.name() + " · " + e.owner() + " · " + pct + (e.helpersAllowed() ? "" : " · privat");
            button(label.length() > 46 ? label.substring(0, 46) + "…" : label, cx - 155, y, 230, b -> {
                LifematicClient.requestBlueprint(e.id());
                status = "«" + e.name() + "» wird geladen …";
            }).active = !e.mine() || LitematicaBridge.all().stream().noneMatch(p -> e.id().equals(LitematicaBridge.linkedId(p)));
            boolean loaded = have.containsKey(e.id());
            button(loaded ? "Entfernen" : "", cx + 80, y, 75, b -> {
                LitematicaBridge.removeReceived(e.id());
                rebuild();
            }).active = loaded;
            y += 24;
        }
        pager(list.size(), perPage);
    }

    // ------------------------------------------------------------------ Materialliste

    private void initMaterials() {
        LifematicClient.Materials m = materialsId == null ? null : LifematicClient.materials.get(materialsId);
        String projName = materialsId;
        for (SchematicPlacement sp : LitematicaBridge.all()) if (materialsId != null && materialsId.equals(idOf(sp))) projName = sp.getName();
        lines.add(new Line("Materialliste: " + projName, GOLD));
        if (m == null) {
            lines.add(new Line("Wird vom Server geladen …", GRAY));
        } else {
            if (m.correct() >= 0 && m.total() > 0) {
                lines.add(new Line(m.correct() + " von " + m.total() + " Bloecken gesetzt ("
                        + (100 * m.correct() / m.total()) + " %)", WHITE));
            } else {
                lines.add(new Line("Fortschritt wird noch berechnet (Bereich muss geladen sein).", GRAY));
            }
            Map<String, Integer> inv = inventoryCounts();
            int perPage = Math.max(3, (this.height - 120) / 18);
            List<Map.Entry<String, Integer>> miss = m.missing();
            for (int i = scroll; i < miss.size() && i < scroll + perPage; i++) {
                String id = miss.get(i).getKey();
                int need = miss.get(i).getValue();
                int have = inv.getOrDefault(itemIdFor(id), 0);
                String stacks = need >= 64 ? "  (" + need / 64 + " × 64 + " + need % 64 + ")" : "";
                lines.add(new Line(need + "×  " + blockName(id) + stacks + "   ·  im Inventar: " + have,
                        have >= need ? GREEN : WHITE, iconFor(id)));
            }
            if (miss.isEmpty()) lines.add(new Line("Nichts fehlt mehr. Fertig!", GREEN));
            pager(miss.size(), perPage);
        }
        int cx = this.width / 2;
        button("Aktualisieren", cx - 50, this.height - 28, 100, b -> {
            LifematicClient.requestMaterials(materialsId);
        });
        button("Projekt wechseln", cx + 55, 2, 100, b -> go(Page.PROJECTS));
    }

    private static String itemIdFor(String blockId) {
        // Die meisten Bloecke haben ein gleichnamiges Item; Sonderfaelle grob abdecken
        return switch (blockId) {
            case "minecraft:water" -> "minecraft:water_bucket";
            case "minecraft:wall_torch" -> "minecraft:torch";
            default -> blockId;
        };
    }

    /** Item-Symbol fuer einen Block (leer, wenn es kein passendes Item gibt). */
    private static ItemStack iconFor(String blockId) {
        try {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemIdFor(blockId)));
            return item == Items.AIR ? null : new ItemStack(item);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String blockName(String id) {
        String ns = "minecraft", path = id;
        int c = id.indexOf(':');
        if (c >= 0) {
            ns = id.substring(0, c);
            path = id.substring(c + 1);
        }
        return Component.translatable("block." + ns + "." + path).getString();
    }

    private static Map<String, Integer> inventoryCounts() {
        Map<String, Integer> m = new HashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return m;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            m.merge(id, st.getCount(), Integer::sum);
        }
        return m;
    }

    // ------------------------------------------------------------------ gemeinsam

    private void pager(int total, int perPage) {
        int cx = this.width / 2;
        int y = this.height - 28;
        button("◀ Zurueck", cx - 155, y, 100, b -> go(Page.TABS));
        button("▲", cx + 55, y, 48, b -> {
            scroll = Math.max(0, scroll - perPage);
            rebuild();
        }).active = scroll > 0;
        button("▼", cx + 107, y, 48, b -> {
            scroll = Math.min(Math.max(0, total - 1), scroll + perPage);
            rebuild();
        }).active = scroll + perPage < total;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int cx = this.width / 2;
        graphics.centeredText(this.font, "Lifematic", cx, 8, GOLD);
        if (page == Page.TABS || page == Page.FILES || page == Page.SERVER || page == Page.PROJECTS) {
            int y = 20;
            for (Line l : lines) {
                if (page == Page.TABS) {
                    graphics.centeredText(this.font, l.text(), cx, y, l.color());
                    y += 10;
                } else {
                    graphics.centeredText(this.font, l.text(), cx, 22, l.color());
                    break;
                }
            }
            if (page == Page.TABS) {
                for (Label l : labels) graphics.centeredText(this.font, l.text(), cx, l.y(), l.color());
            }
        } else {
            int y = 22;
            for (Line l : lines) {
                if (l.icon() != null) {
                    graphics.item(l.icon(), cx - 155, y);
                    graphics.text(this.font, l.text(), cx - 133, y + 4, l.color());
                    y += 18;
                } else {
                    graphics.text(this.font, l.text(), cx - 155, y, l.color());
                    y += 11;
                }
            }
        }
        if (!status.isEmpty()) graphics.centeredText(this.font, status, cx, this.height - 40, WHITE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
