package ru.deelter.vrcamera.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.desktop.SheetReach;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.photo.PhotoSheet;

/**
 * A photo a player at a screen looks at for a moment is shown large at the side of the screen: one that hangs
 * high up, far away or at an angle is seen straight on. It slides in from the right, and down and away when
 * they look somewhere else. The picture is the one the sheet has, there is no more to see in it than on the sheet.
 */
public final class PhotoPreview {
	public static final PhotoPreview INSTANCE = new PhotoPreview();

	private static final double AIM = 0.14;
	private static final double IN_SECONDS = 0.25;
	private static final double OUT_SECONDS = 0.2;
	private static final float HEIGHT_PART = 0.45F;
	private static final float WIDTH_PART = 0.4F;
	private static final int MARGIN = 8;
	private static final int BORDER = 2;
	private static final int PAPER = 0xFFFFFFFF;

	private PhotoSheet lookedAt;
	private PhotoSheet shown;
	private double looked;
	private double in;
	private double out;

	private PhotoPreview() {
	}

	/**
	 * one frame of a player who is not in VR
	 */
	public void frame(Minecraft mc, double dt) {
		final PhotoSheet now = lookedAt(mc);
		looked = now != null && now == lookedAt ? looked + dt : 0;
		lookedAt = now;
		final PhotoSheet wanted = looked >= CameraConfig.current().photoPreviewSeconds ? lookedAt : null;
		if (shown != null && !PhotoAlbum.INSTANCE.has(shown)) {
			shown = null;
		}
		if (shown == null) {
			if (wanted != null) {
				show(mc, wanted);
			}
		} else if (wanted != shown) {
			out += dt / OUT_SECONDS;
			if (out >= 1) {
				shown = null;
			}
		} else {
			in = Math.min(1, in + dt / IN_SECONDS);
			out = Math.max(0, out - dt / OUT_SECONDS);
		}
	}

	/**
	 * drawn with what is on the screen of the player, which is not in the picture of the camera
	 */
	public void draw(GuiGraphics graphics) {
		final PhotoSheet sheet = shown;
		if (sheet == null) {
			return;
		}
		final int screenWidth = graphics.guiWidth();
		final int screenHeight = graphics.guiHeight();
		int height = Math.round(screenHeight * HEIGHT_PART);
		int width = Math.round(height / sheet.aspect);
		final int widest = Math.round(screenWidth * WIDTH_PART);
		if (width > widest) {
			width = widest;
			height = Math.round(width * sheet.aspect);
		}
		final double slidIn = 1 - Math.pow(1 - in, 3);
		final double dropped = out * out;
		final int restY = (screenHeight - height) / 2;
		final int x = screenWidth - MARGIN - width + (int) Math.round((1 - slidIn) * (width + MARGIN + BORDER));
		final int y = restY + (int) Math.round(dropped * (screenHeight - restY + BORDER));

		graphics.fill(x - BORDER, y - BORDER, x + width + BORDER, y + height + BORDER, PAPER);
		graphics.blit(RenderPipelines.GUI_TEXTURED, sheet.texture, x, y, 0.0F, 0.0F, width, height, width, height);
		final int veil = Math.round(sheet.veil() * 255.0F);
		if (veil > 0) {
			graphics.fill(x, y, x + width, y + height, veil << 24 | 0xFFFFFF);
		}
	}

	private void show(Minecraft mc, PhotoSheet sheet) {
		shown = sheet;
		in = 0;
		out = 0;
		if (mc.player != null && CameraConfig.current().photoSounds) {
			mc.player.playSound(SoundEvents.BOOK_PAGE_TURN, 0.7F, 1.0F);
		}
	}

	/**
	 * @return the photo the player looks at, null if there is none or this is no moment to show one
	 */
	@Nullable
	private PhotoSheet lookedAt(Minecraft mc) {
		final LocalPlayer player = mc.player;
		if (player == null || !CameraConfig.current().photoPreview || mc.options.hideGui || mc.screen != null ||
				SheetReach.INSTANCE.isHolding()) {
			return null;
		}
		final float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		final Vec3 eyes = player.getEyePosition(partialTick);
		return PhotoAlbum.INSTANCE.pointedAt(eyes, player.getViewVector(partialTick),
				CameraConfig.current().photoPreviewDistance, AIM, PhotoAlbum.ANY_HAND);
	}
}
