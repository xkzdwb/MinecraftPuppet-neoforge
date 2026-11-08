package com.dsh.puppet.client;

import com.dsh.puppet.Protocol;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端入口：注册 K 键、每 tick 检查、上线时握手。
 *
 * <p>面板只是遥控器：关掉面板任务照样在跑。
 *
 * <p>事件订阅在 {@link PuppetModClientBootstrap} 里显式注册（不依赖注解扫描）。
 */
@OnlyIn(Dist.CLIENT)
public final class PuppetModClient {
    public static KeyMapping openPanel;

    private PuppetModClient() {
    }

    // ---- MOD 总线

    public static final class ModEvents {
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            openPanel = new KeyMapping("key.puppet.panel", InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_K, KeyMapping.Category.MISC);
            event.register(openPanel);
        }
    }

    // ---- 游戏总线

    public static final class GameEvents {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (openPanel == null || mc.player == null) {
                return;
            }
            while (openPanel.consumeClick()) {
                if (mc.gui.screen() == null) {
                    PuppetState.panelVisible = true;
                    mc.gui.setScreen(new PuppetScreen());
                    ClientController.setWanted(true);
                } else if (mc.gui.screen() instanceof PuppetScreen) {
                    PuppetState.panelVisible = false;
                    mc.gui.setScreen(null);
                    ClientController.setWanted(false);
                }
            }
        }

        @SubscribeEvent
        public static void onLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
            ClientController.hello();
        }

        @SubscribeEvent
        public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            synchronized (PuppetState.events) {
                PuppetState.events.clear();
            }
        }
    }
}
