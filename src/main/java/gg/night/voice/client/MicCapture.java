package gg.night.voice.client;

import gg.night.voice.NightVoice;
import gg.night.voice.Protocol;
import gg.night.voice.VoiceConfig;
import org.concentus.OpusApplication;
import org.concentus.OpusEncoder;
import org.concentus.OpusSignal;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.List;

/** Captures 48 kHz / 16-bit / mono from the microphone and encodes 20 ms Opus frames. */
public final class MicCapture implements Runnable {
    static final AudioFormat FORMAT = new AudioFormat(Protocol.SAMPLE_RATE, 16, 1, true, false);

    private final VoiceClient client;
    private volatile boolean running;
    private Thread thread;
    private TargetDataLine line;

    public MicCapture(VoiceClient client) {
        this.client = client;
    }

    public static List<String> listDevices() {
        List<String> out = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(mi).isLineSupported(info)) out.add(mi.getName());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public void start() {
        running = true;
        thread = new Thread(this, "NightVoice-Mic");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        TargetDataLine l = line;
        if (l != null) {
            l.stop();
            l.close();
        }
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            String want = VoiceConfig.C.micDevice;
            TargetDataLine l = null;
            if (!want.isEmpty()) {
                for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                    if (mi.getName().equals(want)) {
                        Mixer m = AudioSystem.getMixer(mi);
                        if (m.isLineSupported(info)) {
                            l = (TargetDataLine) m.getLine(info);
                            break;
                        }
                    }
                }
            }
            if (l == null) l = (TargetDataLine) AudioSystem.getLine(info);
            l.open(FORMAT, Protocol.FRAME_SAMPLES * 2 * 6);
            l.start();
            line = l;

            OpusEncoder enc = new OpusEncoder(Protocol.SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            enc.setBitrate(Math.max(24_000, Math.min(32_000, VoiceConfig.C.bitrate)));
            enc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
            enc.setComplexity(6);

            byte[] raw = new byte[Protocol.FRAME_SAMPLES * 2];
            short[] pcm = new short[Protocol.FRAME_SAMPLES];
            byte[] out = new byte[400];
            int vadHold = 0;

            while (running) {
                int n = 0;
                while (n < raw.length && running) {
                    int r = l.read(raw, n, raw.length - n);
                    if (r <= 0) break;
                    n += r;
                }
                if (n < raw.length) continue;

                float gain = VoiceConfig.C.micGain;
                double sum = 0;
                for (int i = 0; i < pcm.length; i++) {
                    int v = (short) ((raw[2 * i + 1] << 8) | (raw[2 * i] & 0xFF));
                    v = Math.max(-32768, Math.min(32767, Math.round(v * gain)));
                    pcm[i] = (short) v;
                    sum += (double) v * v;
                }
                double level = Math.sqrt(sum / pcm.length) / 32768.0;

                boolean talk;
                if (!client.canTransmit()) {
                    talk = false;
                } else if (client.pttDown || client.radioDown) {
                    talk = true;
                } else if (VoiceConfig.C.voiceActivation) {
                    if (level > VoiceConfig.C.vadThreshold) vadHold = 12; // ~240 ms hang-over
                    talk = vadHold-- > 0;
                } else {
                    talk = false;
                }

                client.speaking = talk && level > 0.003;
                if (!talk) continue;

                int len = enc.encode(pcm, 0, Protocol.FRAME_SAMPLES, out, 0, out.length);
                client.sendAudio(out, len);
            }
        } catch (Exception e) {
            if (running) NightVoice.LOG.error("Microphone capture failed", e);
        } finally {
            client.speaking = false;
        }
    }
}
