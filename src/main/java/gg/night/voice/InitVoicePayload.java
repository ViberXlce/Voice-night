package gg.night.voice;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Sent once on join: tells the client where the UDP relay is and gives it a one-time secret. */
public record InitVoicePayload(UUID player, int port, long hi, long lo,
                               float whisper, float normal, float shout) implements CustomPayload {

    public static final CustomPayload.Id<InitVoicePayload> ID =
            new CustomPayload.Id<>(Identifier.of(NightVoice.MOD_ID, "init"));

    public static final PacketCodec<RegistryByteBuf, InitVoicePayload> CODEC =
            PacketCodec.of(InitVoicePayload::write, InitVoicePayload::read);

    private void write(PacketByteBuf b) {
        b.writeUuid(player);
        b.writeVarInt(port);
        b.writeLong(hi);
        b.writeLong(lo);
        b.writeFloat(whisper);
        b.writeFloat(normal);
        b.writeFloat(shout);
    }

    private static InitVoicePayload read(PacketByteBuf b) {
        return new InitVoicePayload(b.readUuid(), b.readVarInt(), b.readLong(), b.readLong(),
                b.readFloat(), b.readFloat(), b.readFloat());
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
