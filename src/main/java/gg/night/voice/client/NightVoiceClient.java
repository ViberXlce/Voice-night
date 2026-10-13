package gg.night.voice.client;

import gg.night.voice.InitVoicePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class NightVoiceClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Keys.register();

        ClientPlayNetworking.registerGlobalReceiver(InitVoicePayload.ID,
                (payload, ctx) -> VoiceClient.I.connect(payload, ctx.client()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> VoiceClient.I.disconnect());
        ClientTickEvents.END_CLIENT_TICK.register(VoiceClient.I::tick);
        HudRenderCallback.EVENT.register(VoiceHud::render);

        // "Options -> Voice Chat" button
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof OptionsScreen) {
                Screens.getButtons(screen).add(
                        ButtonWidget.builder(Text.translatable("nightvoice.options.title"),
                                        b -> client.setScreen(new VoiceSettingsScreen(screen)))
                                .dimensions(w - 105, 6, 100, 20).build());
            }
        });
    }
}
