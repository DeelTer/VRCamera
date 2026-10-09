package ru.deelter.vrcamera.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Text and dots in the world that face whoever looks at them. Newer versions of the game have this built in, under
 * the same names: here it is done by hand, to leave the code that draws with it the same.
 * <p>
 * What is asked for is kept until the picture it is for is drawn, see {@link #render}.
 */
public final class Gizmos {
	private static final float PIXEL = 0.5F / 9.0F;
	private static final String DOT = "●";
	private static final int FULL_LIGHT = 0xF000F0;
	private static final List<Text> TEXTS = new ArrayList<>();

	private Gizmos() {
	}

	/**
	 * @param at where the top of the text is, in the middle of it
	 */
	public static Text billboardText(String text, Vec3 at, TextGizmo.Style style) {
		Text added = new Text(text, at, style);
		TEXTS.add(added);
		return added;
	}

	public static void point(Vec3 at, int color, float size) {
		float scale = size * 0.05F;
		TEXTS.add(new Text(DOT, at.add(0, scale * 0.25, 0), TextGizmo.Style.forColorAndCentered(color)
				.withScale(scale)));
	}

	/**
	 * draws everything that was asked for since the last time, into the picture that is being drawn
	 *
	 * @param poseStack relative to where the camera is
	 */
	public static void render(PoseStack poseStack, MultiBufferSource buffers, Camera camera) {
		Font font = Minecraft.getInstance().font;
		Vec3 from = camera.getPosition();
		for (Text text : TEXTS) {
			float scale = PIXEL * text.style.scale();
			poseStack.pushPose();
			poseStack.translate(text.at.x - from.x, text.at.y - from.y, text.at.z - from.z);
			poseStack.mulPose(camera.rotation());
			poseStack.scale(scale, -scale, scale);
			font.drawInBatch(text.text, -font.width(text.text) / 2.0F, 0, text.style.color(), false,
					poseStack.last().pose(), buffers,
					text.alwaysOnTop ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, 0, FULL_LIGHT);
			poseStack.popPose();
		}
		TEXTS.clear();
	}

	/**
	 * forgets what was asked for, for a picture it is not meant to be in
	 */
	public static void discard() {
		TEXTS.clear();
	}

	/**
	 * a text in the world, as it was asked for
	 */
	public static final class Text {
		private final String text;
		private final Vec3 at;
		private final TextGizmo.Style style;
		private boolean alwaysOnTop;

		private Text(String text, Vec3 at, TextGizmo.Style style) {
			this.text = text;
			this.at = at;
			this.style = style;
		}

		/**
		 * seen through walls
		 */
		public Text setAlwaysOnTop() {
			this.alwaysOnTop = true;
			return this;
		}
	}
}
