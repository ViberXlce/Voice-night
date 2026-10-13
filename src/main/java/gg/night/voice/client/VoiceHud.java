package gg.night.voice.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;

public final class VoiceHud {
    private VoiceHud() {}

    private static final int GREEN = 0xFF55FF55, GRAY = 0xFF8A8A8A, RED = 0xFFFF5555, ORANGE = 0xFFFFAA00;

    public static void render(DrawContext g, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        VoiceClient v = VoiceClient.I;
        if (mc.player == null || mc.options.hudHidden || !v.running) return;

        int w = g.getScaledWindowWidth(), h = g.getScaledWindowHeight();

        // own indicator: above the health bar
        int x = w / 2 - 91, y = h - 62;
        int col = !v.authed ? GRAY : v.muted ? RED : v.speaking ? (v.radioDown ? ORANGE : GREEN) : GRAY;
        mic(g, x, y, col);

        Text label = Text.translatable("nightvoice.mode." + v.mode.name().toLowerCase());
        if (v.radioChannel > 0) label = label.copy().append("  📻 " + v.radioChannel);
        if (v.muted) label = Text.translatable("nightvoice.hud.muted");
        else if (v.deafened) label = Text.translatable("nightvoice.hud.deafened");
        g.drawTextWithShadow(mc.textRenderer, label, x + 11, y + 2, 0xFFFFFFFF);
        if (v.recorder.active()) g.fill(x + 11, y - 4, x + 15, y - 1, RED);

        // who is speaking right now
        int ly = 6;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : v.heard.entrySet()) {
            if (now - e.getValue() > 350) continue;
            PlayerListEntry ple = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getPlayerListEntry(e.getKey());
            String name = ple != null ? ple.getProfile().getName() : "?";
            int tw = mc.textRenderer.getWidth(name);
            mic(g, w - 14, ly, GREEN);
            g.drawTextWithShadow(mc.textRenderer, name, w - 18 - tw, ly + 2, 0xFFFFFFFF);
            ly += 14;
        }
    }

    /** 7x11 px microphone icon drawn with plain rectangles (no texture needed). */
    private static void mic(DrawContext g, int x, int y, int c) {
        g.fill(x + 2, y, x + 5, y + 6, c);
        g.fill(x, y + 4, x + 1, y + 7, c);
        g.fill(x + 6, y + 4, x + 7, y + 7, c);
        g.fill(x + 1, y + 7, x + 6, y + 8, c);
        g.fill(x + 3, y + 8, x + 4, y + 10, c);
        g.fill(x + 1, y + 10, x + 6, y + 11, c);
    }
}
