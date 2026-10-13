package gg.night.voice.client;

import gg.night.voice.NightVoice;
import gg.night.voice.Protocol;
import gg.night.voice.VoiceConfig;
import gg.night.voice.VoiceMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.concentus.OpusDecoder;
import org.concentus.OpusException;
import org.lwjgl.openal.*;
import org.lwjgl.system.MemoryStack;

import java.nio.ShortBuffer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Own OpenAL device + context (thread-local) so voice never touches Minecraft's sound engine.
 * Each remote player is a MONO 3D source: OpenAL pans it (and HRTF-spatializes it for headphones)
 * relative to the listener's yaw/pitch. Listener sits at the origin, sources are given relative to
 * it, which avoids float precision problems far from world origin.
 */
public final class AudioEngine implements Runnable {
    public record Frame(UUID id, int flags, int mode, float x, float y, float z, int seq, byte[] opus) {}
    public record Listener(float fx, float fy, float fz, float ux, float uy, float uz, double x, double y, double z) {}

    public static final class Speaker {
        final UUID id;
        OpusDecoder dec;
        int src, filter;
        int lastSeq = Integer.MIN_VALUE;
        public volatile float x, y, z, occlusion;
        public volatile boolean radio;
        public volatile VoiceMode mode = VoiceMode.NORMAL;
        volatile long lastFrameMs;
        Speaker(UUID id) { this.id = id; }
    }

    private static final int PREBUFFER = 3;       // 60 ms jitter buffer
    private static final int MAX_QUEUED = 12;     // drop frames if we are >240 ms behind

    private final ConcurrentLinkedQueue<Frame> inbox = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<UUID, Speaker> speakers = new ConcurrentHashMap<>();
    private volatile Listener listener = new Listener(0, 0, -1, 0, 1, 0, 0, 0, 0);
    private volatile float reverbTarget;
    private float reverbCur;

    private volatile boolean running;
    private Thread thread;
    private long device, context;
    private boolean efx;
    private int effect, slot;

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        running = true;
        thread = new Thread(this, "NightVoice-Audio");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    public static List<String> listOutputs() {
        try {
            List<String> l = ALUtil.getStringList(0L, ALC11.ALC_ALL_DEVICES_SPECIFIER);
            return l == null ? List.of() : l;
        } catch (Throwable t) {
            return List.of();
        }
    }

    public void submit(UUID id, int flags, int mode, float x, float y, float z, int seq, byte[] opus) {
        inbox.add(new Frame(id, flags, mode, x, y, z, seq, opus));
    }

    public java.util.Collection<Speaker> speakers() {
        return speakers.values();
    }

    // ------------------------------------------------------------------ main-thread world analysis

    public void worldTick(MinecraftClient mc, int tick) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null) return;

        Vec3d eye = p.getEyePos();
        float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        float pitch = p.getPitch() * MathHelper.RADIANS_PER_DEGREE;
        float sy = MathHelper.sin(yaw), cy = MathHelper.cos(yaw);
        float sp = MathHelper.sin(pitch), cp = MathHelper.cos(pitch);
        // Minecraft: yaw 0 = +Z (south), -Z = north, +X = east -> same handedness as OpenAL (-Z forward, +X right)
        listener = new Listener(-sy * cp, -sp, cy * cp,      // forward ("at")
                -sy * sp, cp, cy * sp,                       // up
                eye.x, eye.y, eye.z);

        if (tick % 2 == 0) {
            long now = System.currentTimeMillis();
            for (Speaker s : speakers.values()) {
                if (now - s.lastFrameMs > 500) continue;
                float target = (VoiceConfig.C.occlusion && !s.radio)
                        ? Occlusion.measure(w, new Vec3d(s.x, s.y, s.z), eye) : 0f;
                s.occlusion += (target - s.occlusion) * 0.5f;
            }
        }
        if (tick % 10 == 0) {
            reverbTarget = VoiceConfig.C.reverb ? Occlusion.enclosure(w, p, eye) : 0f;
        }
    }

    // ------------------------------------------------------------------ audio thread

    @Override
    public void run() {
        if (!init()) {
            running = false;
            return;
        }
        try {
            while (running) {
                Frame f;
                int n = 0;
                while (n++ < 64 && (f = inbox.poll()) != null) handle(f);

                Listener L = listener;
                AL10.alListener3f(AL10.AL_POSITION, 0, 0, 0);
                AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{L.fx(), L.fy(), L.fz(), L.ux(), L.uy(), L.uz()});
                AL10.alListenerf(AL10.AL_GAIN, VoiceConfig.C.masterVolume);

                if (efx) {
                    reverbCur += (reverbTarget - reverbCur) * 0.05f;
                    EXTEfx.alAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN, reverbCur);
                }

                long now = System.currentTimeMillis();
                for (Speaker s : speakers.values()) {
                    reclaim(s);
                    updateSource(s, L);
                    if (now - s.lastFrameMs > 10_000
                            && AL10.alGetSourcei(s.src, AL10.AL_BUFFERS_QUEUED) == 0) {
                        destroy(s);
                        speakers.remove(s.id);
                    }
                }
                Thread.sleep(5);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            NightVoice.LOG.error("Voice audio thread crashed", t);
        } finally {
            shutdown();
        }
    }

    private boolean init() {
        try {
            CharSequence name = VoiceConfig.C.outputDevice.isEmpty() ? null : VoiceConfig.C.outputDevice;
            device = ALC10.alcOpenDevice(name);
            if (device == 0 && name != null) device = ALC10.alcOpenDevice((CharSequence) null);
            if (device == 0) {
                NightVoice.LOG.error("Voice: could not open an OpenAL output device");
                return false;
            }
            ALCCapabilities caps = ALC.createCapabilities(device);
            if (!caps.ALC_EXT_thread_local_context) {
                NightVoice.LOG.error("Voice: OpenAL driver lacks ALC_EXT_thread_local_context (need OpenAL Soft)");
                ALC10.alcCloseDevice(device);
                device = 0;
                return false;
            }
            int[] attrs = {SOFTHRTF.ALC_HRTF_SOFT, VoiceConfig.C.hrtf ? ALC10.ALC_TRUE : ALC10.ALC_FALSE, 0};
            context = ALC10.alcCreateContext(device, attrs);
            EXTThreadLocalContext.alcSetThreadContext(context);
            AL.createCapabilities(caps);

            AL10.alDistanceModel(AL10.AL_NONE); // we do our own logarithmic/inverse roll-off

            efx = caps.ALC_EXT_EFX;
            if (efx) {
                effect = EXTEfx.alGenEffects();
                EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
                EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, 2.6f);
                EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAIN, 0.5f);
                slot = EXTEfx.alGenAuxiliaryEffectSlots();
                EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
                EXTEfx.alAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN, 0f);
            }
            return true;
        } catch (Throwable t) {
            NightVoice.LOG.error("Voice: OpenAL init failed", t);
            return false;
        }
    }

    private void shutdown() {
        try {
            for (Speaker s : speakers.values()) destroy(s);
            speakers.clear();
            if (efx) {
                EXTEfx.alDeleteAuxiliaryEffectSlots(slot);
                EXTEfx.alDeleteEffects(effect);
            }
            EXTThreadLocalContext.alcSetThreadContext(0L);
            if (context != 0) ALC10.alcDestroyContext(context);
            if (device != 0) ALC10.alcCloseDevice(device);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ per-speaker

    private Speaker createSpeaker(UUID id) {
        try {
            Speaker s = new Speaker(id);
            s.dec = new OpusDecoder(Protocol.SAMPLE_RATE, 1);
            s.src = AL10.alGenSources();
            if (efx) {
                s.filter = EXTEfx.alGenFilters();
                EXTEfx.alFilteri(s.filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
                AL10.alSource3i(s.src, EXTEfx.AL_AUXILIARY_SEND_FILTER, slot, 0, EXTEfx.AL_FILTER_NULL);
            }
            return s;
        } catch (OpusException e) {
            return null;
        }
    }

    private void destroy(Speaker s) {
        AL10.alSourceStop(s.src);
        int q = AL10.alGetSourcei(s.src, AL10.AL_BUFFERS_QUEUED);
        for (int i = 0; i < q; i++) AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(s.src));
        AL10.alDeleteSources(s.src);
        if (efx && s.filter != 0) EXTEfx.alDeleteFilters(s.filter);
    }

    private void reclaim(Speaker s) {
        int done = AL10.alGetSourcei(s.src, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < done; i++) AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(s.src));
    }

    private void handle(Frame f) {
        Speaker s = speakers.get(f.id());
        if (s == null) {
            s = createSpeaker(f.id());
            if (s == null) return;
            speakers.put(f.id(), s);
        }
        s.x = f.x();
        s.y = f.y();
        s.z = f.z();
        s.radio = (f.flags() & Protocol.FLAG_RADIO) != 0;
        s.mode = VoiceMode.of(f.mode());
        s.lastFrameMs = System.currentTimeMillis();

        // drop duplicates / late packets (but accept a sender whose counter restarted)
        int diff = f.seq() - s.lastSeq;
        if (s.lastSeq != Integer.MIN_VALUE && diff <= 0 && diff > -100) return;

        reclaim(s);
        int queued = AL10.alGetSourcei(s.src, AL10.AL_BUFFERS_QUEUED);
        if (queued > MAX_QUEUED) return;

        try {
            short[] pcm = new short[Protocol.FRAME_SAMPLES];
            if (s.lastSeq != Integer.MIN_VALUE && diff == 2) {          // one lost packet -> concealment
                s.dec.decode(null, 0, 0, pcm, 0, Protocol.FRAME_SAMPLES, false);
                queue(s, pcm);
                pcm = new short[Protocol.FRAME_SAMPLES];
                queued++;
            }
            s.dec.decode(f.opus(), 0, f.opus().length, pcm, 0, Protocol.FRAME_SAMPLES, false);
            queue(s, pcm);
            queued++;
        } catch (OpusException e) {
            return;
        }
        s.lastSeq = f.seq();

        if (queued >= PREBUFFER && AL10.alGetSourcei(s.src, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(s.src);
        }
    }

    private void queue(Speaker s, short[] pcm) {
        int buf = AL10.alGenBuffers();
        try (MemoryStack st = MemoryStack.stackPush()) {
            ShortBuffer sb = st.mallocShort(pcm.length);
            sb.put(pcm).flip();
            AL10.alBufferData(buf, AL10.AL_FORMAT_MONO16, sb, Protocol.SAMPLE_RATE);
        }
        AL10.alSourceQueueBuffers(s.src, buf);
    }

    private void updateSource(Speaker s, Listener L) {
        float gain;
        float hf = 1f;
        if (s.radio) {
            AL10.alSourcei(s.src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(s.src, AL10.AL_POSITION, 0, 0, 0);
            gain = VoiceConfig.C.radioVolume;
            hf = 0.35f;                                   // thin "radio" tone
        } else {
            float dx = (float) (s.x - L.x()), dy = (float) (s.y - L.y()), dz = (float) (s.z - L.z());
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float range = s.mode.range();
            gain = d >= range ? 0f : rolloff(d, range);
            float occ = VoiceConfig.C.occlusion ? s.occlusion : 0f;
            gain *= 1f - 0.55f * occ;
            hf = 1f - 0.92f * occ;
            AL10.alSourcei(s.src, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(s.src, AL10.AL_POSITION, dx, dy, dz);
        }
        AL10.alSourcef(s.src, AL10.AL_GAIN, gain);
        if (efx) {
            EXTEfx.alFilterf(s.filter, EXTEfx.AL_LOWPASS_GAIN, 1f);
            EXTEfx.alFilterf(s.filter, EXTEfx.AL_LOWPASS_GAINHF, Math.max(hf, 0.02f));
            AL10.alSourcei(s.src, EXTEfx.AL_DIRECT_FILTER, s.filter);
        }
    }

    /** Inverse-distance (-6 dB per doubling) beyond a reference distance, smoothly faded to 0 at the max range. */
    static float rolloff(float d, float range) {
        float ref = Math.max(1f, range * 0.25f);
        float inv = ref / Math.max(d, ref);
        float t = MathHelper.clamp((d - range * 0.75f) / (range * 0.25f), 0f, 1f);
        float edge = 1f - t * t * (3f - 2f * t);
        return inv * edge;
    }
}
