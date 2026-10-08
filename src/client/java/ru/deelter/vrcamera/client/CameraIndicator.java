package ru.deelter.vrcamera.client;

import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.compat.Gizmos;
import ru.deelter.vrcamera.client.compat.TextGizmo;
import ru.deelter.vrcamera.client.config.CameraConfig;

import java.util.function.UnaryOperator;

/**
 * The camera icon with the distance to the camera below it, like a waypoint: at the camera and seen through walls.
 * While the camera is out of sight the icon sticks to the edge of the view on the side the camera is on.
 */
public final class CameraIndicator {
	// the icon is left out while the camera is closer than this, in the hand or right in front of the face
	private static final double MIN_DISTANCE = 1.2;
	// further from the middle of the view than this the camera counts as out of sight
	private static final double VIEW_ANGLE = Math.toRadians(35);
	// where the icon goes while the camera is out of sight: this far in front of the face, and this far off the
	// middle of the view, a bit inside of where it would leave the view
	private static final double PINNED_DISTANCE = 0.6;
	private static final double PINNED_ANGLE = Math.toRadians(30);
	// Text scale of icon and distance per block they are away. Growing with the distance keeps them the same size
	// for the eye. Text of scale 1 is half a block tall
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
		Vec3 right = forward.cross(up);

		Vec3 toCamera = camera.subtract(head);
		double distance = toCamera.length();
		if (distance < MIN_DISTANCE * worldScale) {
			return;
		}
		double x = toCamera.dot(right);
		double y = toCamera.dot(up);
		double z = toCamera.dot(forward);
		double sideways = Math.sqrt(x * x + y * y);

		Vec3 anchor;
		if (Math.atan2(sideways, z) < VIEW_ANGLE) {
			// above the camera, to not cover it
			anchor = camera.add(up.scale(0.15 * worldScale));
		} else if (!alsoOutOfSight) {
			return;
		} else {
			// straight behind has no side, call that right
			Vec3 side = sideways < 1.0E-3 ? right : right.scale(x / sideways).add(up.scale(y / sideways));
			double depth = PINNED_DISTANCE * worldScale;
			anchor = head.add(forward.scale(depth)).add(side.scale(depth * Math.tan(PINNED_ANGLE)));
		}

		double size = CameraConfig.current().indicatorSize * anchor.distanceTo(head) * grow;
		float iconScale = (float) (ICON_SCALE * size);
		// text is drawn downwards from its position: the icon stands on the anchor, the distance hangs below it
		Vec3 iconTop = placed.apply(anchor.add(up.scale(iconScale / 2.0)));
		Vec3 textTop = placed.apply(anchor.subtract(up.scale(0.2 * iconScale / 2.0)));
		Gizmos.billboardText(icon, iconTop, TextGizmo.Style.forColorAndCentered(COLOR).withScale(iconScale))
				.setAlwaysOnTop();
		// in blocks, the world scale of Vivecraft changes the size of the player and not of the world
		Gizmos.billboardText((name.isEmpty() ? "" : name + "  ") + Math.round(distance) + " M", textTop,
				TextGizmo.Style.forColorAndCentered(COLOR).withScale((float) (TEXT_SCALE * size))).setAlwaysOnTop();
	}
}
