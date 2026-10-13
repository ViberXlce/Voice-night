package gg.night.voice;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.component.ComponentType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModItems {
    private ModItems() {}

    public static final ComponentType<Integer> CHANNEL = Registry.register(
            Registries.DATA_COMPONENT_TYPE,
            Identifier.of(NightVoice.MOD_ID, "radio_channel"),
            ComponentType.<Integer>builder()
                    .codec(Codec.intRange(1, RadioItem.MAX_CHANNEL))
                    .packetCodec(PacketCodecs.VAR_INT)
                    .build());

    public static final Item RADIO = Registry.register(
            Registries.ITEM,
            Identifier.of(NightVoice.MOD_ID, "radio"),
            new RadioItem(new Item.Settings().maxCount(1).component(CHANNEL, 1)));

    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(RADIO));
    }
}
