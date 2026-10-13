package gg.night.voice.client;

import gg.night.voice.NightVoice;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Records every voice frame (ours + everyone we hear) with timestamp and 3D position into an .nvr file
 * so it can be re-played / synced with a Flashback replay later.
 *
 * NVR1 format (big-endian):
 *   int magic 'NVR1', short version(1)
 *   repeated: int tMs | long uuidHi | long uuidLo | byte flags | byte mode | float x,y,z | short len | opus[len]
 */
public final class VoiceRecorder {
    private DataOutputStream out;
    private long t0;

    public synchronized boolean active() {
        return out != null;
    }

    public synchronized Path start(Path dir) throws IOException {
        stop();
        Files.createDirectories(dir);
        Path f = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".nvr");
        out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(f)));
        out.writeInt(0x4E565231);
        out.writeShort(1);
        t0 = System.currentTimeMillis();
        return f;
    }

    public synchronized void stop() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    public synchronized void write(UUID id, int flags, int mode, float x, float y, float z, byte[] opus) {
        if (out == null) return;
        try {
            out.writeInt((int) (System.currentTimeMillis() - t0));
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
            out.writeByte(flags);
            out.writeByte(mode);
            out.writeFloat(x);
            out.writeFloat(y);
            out.writeFloat(z);
            out.writeShort(opus.length);
            out.write(opus);
        } catch (IOException e) {
            NightVoice.LOG.warn("Voice recording stopped: {}", e.toString());
            stop();
        }
    }
}
