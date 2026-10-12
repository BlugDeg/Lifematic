package ch.blugdeg.lifematic;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Alle Soll-Bloecke eines Bauplans in Weltkoordinaten (nach Drehen/Spiegeln).
 * Luft wird ausgelassen. Verglichen wird spaeter nur der Blocktyp, nicht der Zustand.
 */
public final class ExpectedBlocks {
    public final int[] xs, ys, zs;
    public final int[] type;          // Index in typeNames
    public final String[] typeNames;  // z. B. "minecraft:stone_bricks"
    public int minX, minY, minZ, maxX, maxY, maxZ;

    private ExpectedBlocks(int[] xs, int[] ys, int[] zs, int[] type, String[] typeNames) {
        this.xs = xs; this.ys = ys; this.zs = zs; this.type = type; this.typeNames = typeNames;
    }

    public int size() { return xs.length; }

    /** Transformation wie in Minecraft/Litematica: erst spiegeln, dann drehen. */
    static int[] transform(int x, int z, String mirror, String rotation) {
        switch (mirror) {
            case "LEFT_RIGHT": z = -z; break;
            case "FRONT_BACK": x = -x; break;
            default: break;
        }
        switch (rotation) {
            case "CLOCKWISE_90": return new int[]{-z, x};
            case "CLOCKWISE_180": return new int[]{-x, -z};
            case "COUNTERCLOCKWISE_90": return new int[]{z, -x};
            default: return new int[]{x, z};
        }
    }

    @SuppressWarnings("unchecked")
    public static ExpectedBlocks load(InputStream file, int ox, int oy, int oz, String rotation, String mirror) throws IOException {
        Map<String, Object> root = NbtReader.readGzip(file);
        Object regionsObj = root.get("Regions");
        if (!(regionsObj instanceof Map)) throw new IOException("Keine Regions im Bauplan");
        Map<String, Object> regions = (Map<String, Object>) regionsObj;

        Map<String, Integer> typeIndex = new HashMap<>();
        List<String> typeNames = new ArrayList<>();
        IntList xs = new IntList(), ys = new IntList(), zs = new IntList(), ts = new IntList();

        for (Object regObj : regions.values()) {
            Map<String, Object> reg = (Map<String, Object>) regObj;
            Map<String, Object> pos = (Map<String, Object>) reg.get("Position");
            Map<String, Object> size = (Map<String, Object>) reg.get("Size");
            List<Object> palette = (List<Object>) reg.get("BlockStatePalette");
            long[] states = (long[]) reg.get("BlockStates");
            if (pos == null || size == null || palette == null || states == null) continue;

            int px = (Integer) pos.get("x"), py = (Integer) pos.get("y"), pz = (Integer) pos.get("z");
            int sx = (Integer) size.get("x"), sy = (Integer) size.get("y"), sz = (Integer) size.get("z");
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            int bx = px + (sx < 0 ? sx + 1 : 0);
            int by = py + (sy < 0 ? sy + 1 : 0);
            int bz = pz + (sz < 0 ? sz + 1 : 0);

            int[] palToType = new int[palette.size()];
            for (int i = 0; i < palette.size(); i++) {
                Map<String, Object> entry = (Map<String, Object>) palette.get(i);
                String name = String.valueOf(entry.get("Name"));
                if (name.equals("minecraft:air") || name.equals("minecraft:cave_air")
                        || name.equals("minecraft:void_air") || name.equals("minecraft:structure_void")) {
                    palToType[i] = -1;
                    continue;
                }
                Integer idx = typeIndex.get(name);
                if (idx == null) {
                    idx = typeNames.size();
                    typeNames.add(name);
                    typeIndex.put(name, idx);
                }
                palToType[i] = idx;
            }

            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long mask = (1L << bits) - 1L;
            long total = (long) ax * ay * az;
            for (long index = 0; index < total; index++) {
                long startOffset = index * bits;
                int startArr = (int) (startOffset >> 6);
                int endArr = (int) (((index + 1) * bits - 1) >> 6);
                int startBit = (int) (startOffset & 63);
                long value;
                if (startArr >= states.length) break;
                if (startArr == endArr) {
                    value = (states[startArr] >>> startBit) & mask;
                } else {
                    int endOffset = 64 - startBit;
                    long hi = endArr < states.length ? states[endArr] : 0L;
                    value = ((states[startArr] >>> startBit) | (hi << endOffset)) & mask;
                }
                if (value >= palToType.length) continue;
                int t = palToType[(int) value];
                if (t < 0) continue;
                int x = (int) (index % ax);
                int z = (int) ((index / ax) % az);
                int y = (int) (index / ((long) ax * az));
                int[] r = transform(bx + x, bz + z, mirror, rotation);
                xs.add(ox + r[0]);
                ys.add(oy + by + y);
                zs.add(oz + r[1]);
                ts.add(t);
            }
        }

        ExpectedBlocks eb = new ExpectedBlocks(xs.toArray(), ys.toArray(), zs.toArray(), ts.toArray(), typeNames.toArray(new String[0]));
        if (eb.size() > 0) {
            eb.minX = eb.minY = eb.minZ = Integer.MAX_VALUE;
            eb.maxX = eb.maxY = eb.maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < eb.size(); i++) {
                eb.minX = Math.min(eb.minX, eb.xs[i]); eb.maxX = Math.max(eb.maxX, eb.xs[i]);
                eb.minY = Math.min(eb.minY, eb.ys[i]); eb.maxY = Math.max(eb.maxY, eb.ys[i]);
                eb.minZ = Math.min(eb.minZ, eb.zs[i]); eb.maxZ = Math.max(eb.maxZ, eb.zs[i]);
            }
        }
        return eb;
    }

    /** Kleine wachsende int-Liste, um Boxing bei zehntausenden Bloecken zu vermeiden. */
    static final class IntList {
        int[] a = new int[1024];
        int n;
        void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }
        int[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
