package ru.deelter.vrcamera.client.rig;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.vivecraft.client_vr.VRData;

import ru.deelter.vrcamera.client.config.CameraConfig;

/**
 * a player in VR as the shots see them: where their headset and their hands are, not where the game has them
 */
public final class VrSubject {

	private VrSubject() {
	}

	/**
	 * @param dt     seconds since the last update, limited to a sane step size
	 * @param realDt actual seconds since the last update
	 */
	public static void update(
			Subject subject, LocalPlayer player, VRData vr, float partialTick, double dt, double realDt,
			CameraConfig config) {
		final Vec3 newFeet = subject.move(player, partialTick, dt, realDt);

		subject.head = vr.hmd.getPosition();
		subject.headDir = new Vec3(vr.hmd.getDirection());

		if (subject.head.distanceTo(newFeet) > 4.0 * subject.unit + 2.0) {
			subject.head = player.getEyePosition(partialTick);
		}
		subject.center = subject.feet.lerp(subject.head, config.aimHeight);
		subject.hands = vr.getController(0).getPosition().lerp(vr.getController(1).getPosition(), 0.5);
		subject.tracksHands = true;

		subject.turn(player, partialTick, vr.getBodyYawRad(), dt);
	}
}
