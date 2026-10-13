package gg.night.voice;

/** UDP wire format shared by client and server. */
public final class Protocol {
    private Protocol() {}

    /** client -> server: [type][uuid hi][uuid lo][secret hi][secret lo] */
    public static final byte C2S_AUTH = 0;
    /** client -> server: [type][mode:1][flags:1][seq:4][len:2][opus...] */
    public static final byte C2S_AUDIO = 1;
    /** server -> client: [type] */
    public static final byte S2C_AUTH_OK = 2;
    /** server -> client: [type][uuid:16][flags:1][mode:1][x:f][y:f][z:f][seq:4][len:2][opus...] */
    public static final byte S2C_AUDIO = 3;
    /** client -> server keep-alive (keeps NAT mapping open) */
    public static final byte C2S_PING = 4;

    public static final int SAMPLE_RATE = 48_000;
    public static final int FRAME_SAMPLES = 960; // 20 ms @ 48 kHz
    public static final int MAX_PACKET = 1400;

    public static final int FLAG_RADIO = 1;
}
