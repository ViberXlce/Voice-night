package gg.night.voice.client;

import gg.night.voice.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side state + UDP connection to the relay. */
public final class VoiceClient {
    public static final VoiceClient I = new VoiceClient();

    public volatile VoiceMode mode = VoiceMode.NORMAL;
    public volatile boolean muted, deafened, pttDown, radioDown, speaking, authed, running;
    public volatile int radioChannel;
    public volatile Vec3d eyePos = Vec3d.ZERO;
    /** who we heard recently (uuid -> millis) - drives the HUD list */
    public final Map<UUID, Long> heard = new ConcurrentHashMap<>();
    public final VoiceRecorder recorder = new VoiceRecorder();

    private DatagramSocket socket;
    private InetSocketAddress server;
    private UUID me;
    private long hi, lo;
    private MicCapture mic;
    private AudioEngine audio;
    private Thread recvThread, netThread;
    private int seq;
    private int tickCount;

    private VoiceClient() {}

    // ---------------- connection ----------------

    public synchronized void connect(InitVoicePayload p, MinecraftClient mc) {
        disconnect();
        VoiceMode.RANGES[0] = p.whisper();
        VoiceMode.RANGES[1] = p.normal();
        VoiceMode.RANGES[2] = p.shout();
        try {
            SocketAddress a = mc.getNetworkHandler().getConnection().getAddress();
            String host = a instanceof InetSocketAddress isa ? isa.getHostString() : "127.0.0.1";
            server = new InetSocketAddress(host, p.port());
            socket = new DatagramSocket();
            me = p.player();
            hi = p.hi();
            lo = p.lo();
            authed = false;
            running = true;
            seq = 0;

            recvThread = new Thread(this::recvLoop, "NightVoice-Recv");
            netThread = new Thread(this::netLoop, "NightVoice-Net");
            recvThread.setDaemon(true);
            netThread.setDaemon(true);
            recvThread.start();
            netThread.start();
            startAudio();
            NightVoice.LOG.info("Voice connecting to {}", server);
        } catch (Exception e) {
            NightVoice.LOG.error("Voice connect failed", e);
            disconnect();
        }
    }

    public synchronized void disconnect() {
        running = false;
        authed = false;
        speaking = false;
        stopAudio();
        if (socket != null) socket.close();
        socket = null;
        recorder.stop();
        heard.clear();
    }

    private void startAudio() {
        audio = new AudioEngine();
        audio.start();
        mic = new MicCapture(this);
        mic.start();
    }

    private void stopAudio() {
        if (mic != null) mic.stop();
        if (audio != null) audio.stop();
        mic = null;
        audio = null;
    }

    /** Called after the settings screen changed device / HRTF. */
    public synchronized void restartAudio() {
        if (!running) return;
        stopAudio();
        startAudio();
    }

    // ---------------- tick (main thread) ----------------

    public void tick(MinecraftClient mc) {
        if (mc.player == null) return;
        eyePos = mc.player.getEyePos();

        while (Keys.MUTE.wasPressed()) {
            if (Screen.hasShiftDown()) {
                deafened = !deafened;
                mc.inGameHud.setOverlayMessage(Text.translatable("nightvoice.hud.deafened").append(deafened ? " ✔" : " ✘"), false);
            } else {
                muted = !muted;
                mc.inGameHud.setOverlayMessage(Text.translatable("nightvoice.hud.muted").append(muted ? " ✔" : " ✘"), false);
            }
        }
        while (Keys.DISTANCE.wasPressed()) {
            mode = mode.next();
            mc.inGameHud.setOverlayMessage(Text.translatable("nightvoice.mode." + mode.name().toLowerCase())
                    .append(" (" + (int) mode.range() + ")"), false);
        }
        while (Keys.SETTINGS.wasPressed()) mc.setScreen(new VoiceSettingsScreen(mc.currentScreen));
        while (Keys.RECORD.wasPressed()) {
            if (recorder.active()) {
                recorder.stop();
                mc.inGameHud.setOverlayMessage(Text.translatable("nightvoice.hud.saved"), false);
            } else {
                try {
                    Path f = recorder.start(mc.runDirectory.toPath().resolve("nightvoice").resolve("recordings"));
                    mc.inGameHud.setOverlayMessage(Text.translatable("nightvoice.hud.recording").append(" → " + f.getFileName()), false);
                } catch (IOException e) {
                    NightVoice.LOG.error("Could not start voice recording", e);
                }
            }
        }

        pttDown = Keys.PTT.isPressed();
        radioChannel = RadioItem.channelOf(mc.player);
        radioDown = radioChannel > 0 && Keys.RADIO.isPressed();

        if (audio != null) audio.worldTick(mc, tickCount);
        if (++tickCount % 100 == 0) {
            long now = System.currentTimeMillis();
            heard.values().removeIf(t -> now - t > 5000);
        }
    }

    // ---------------- mic thread callbacks ----------------

    public boolean canTransmit() {
        return running && authed && !muted;
    }

    public void sendAudio(byte[] data, int len) {
        DatagramSocket s = socket;
        if (s == null) return;
        int flags = radioDown ? Protocol.FLAG_RADIO : 0;
        ByteBuffer bb = ByteBuffer.allocate(1 + 1 + 1 + 4 + 2 + len);
        bb.put(Protocol.C2S_AUDIO).put((byte) mode.ordinal()).put((byte) flags)
                .putInt(seq++).putShort((short) len).put(data, 0, len);
        send(bb.array());
        if (recorder.active()) {
            Vec3d p = eyePos;
            byte[] copy = java.util.Arrays.copyOf(data, len);
            recorder.write(me, flags, mode.ordinal(), (float) p.x, (float) p.y, (float) p.z, copy);
        }
    }

    // ---------------- network threads ----------------

    private void send(byte[] data) {
        try {
            DatagramSocket s = socket;
            if (s != null) s.send(new DatagramPacket(data, data.length, server));
        } catch (IOException ignored) {
        }
    }

    private void netLoop() {
        while (running) {
            try {
                if (!authed) {
                    ByteBuffer bb = ByteBuffer.allocate(1 + 32);
                    bb.put(Protocol.C2S_AUTH)
                            .putLong(me.getMostSignificantBits()).putLong(me.getLeastSignificantBits())
                            .putLong(hi).putLong(lo);
                    send(bb.array());
                    Thread.sleep(500);
                } else {
                    send(new byte[]{Protocol.C2S_PING});
                    Thread.sleep(5000);
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void recvLoop() {
        byte[] buf = new byte[Protocol.MAX_PACKET];
        DatagramPacket pk = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                DatagramSocket s = socket;
                if (s == null) return;
                pk.setLength(buf.length);
                s.receive(pk);
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, pk.getLength());
                byte type = bb.get();
                if (type == Protocol.S2C_AUTH_OK) {
                    authed = true;
                } else if (type == Protocol.S2C_AUDIO) {
                    UUID id = new UUID(bb.getLong(), bb.getLong());
                    int flags = bb.get();
                    int m = bb.get();
                    float x = bb.getFloat(), y = bb.getFloat(), z = bb.getFloat();
                    int sq = bb.getInt();
                    int len = bb.getShort() & 0xFFFF;
                    if (len > bb.remaining()) continue;
                    byte[] data = new byte[len];
                    bb.get(data);

                    heard.put(id, System.currentTimeMillis());
                    recorder.write(id, flags, m, x, y, z, data);
                    AudioEngine a = audio;
                    if (a != null && !deafened) a.submit(id, flags, m, x, y, z, sq, data);
                }
            } catch (SocketException e) {
                return; // closed
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }
}
