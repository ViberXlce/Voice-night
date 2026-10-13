package gg.night.voice.client;

import gg.night.voice.VoiceConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Options -> Voice Chat */
public class VoiceSettingsScreen extends Screen {
    private final Screen parent;

    public VoiceSettingsScreen(Screen parent) {
        super(Text.translatable("nightvoice.options.title"));
        this.parent = parent;
    }

    private static Text devName(String s) {
        return s.isEmpty() ? Text.translatable("nightvoice.options.default") : Text.literal(s);
    }

    @Override
    protected void init() {
        VoiceConfig c = VoiceConfig.C;
        int lx = width / 2 - 155, rx = width / 2 + 5, bw = 150, y = 36, step = 24;

        List<String> mics = new ArrayList<>(MicCapture.listDevices());
        mics.add(0, "");
        List<String> outs = new ArrayList<>(AudioEngine.listOutputs());
        outs.add(0, "");

        addDrawableChild(CyclingButtonWidget.<String>builder(VoiceSettingsScreen::devName)
                .values(mics).initially(mics.contains(c.micDevice) ? c.micDevice : "")
                .build(lx, y, bw, 20, Text.translatable("nightvoice.options.mic"), (b, v) -> c.micDevice = v));
        addDrawableChild(CyclingButtonWidget.<String>builder(VoiceSettingsScreen::devName)
                .values(outs).initially(outs.contains(c.outputDevice) ? c.outputDevice : "")
                .build(rx, y, bw, 20, Text.translatable("nightvoice.options.output"), (b, v) -> c.outputDevice = v));
        y += step;

        addDrawableChild(new SliderWidget(lx, y, bw, 20, Text.empty(), c.micGain / 2f) {
            { updateMessage(); }
            @Override protected void updateMessage() {
                setMessage(Text.translatable("nightvoice.options.micgain").append(": " + Math.round(value * 200) + "%"));
            }
            @Override protected void applyValue() { c.micGain = (float) (value * 2); }
        });
        addDrawableChild(new SliderWidget(rx, y, bw, 20, Text.empty(), c.masterVolume / 2f) {
            { updateMessage(); }
            @Override protected void updateMessage() {
                setMessage(Text.translatable("nightvoice.options.volume").append(": " + Math.round(value * 200) + "%"));
            }
            @Override protected void applyValue() { c.masterVolume = (float) (value * 2); }
        });
        y += step;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(c.voiceActivation)
                .build(lx, y, bw, 20, Text.translatable("nightvoice.options.vad"), (b, v) -> c.voiceActivation = v));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(c.occlusion)
                .build(rx, y, bw, 20, Text.translatable("nightvoice.options.occlusion"), (b, v) -> c.occlusion = v));
        y += step;

        addDrawableChild(CyclingButtonWidget.onOffBuilder(c.reverb)
                .build(lx, y, bw, 20, Text.translatable("nightvoice.options.reverb"), (b, v) -> c.reverb = v));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(c.hrtf)
                .build(rx, y, bw, 20, Text.translatable("nightvoice.options.hrtf"), (b, v) -> c.hrtf = v));

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close())
                .dimensions(width / 2 - 100, height - 28, 200, 20).build());
    }

    @Override
    public void render(DrawContext g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        g.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);
    }

    @Override
    public void close() {
        VoiceConfig.save();
        VoiceClient.I.restartAudio();
        client.setScreen(parent);
    }
}
