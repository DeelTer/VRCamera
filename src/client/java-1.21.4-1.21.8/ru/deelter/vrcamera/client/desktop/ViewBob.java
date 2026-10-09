package ru.deelter.vrcamera.client.desktop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
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
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		double walk = -(player.walkDist + (player.walkDist - player.walkDistO) * partialTick) * Math.PI;
		double bob = Mth.lerp(partialTick, player.oBob, player.bob);
		double shiftX = Math.sin(walk) * bob * 0.5;
		double shiftY = -Math.abs(Math.cos(walk) * bob);
		double roll = -Math.toRadians(Math.sin(walk) * bob * 3.0);
		double nod = -Math.toRadians(Math.abs(Math.cos(walk - 0.2) * bob) * 5.0);
		double rollCos = Math.cos(roll);
		double rollSin = Math.sin(roll);
		double nodCos = Math.cos(nod);
		double nodSin = Math.sin(nod);
		Vec3 right = forward.cross(up);
		return point -> {
			Vec3 to = point.subtract(eye);
			double x = to.dot(right) - shiftX;
			double y = to.dot(up) - shiftY;
			double z = -to.dot(forward);
			double rolledX = x * rollCos - y * rollSin;
			double rolledY = x * rollSin + y * rollCos;
			double noddedY = rolledY * nodCos - z * nodSin;
			double noddedZ = rolledY * nodSin + z * nodCos;
			return eye.add(right.scale(rolledX)).add(up.scale(noddedY)).add(forward.scale(-noddedZ));
		};
	}
}
