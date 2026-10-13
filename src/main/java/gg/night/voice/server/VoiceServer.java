package gg.night.voice.server;

import gg.night.voice.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight UDP relay. The relay thread never touches game objects: the main thread
 * publishes an immutable snapshot of player positions every 2 ticks and the relay thread reads it.
 */
public final class VoiceServer {
    private record State(String dim, double x, double y, double z, int radio) {}

    private static final class Session {
        final UUID id;
        final long hi, lo;
        volatile SocketAddress addr;
        Session(UUID id, long hi, long lo) { this.id = id; this.hi = hi; this.lo = lo; }
    }

    private final MinecraftServer mc;
    private DatagramSocket socket;
    private Thread thread;
    private volatile boolean running;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<SocketAddress, Session> byAddr = new ConcurrentHashMap<>();
    private volatile Map<UUID, State> states = Map.of();

    public VoiceServer(MinecraftServer mc) {
        this.mc = mc;
    }

    public void start() {
        try {
            socket = new DatagramSocket(VoiceConfig.C.udpPort);
        } catch (SocketException e) {
            NightVoice.LOG.error("Voice relay could not bind UDP port {} (is it in use?)", VoiceConfig.C.udpPort, e);
            socket = null;
            return;
        }
        running = true;
        thread = new Thread(this::loop, "NightVoice-Relay");
        thread.setDaemon(true);
        thread.start();
        NightVoice.LOG.info("Voice relay listening on UDP {}", VoiceConfig.C.udpPort);
    }

    public void stop() {
        running = false;
        if (socket != null) socket.close();
    }

    // ---------- main-thread API ----------

    public void onJoin(ServerPlayerEntity p) {
        if (socket == null) return;
        UUID secret = UUID.randomUUID();
        Session s = new Session(p.getUuid(), secret.getMostSignificantBits(), secret.getLeastSignificantBits());
        Session old = sessions.put(p.getUuid(), s);
        if (old != null && old.addr != null) byAddr.remove(old.addr);
        ServerPlayNetworking.send(p, new InitVoicePayload(p.getUuid(), socket.getLocalPort(), s.hi, s.lo,
                VoiceMode.RANGES[0], VoiceMode.RANGES[1], VoiceMode.RANGES[2]));
    }

    public void onLeave(UUID id) {
        Session s = sessions.remove(id);
        if (s != null && s.addr != null) byAddr.remove(s.addr);
    }

    public void tick() {
        if (mc.getTicks() % 2 != 0) return;
        Map<UUID, State> m = new HashMap<>();
        for (ServerPlayerEntity p : mc.getPlayerManager().getPlayerList()) {
            m.put(p.getUuid(), new State(
                    p.getServerWorld().getRegistryKey().getValue().toString(),
                    p.getX(), p.getEyeY(), p.getZ(),
                    RadioItem.channelOf(p)));
        }
        states = m;
    }

    // ---------- relay thread ----------

    private void loop() {
        byte[] buf = new byte[Protocol.MAX_PACKET];
        DatagramPacket in = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                in.setLength(buf.length);
                socket.receive(in);
                handle(in);
            } catch (IOException e) {
                if (running) NightVoice.LOG.warn("Voice relay IO error", e);
            } catch (RuntimeException ignored) {
                // malformed packet - drop
            }
        }
    }

    private void handle(DatagramPacket in) {
        ByteBuffer bb = ByteBuffer.wrap(in.getData(), 0, in.getLength());
        byte type = bb.get();
        SocketAddress from = in.getSocketAddress();

        switch (type) {
            case Protocol.C2S_AUTH -> {
                UUID id = new UUID(bb.getLong(), bb.getLong());
                long hi = bb.getLong(), lo = bb.getLong();
                Session s = sessions.get(id);
                if (s == null || s.hi != hi || s.lo != lo) return;
                if (s.addr != null) byAddr.remove(s.addr);
                s.addr = from;
                byAddr.put(from, s);
                send(from, new byte[]{Protocol.S2C_AUTH_OK});
            }
            case Protocol.C2S_AUDIO -> {
                Session s = byAddr.get(from);
                if (s == null) return;
                int mode = bb.get();
                int flags = bb.get();
                int seq = bb.getInt();
                int len = bb.getShort() & 0xFFFF;
                if (len <= 0 || len > bb.remaining() || len > 600) return;
                byte[] opus = new byte[len];
                bb.get(opus);
                relay(s, mode, flags, seq, opus);
            }
            default -> { /* C2S_PING etc. */ }
        }
    }

    private void relay(Session from, int modeIdx, int flags, int seq, byte[] opus) {
        Map<UUID, State> snap = states;
        State me = snap.get(from.id);
        if (me == null) return;

        VoiceMode mode = VoiceMode.of(modeIdx);
        float range = mode.range();
        double r2 = (double) range * range;

        // radio is validated on the server: the sender must really be holding a radio
        boolean radio = (flags & Protocol.FLAG_RADIO) != 0 && VoiceConfig.C.radioEnabled && me.radio() > 0;

        byte[] normalPkt = pack(from.id, 0, mode, me, seq, opus);
        byte[] radioPkt = radio ? pack(from.id, Protocol.FLAG_RADIO, mode, me, seq, opus) : null;

        for (Map.Entry<UUID, State> e : snap.entrySet()) {
            if (e.getKey().equals(from.id)) continue;
            Session t = sessions.get(e.getKey());
            if (t == null || t.addr == null) continue;
            State o = e.getValue();

            if (radio && o.radio() == me.radio()) {
                send(t.addr, radioPkt);                         // same channel: any distance, any dimension
            } else if (o.dim().equals(me.dim())) {
                double dx = o.x() - me.x(), dy = o.y() - me.y(), dz = o.z() - me.z();
                if (dx * dx + dy * dy + dz * dz <= r2) send(t.addr, normalPkt);
            }
        }
    }

    private static byte[] pack(UUID id, int flags, VoiceMode mode, State me, int seq, byte[] opus) {
        ByteBuffer out = ByteBuffer.allocate(1 + 16 + 1 + 1 + 12 + 4 + 2 + opus.length);
        out.put(Protocol.S2C_AUDIO)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .put((byte) flags).put((byte) mode.ordinal())
                .putFloat((float) me.x()).putFloat((float) me.y()).putFloat((float) me.z())
                .putInt(seq).putShort((short) opus.length).put(opus);
        return out.array();
    }

    private void send(SocketAddress to, byte[] data) {
        try {
            socket.send(new DatagramPacket(data, data.length, to));
        } catch (IOException ignored) {
        }
    }
}
