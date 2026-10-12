package ch.blugdeg.lifematic.client;

import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import org.lwjgl.glfw.GLFW;
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

/** Das Lifematic-Menue (Taste R). Eine Klasse mit mehreren Seiten. */
public final class LifematicScreen extends Screen {
    private enum Page { MAIN, FILES, SERVER, MATERIALS, PROJECTS }

    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, GOLD = 0xFFFFAA00, GREEN = 0xFF55FF55, RED = 0xFFFF5555;

    private Page page = Page.MAIN;
    private int scroll = 0;
    private String status = "";
    private boolean confirmShare = false;
    private String materialsId;
    private final List<Line> lines = new ArrayList<>();

    private record Line(String text, int color, ItemStack icon) {
        Line(String text, int color) { this(text, color, null); }
    }

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
        if (p == Page.SERVER) LifematicClient.requestList();
        rebuild();
    }

    private Button button(String label, int x, int y, int w, Button.OnPress action) {
        return this.addRenderableWidget(Button.builder(Component.literal(label), action).pos(x, y).size(w, 20).build());
    }

    @Override
    protected void init() {
        lines.clear();
        switch (page) {
            case MAIN -> initMain();
            case FILES -> initFiles();
            case SERVER -> initServer();
            case MATERIALS -> initMaterials();
            case PROJECTS -> initProjects();
        }
    }

    // ------------------------------------------------------------------ Hauptseite

    private void initMain() {
        int cx = this.width / 2;
        int y = 40;
        SchematicPlacement p = LitematicaBridge.selected();

        if (p == null) {
            lines.add(new Line("Kein Bauplan gewaehlt", GRAY));
        } else {
            String recv = LitematicaBridge.receivedId(p);
            String linked = LitematicaBridge.linkedId(p);
            String state = linked != null ? "geteilt" : recv != null ? "vom Server" : "nur bei dir";
            BlockPos o = p.getOrigin();
            lines.add(new Line(p.getName() + "  ·  " + state, GOLD));
            lines.add(new Line("Position " + o.getX() + " / " + o.getY() + " / " + o.getZ()
                    + "  ·  Drehung " + rotText(p) + "  ·  Spiegelung " + mirrorText(p), GRAY));
        }

        // Zeile 1: Baupläne
        button("Bauplan oeffnen …", cx - 155, y, 100, b -> go(Page.FILES));
        button("Meine Projekte (" + LitematicaBridge.all().size() + ")", cx - 50, y, 100, b -> go(Page.PROJECTS))
                .active = !LitematicaBridge.all().isEmpty();
        button("Server-Bauplaene", cx + 55, y, 100, b -> go(Page.SERVER));
        y += 28;

        boolean edit = LitematicaBridge.canEdit(p);
        // Zeile 2: verschieben
        String[] labels = {"X −", "X +", "Y −", "Y +", "Z −", "Z +"};
        int[][] d = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        for (int i = 0; i < 6; i++) {
            final int[] dd = d[i];
            button(labels[i], cx - 155 + i * 52, y, 48, b -> {
                int f = shift() ? 10 : 1;
                LitematicaBridge.move(LitematicaBridge.selected(), dd[0] * f, dd[1] * f, dd[2] * f);
                rebuild();
            }).active = edit;
        }
        y += 24;
        // Zeile 3: hierher, drehen, spiegeln
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
        y += 28;

        // Zeile 4: teilen, Material, entfernen
        boolean shareable = p != null && (LitematicaBridge.receivedId(p) == null || LitematicaBridge.linkedId(p) != null);
        String shareLabel = confirmShare ? "Wirklich teilen?" : (p != null && LitematicaBridge.linkedId(p) != null ? "Aktualisieren" : "Mit allen teilen");
        button(shareLabel, cx - 155, y, 100, b -> {
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
        button("Materialliste", cx - 50, y, 100, b -> {
            materialsId = matId;
            LifematicClient.requestMaterials(matId);
            go(Page.MATERIALS);
        }).active = matId != null && LifematicClient.serverHasPlugin;
        button("Entfernen", cx + 55, y, 100, b -> {
            SchematicPlacement sp = LitematicaBridge.selected();
            String rid = sp == null ? null : LitematicaBridge.receivedId(sp);
            if (rid != null) LitematicaBridge.removeReceived(rid);
            else LitematicaBridge.remove(sp);
            status = "Vorschau entfernt (auf dem Server bleibt der Bauplan).";
            rebuild();
        }).active = p != null;
        y += 36;

        // Zeile 5: Anzeige
        button("Vorschau: " + (LitematicaBridge.renderingOn() ? "an" : "aus"), cx - 155, y, 100, b -> {
            LitematicaBridge.toggleRendering();
            rebuild();
        });
        button(LitematicaBridge.singleLayer() ? "Alle Ebenen" : "Nur eine Ebene", cx - 50, y, 100, b -> {
            LitematicaBridge.toggleSingleLayer();
            rebuild();
        });
        button("Ebene = meine Hoehe", cx + 55, y, 100, b -> {
            LitematicaBridge.layerToPlayer();
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
        button("Schliessen", cx + 55, y, 100, b -> this.onClose());

        if (!LifematicClient.serverHasPlugin && status.isEmpty()) {
            status = "Keine Verbindung zum Lifematic-Plugin – Teilen nicht moeglich";
            LifematicClient.sendHello();
        }
    }

    /** Shift gedrueckt? Dann 10 statt 1 Block (wie in Litematica). */
    private static boolean shift() {
        var w = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT_SHIFT) || InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT_SHIFT);
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
        lines.add(new Line("Meine Projekte – auswaehlen, ein-/ausblenden, Materialliste", GOLD));
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
        lines.add(new Line("Bauplan oeffnen (Ordner .minecraft/schematics)", GOLD));
        if (files.isEmpty()) lines.add(new Line("Keine .litematic-Dateien gefunden.", GRAY));
        int perPage = Math.max(3, (this.height - 110) / 24);
        int y = 40;
        for (int i = scroll; i < files.size() && i < scroll + perPage; i++) {
            Path f = files.get(i);
            String name = f.getFileName().toString().replace(".litematic", "");
            button(name.length() > 40 ? name.substring(0, 40) + "…" : name, cx - 155, y, 310, b -> {
                status = LitematicaBridge.open(f);
                go(Page.MAIN);
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
        button("◀ Zurueck", cx - 155, y, 100, b -> go(Page.MAIN));
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
        if (page == Page.MAIN || page == Page.FILES || page == Page.SERVER || page == Page.PROJECTS) {
            int y = 20;
            for (Line l : lines) {
                if (page == Page.MAIN) {
                    graphics.centeredText(this.font, l.text(), cx, y, l.color());
                    y += 10;
                } else {
                    graphics.centeredText(this.font, l.text(), cx, 22, l.color());
                    break;
                }
            }
            if (page == Page.MAIN) {
                graphics.centeredText(this.font, "Anzeige: " + LitematicaBridge.layerText(), cx, 145, GRAY);
                graphics.centeredText(this.font, "Shift + Klick = 10 Bloecke / 10 Ebenen", cx, 206, GRAY);
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
