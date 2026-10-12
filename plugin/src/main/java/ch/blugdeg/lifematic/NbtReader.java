package ch.blugdeg.lifematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimaler NBT-Leser fuer .litematic-Dateien (gzip-komprimiert).
 * Compounds werden zu Map, Listen zu List, Arrays zu primitiven Arrays.
 */
public final class NbtReader {
    private NbtReader() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readGzip(InputStream raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            int type = in.readUnsignedByte();
            if (type != 10) throw new IOException("Kein NBT-Compound am Anfang (Typ " + type + ")");
            in.readUTF(); // Name des Wurzel-Tags
            return (Map<String, Object>) readPayload(in, 10, 0);
        }
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT zu tief verschachtelt");
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                int n = checkLen(in.readInt());
                byte[] b = new byte[n];
                in.readFully(b);
                return b;
            }
            case 8: return in.readUTF();
            case 9: {
                int elemType = in.readUnsignedByte();
                int n = checkLen(in.readInt());
                List<Object> list = new ArrayList<>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) list.add(readPayload(in, elemType, depth + 1));
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    String name = in.readUTF();
                    map.put(name, readPayload(in, t, depth + 1));
                }
                return map;
            }
            case 11: {
                int n = checkLen(in.readInt());
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                int n = checkLen(in.readInt());
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = in.readLong();
                return a;
            }
            default:
                throw new IOException("Unbekannter NBT-Typ " + type);
        }
    }

    private static int checkLen(int n) throws IOException {
        if (n < 0 || n > 64_000_000) throw new IOException("Ungueltige NBT-Laenge " + n);
        return n;
    }
}
