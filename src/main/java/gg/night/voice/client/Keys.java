package gg.night.voice.client;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class Keys {
    private Keys() {}

    public static KeyBinding PTT, MUTE, DISTANCE, RADIO, RECORD, SETTINGS;

    private static KeyBinding reg(String id, int key) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.nightvoice." + id, InputUtil.Type.KEYSYM, key, "key.categories.nightvoice"));
    }

    public static void register() {
        PTT = reg("ptt", GLFW.GLFW_KEY_V);
        MUTE = reg("mute", GLFW.GLFW_KEY_M);
        DISTANCE = reg("distance", GLFW.GLFW_KEY_B);
        RADIO = reg("radio", GLFW.GLFW_KEY_G);
        RECORD = reg("record", GLFW.GLFW_KEY_UNKNOWN);
        SETTINGS = reg("settings", GLFW.GLFW_KEY_UNKNOWN);
    }
}
