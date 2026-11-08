package com.dsh.puppet.client;

import com.dsh.puppet.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 人偶控制台：指令输入框 + 快捷按钮 + 位置/血量/任务/事件流。
 *
 * <p>只是遥控器 —— 关掉面板（再次按 K 或 Esc）任务继续跑。
 */
public class PuppetScreen extends Screen {
    private static final int PANEL_W = 330;
    private static final int PANEL_H = 230;

    private EditBox input;
    private int left;
    private int top;

    private static final String[][] QUICK = {
            {"前往", "去 0 64 0"},
            {"砍树", "砍 5 棵树"},
            {"挖矿", "挖 10 个钻石"},
            {"收菜", "收 16"},
            {"捡东西", "捡东西"},
            {"跟着我", "跟着我"},
            {"保护", "保护"},
            {"打怪", "打僵尸 3"},
            {"种树", "种树 2"},
            {"停", "停"},
    };

    public PuppetScreen() {
        super(Component.literal("人偶控制台"));
    }

    @Override
    protected void init() {
        this.left = (this.width - PANEL_W) / 2;
        this.top = (this.height - PANEL_H) / 2;

        this.input = new EditBox(this.font, left + 8, top + PANEL_H - 28, PANEL_W - 82, 18,
                Component.literal("指令"));
        this.input.setMaxLength(256);
        this.input.setHint(Component.literal("输入指令，例如：砍 5 棵树"));
        this.addRenderableWidget(this.input);

        this.addRenderableWidget(Button.builder(Component.literal("执行"), b -> submit())
                .bounds(left + PANEL_W - 70, top + PANEL_H - 28, 62, 18)
                .build());

        int bw = 60;
        int bh = 18;
        int perRow = 5;
        for (int i = 0; i < QUICK.length; i++) {
            String label = QUICK[i][0];
            String cmd = QUICK[i][1];
            int x = left + 8 + (i % perRow) * (bw + 4);
            int y = top + 52 + (i / perRow) * (bh + 4);
            this.addRenderableWidget(Button.builder(Component.literal(label), b -> input.setValue(cmd))
                    .bounds(x, y, bw, bh)
                    .build());
        }
    }

    private void submit() {
        String text = input.getValue().trim();
        if (text.isEmpty()) {
            return;
        }
        ClientController.command(text);
        input.setValue("");
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257 || event.key() == 335) { // Enter
            submit();
            return true;
        }
        if (PuppetModClient.openPanel != null) {
            boolean matches = PuppetModClient.openPanel.matches(event);
            if (matches) {
                onClose();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        PuppetState.panelVisible = false;
        ClientController.setWanted(false);
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 半透明背景
        g.fill(0, 0, this.width, this.height, 0x60000000);

        // 面板底板
        g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xE8101010);
        g.outline(left, top, PANEL_W, PANEL_H, 0xFF35C0FF);

        int y = top + 8;
        g.text(this.font, "人偶控制台  §7(K 开关)", left + 8, y, 0xFF55DDFF, true);
        y += 14;
        g.text(this.font, "玩家 §f" + PuppetState.playerName() + " §7任务 §e" + PuppetState.taskName(),
                left + 8, y, 0xFFFFFFFF, true);
        y += 12;
        g.text(this.font, "位置 §f" + PuppetState.posText() + " §7血量 §c" + PuppetState.healthText(),
                left + 8, y, 0xFFFFFFFF, true);

        // 事件流（固定在按钮下方）
        int ey = top + PANEL_H - 118;
        g.text(this.font, "§7事件流", left + 8, ey, 0xFFAAAAAA, true);
        ey += 11;
        List<String> evts = new ArrayList<>();
        synchronized (PuppetState.events) {
            evts.addAll(PuppetState.events);
        }
        int from = Math.max(0, evts.size() - 6);
        for (int i = from; i < evts.size(); i++) {
            String line = evts.get(i);
            if (this.font.width(line) > PANEL_W - 20) {
                line = this.font.plainSubstrByWidth(line, PANEL_W - 26) + "…";
            }
            g.text(this.font, line, left + 10, ey + (i - from) * 10, 0xFFCCCCCC, false);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
