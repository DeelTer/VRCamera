package ru.deelter.vrcamera.client.desktop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.function.UnaryOperator;

/**
 * With view bobbing on, the game sways the whole world in front of the eyes of a player who walks. An icon that is
 * read like a part of the screen sways with it, and should not.
 */
final class ViewBob {

	private ViewBob() {
	}

	/**
	 * @param eye where the game looks from, and which way and how it is turned
	 * @return where to draw something for it to be seen where it is, with the sway of this frame taken back
	 */
	static UnaryOperator<Vec3> steady(Minecraft mc, LocalPlayer player, Vec3 eye, Vec3 forward, Vec3 up) {
		if (!mc.options.bobView().get()) {
			return UnaryOperator.identity();
		}

		final float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		final double walk = player.avatarState().getBackwardsInterpolatedWalkDistance(partialTick) * Math.PI;
		final double bob = player.avatarState().getInterpolatedBob(partialTick);
		final double shiftX = Math.sin(walk) * bob * 0.5;
		final double shiftY = -Math.abs(Math.cos(walk) * bob);
		final double roll = -Math.toRadians(Math.sin(walk) * bob * 3.0);
		final double nod = -Math.toRadians(Math.abs(Math.cos(walk - 0.2) * bob) * 5.0);
		final double rollCos = Math.cos(roll);
		final double rollSin = Math.sin(roll);
		final double nodCos = Math.cos(nod);
		final double nodSin = Math.sin(nod);
		final Vec3 right = forward.cross(up);
		return point -> {
			final Vec3 to = point.subtract(eye);
			final double x = to.dot(right) - shiftX;
			final double y = to.dot(up) - shiftY;
			final double z = -to.dot(forward);
			final double rolledX = x * rollCos - y * rollSin;
			final double rolledY = x * rollSin + y * rollCos;
			final double noddedY = rolledY * nodCos - z * nodSin;
			final double noddedZ = rolledY * nodSin + z * nodCos;
			return eye.add(right.scale(rolledX)).add(up.scale(noddedY)).add(forward.scale(-noddedZ));
		};
	}
}
