package ch.blugdeg.lifematic;

/**
 * Nachrichten zwischen Plugin und Mod auf dem Kanal "lifematic:main".
 * Jede Nachricht beginnt mit einem Typ-Byte, danach folgen die Felder
 * im Format von DataOutputStream (big endian, Strings als writeUTF).
 * Muss identisch zur Klasse Protocol in der Mod sein.
 */
public final class Protocol {
    private Protocol() {}

    public static final String CHANNEL = "lifematic:main";
    public static final int VERSION = 1;
    /** Nutzlast pro Upload-Paket (Client -> Server hat ein Limit von 32 KiB). */
    public static final int UPLOAD_CHUNK = 30_000;
    /** Nutzlast pro Download-Paket (Server -> Client erlaubt bis 1 MiB). */
    public static final int DOWNLOAD_CHUNK = 256_000;

    // Client -> Server
    public static final byte C_HELLO = 1;         // int version
    public static final byte C_UPLOAD_BEGIN = 2;  // utf existingId, utf name, utf fileName, int size, int ox, oy, oz, utf rotation, utf mirror, int minX, minY, minZ, maxX, maxY, maxZ
    public static final byte C_UPLOAD_CHUNK = 3;  // int offset, int len, bytes
    public static final byte C_UPLOAD_END = 4;    // -
    public static final byte C_REQUEST = 5;       // utf id
    public static final byte C_HAVE = 6;          // int n, n * (utf id, int version)  - welche Bauplaene der Client schon hat

    // Server -> Client
    public static final byte S_HELLO = 10;        // int version
    public static final byte S_FILE_BEGIN = 11;   // utf id, int version, utf name, utf owner, int size, int ox, oy, oz, utf rotation, utf mirror, boolean isOwner
    public static final byte S_FILE_CHUNK = 12;   // int offset, int len, bytes
    public static final byte S_FILE_END = 13;     // utf id
    public static final byte S_REMOVE = 14;       // utf id
    public static final byte S_UPLOAD_RESULT = 15;// boolean ok, utf message, utf id
    public static final byte S_SYNC = 16;         // int n, n * (utf id, int version)  - alle gueltigen Bauplaene
}
