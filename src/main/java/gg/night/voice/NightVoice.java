package gg.night.voice;

import gg.night.voice.server.VoiceServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NightVoice implements ModInitializer {
    public static final String MOD_ID = "nightvoice";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static VoiceServer voiceServer;

    @Override
    public void onInitialize() {
        VoiceConfig.load();
        ModItems.register();
        PayloadTypeRegistry.playS2C().register(InitVoicePayload.ID, InitVoicePayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            voiceServer = new VoiceServer(server);
            voiceServer.start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (voiceServer != null) {
                voiceServer.stop();
                voiceServer = null;
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (voiceServer != null) voiceServer.onJoin(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (voiceServer != null) voiceServer.onLeave(handler.player.getUuid());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (voiceServer != null) voiceServer.tick();
        });
        LOG.info("NIGHT Voice loaded");
    }
}
