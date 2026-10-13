package gg.night.voice;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/** Walkie-talkie. Hold it (either hand) to use the radio key. Right-click changes channel. */
public class RadioItem extends Item {
    public static final int MAX_CHANNEL = 99;

    public RadioItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient) {
            int ch = stack.getOrDefault(ModItems.CHANNEL, 1);
            ch = user.isSneaking()
                    ? (ch - 2 + MAX_CHANNEL) % MAX_CHANNEL + 1
                    : ch % MAX_CHANNEL + 1;
            stack.set(ModItems.CHANNEL, ch);
            user.sendMessage(Text.translatable("item.nightvoice.radio.channel", ch), true);
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.nightvoice.radio.channel", stack.getOrDefault(ModItems.CHANNEL, 1))
                .formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("item.nightvoice.radio.tooltip").formatted(Formatting.DARK_GRAY));
    }

    /** 0 = player is not holding a radio, otherwise the channel of the radio in hand. */
    public static int channelOf(PlayerEntity p) {
        for (Hand h : Hand.values()) {
            ItemStack s = p.getStackInHand(h);
            if (s.getItem() instanceof RadioItem) return s.getOrDefault(ModItems.CHANNEL, 1);
        }
        return 0;
    }
}
