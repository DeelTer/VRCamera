package ru.deelter.vrcamera.client.desktop;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;

/**
 * what the game is asked for to draw a frame, which is not asked for the same way in every version of it
 */
final class GameFrame {

    /**
     * @param renderLevel false to draw the menus alone. What the newer game draws was said when it was extracted
     */
    static void render(Minecraft mc, DeltaTracker deltaTracker, boolean renderLevel) {
        mc.gameRenderer.render(deltaTracker, renderLevel);
    }

    private GameFrame() {
    }
}
