package ru.deelter.vrcamera.client;

import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.config.CameraConfig;

import java.util.function.UnaryOperator;

/**
 * The camera icon with the distance to the camera below it, like a waypoint: at the camera and seen through walls.
 * While the camera is out of sight the icon sticks to the edge of the view on the side the camera is on.
 */
public final class CameraIndicator {

	/**
	 * what marks a photo that lies around, from the font of the mod
	 */
	public static final String PHOTO_ICON = "";
	private static final double MIN_DISTANCE = 1.2;
	private static final double VIEW_ANGLE = Math.toRadians(35);
	private static final double PINNED_DISTANCE = 0.6;
	private static final double PINNED_ANGLE = Math.toRadians(30);
	private static final double ICON_SCALE = 0.11;
	private static final double TEXT_SCALE = 0.05;
	private static final int COLOR = 0xFFFFFFFF;

	private CameraIndicator() {
	}

	/**
	 * Called while the game collects gizmos for a pass.
	 *
	 * @param name       what the camera is called, in front of the distance to it. Empty for none
	 * @param head       where it is looked from, and which way and how that is turned
	 * @param worldScale how large the player is, as Vivecraft has it
	 * @param grow       how many times larger than usual it is drawn
	 * @param placed     where to draw what should be seen at a place, for a view that is not the one it is drawn in
	 */
	public static void draw(
			String icon, String name, Vec3 camera, Vec3 head, Vec3 forward, Vec3 up, float worldScale,
			boolean alsoOutOfSight, double grow, UnaryOperator<Vec3> placed) {
		final Vec3 right = forward.cross(up);

		final Vec3 toCamera = camera.subtract(head);
		final double distance = toCamera.length();
		if (distance < MIN_DISTANCE * worldScale) {
			return;
		}
		final double x = toCamera.dot(right);
		final double y = toCamera.dot(up);
		final double z = toCamera.dot(forward);
		final double sideways = Math.sqrt(x * x + y * y);

		Vec3 anchor;
		if (Math.atan2(sideways, z) < VIEW_ANGLE) {

			anchor = camera.add(up.scale(0.15 * worldScale));
		} else if (!alsoOutOfSight) {
			return;
		} else {

			final Vec3 side = sideways < 1.0E-3 ? right : right.scale(x / sideways).add(up.scale(y / sideways));
			final double depth = PINNED_DISTANCE * worldScale;
			anchor = head.add(forward.scale(depth)).add(side.scale(depth * Math.tan(PINNED_ANGLE)));
		}

		final double size = CameraConfig.current().indicatorSize * anchor.distanceTo(head) * grow;
		final float iconScale = (float) (ICON_SCALE * size);

		final Vec3 iconTop = placed.apply(anchor.add(up.scale(iconScale / 2.0)));
		final Vec3 textTop = placed.apply(anchor.subtract(up.scale(0.2 * iconScale / 2.0)));
		Gizmos.billboardText(icon, iconTop, TextGizmo.Style.forColorAndCentered(COLOR).withScale(iconScale))
				.setAlwaysOnTop();

		Gizmos.billboardText((name.isEmpty() ? "" : name + "  ") + Math.round(distance) + " M", textTop,
				TextGizmo.Style.forColorAndCentered(COLOR).withScale((float) (TEXT_SCALE * size))).setAlwaysOnTop();
	}
}
