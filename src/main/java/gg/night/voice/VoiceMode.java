package gg.night.voice;

public enum VoiceMode {
    WHISPER, NORMAL, SHOUT;

    /** Ranges in blocks. Server fills these from the config, client from the init packet. */
    public static final float[] RANGES = {4f, 16f, 32f};

    public float range() {
        return RANGES[ordinal()];
    }

    public VoiceMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static VoiceMode of(int i) {
        VoiceMode[] v = values();
        return v[Math.floorMod(i, v.length)];
    }
}
