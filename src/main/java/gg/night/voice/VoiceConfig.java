package gg.night.voice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** config/nightvoice.json - server fields apply on the server, client fields on the client. */
public final class VoiceConfig {
    public static VoiceConfig C = new VoiceConfig();

    // ---- server ----
    public int udpPort = 24464;          // open this UDP port on the host/firewall
    public float whisperRange = 4f;      // 3-5
    public float normalRange = 16f;      // 15-18
    public float shoutRange = 32f;       // 30-35
    public boolean radioEnabled = true;

    // ---- client ----
    public String micDevice = "";
    public String outputDevice = "";
    public float micGain = 1f;
    public float masterVolume = 1f;
    public float radioVolume = 0.9f;
    public boolean voiceActivation = false;
    public float vadThreshold = 0.02f;
    public boolean occlusion = true;
    public boolean reverb = true;
    public boolean hrtf = true;
    public int bitrate = 32_000;         // 24000-32000

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("nightvoice.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                VoiceConfig loaded = GSON.fromJson(Files.readString(p), VoiceConfig.class);
                if (loaded != null) C = loaded;
            }
            save();
        } catch (Exception e) {
            NightVoice.LOG.warn("Could not read nightvoice.json, using defaults", e);
        }
        VoiceMode.RANGES[0] = C.whisperRange;
        VoiceMode.RANGES[1] = C.normalRange;
        VoiceMode.RANGES[2] = C.shoutRange;
    }

    public static void save() {
        try {
            Files.writeString(path(), GSON.toJson(C));
        } catch (Exception e) {
            NightVoice.LOG.warn("Could not write nightvoice.json", e);
        }
    }
}
