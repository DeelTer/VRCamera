package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.compat.SubmitNodeCollector;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.rig.Subject;

/**
 * The menu a player without VR has open, as something in the world for the camera to look at.
 * <p>
 * Not there in this version of the game yet: it draws its menus in a way of its own, and the menu in the world has
 * to be made for that. The camera films without it, the way it does with a menu that is not worth a look.
 */
public final class DesktopGui {
	private DesktopGui() {
	}

	/**
	 * @return if what the game draws right now is the menu alone, for the screen in the world
	 */
	public static boolean isDrawing() {
		return false;
	}

	/**
	 * @return the middle of the screen in the world, null without a menu that is worth a look
	 */
	public static Vec3 place(Minecraft mc, Subject subject, CameraConfig config) {
		return null;
	}

	/**
	 * @param widget something in a menu that is not part of it, and has no say in where the menu is
	 */
	public static void leaveOut(GuiEventListener widget) {
	}

	/**
	 * @param viewPosition where the pass looks from, the pose stack is relative to that
	 */
	public static void render(SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack) {
	}

	public static void close() {
	}
}
