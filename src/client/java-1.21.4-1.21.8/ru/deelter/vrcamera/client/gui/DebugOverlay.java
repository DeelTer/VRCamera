package ru.deelter.vrcamera.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import ru.deelter.vrcamera.client.Vive;
import ru.deelter.vrcamera.client.Vr;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;

/**
 * Text on the hud about what the camera is doing and why, to tune the settings with.
 */
public final class DebugOverlay {
	private static final int LINE_HEIGHT = 10;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int BACKGROUND_COLOR = 0x90000000;

	public static void toggle() {
		CameraConfig config = CameraConfig.current();
		config.debugOverlay = !config.debugOverlay;
		config.save();
	}

	public static void extract(GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (!CameraConfig.current().debugOverlay || mc.player == null) {
			return;
		}
		boolean onScreen = DesktopCamera.INSTANCE.isOn();
		if (!onScreen && (!Vr.INSTALLED || !Vive.isCameraOn())) {
			return;
		}
		int y = 4;
		for (String line : onScreen ? DesktopCamera.INSTANCE.debugLines() : Vive.debugLines()) {
			graphics.fill(2, y - 1, 6 + mc.font.width(line), y + LINE_HEIGHT - 1, BACKGROUND_COLOR);
			graphics.drawString(mc.font, line, 4, y, TEXT_COLOR, false);
			y += LINE_HEIGHT;
		}
	}
}
