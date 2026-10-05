package ru.deelter.vrcamera.client.rig;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.vivecraft.client_vr.VRData;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.math.SmoothAngle;
import ru.deelter.vrcamera.client.math.SmoothVec;

/**
 * per frame snapshot of the player the camera films
 */
public final class Subject {
	public LocalPlayer player;
	/**
	 * how far the current frame is between two game ticks, to get where entities are drawn
	 */
	public float partialTick;
	public Vec3 feet = Vec3.ZERO;
	public Vec3 head = Vec3.ZERO;
	public Vec3 headDir = new Vec3(0, 0, 1);
	/**
	 * point the camera aims at and orbits around
	 */
	public Vec3 center = Vec3.ZERO;
	/**
	 * middle between both hands
	 */
	public Vec3 hands = Vec3.ZERO;
	/**
	 * size of the player, 1 is a regular player. All camera distances are multiplied by this
	 */
	public double unit = 1.0;
	/**
	 * smoothed body yaw in radians
	 */
	public double facing;
	/**
	 * smoothed velocity in blocks per second
	 */
	public Vec3 velocity = Vec3.ZERO;
	public double speed;
	/**
	 * the player moved further than they could have, cameras need to jump
	 */
	public boolean teleported;

	/**
	 * middle of the inventory or chest menu the player has open, null without one
	 */
	public Vec3 guiCenter;

	public Entity target;
	/**
	 * middle of the {@link #target}, null without one
	 */
	public Vec3 targetCenter;

	private final SmoothVec velocitySmooth = new SmoothVec();
	private final SmoothAngle facingSmooth = new SmoothAngle();
	private boolean first = true;

	public void reset() {
		this.first = true;
		this.target = null;
		this.targetCenter = null;
		this.guiCenter = null;
	}

	/**
	 * @param dt     seconds since the last update, limited to a sane step size
	 * @param realDt actual seconds since the last update
	 */
	public void update(
			LocalPlayer player, VRData vr, float partialTick, double dt, double realDt, CameraConfig config) {
		this.player = player;
		this.partialTick = partialTick;
		Vec3 newFeet = player.getPosition(partialTick);
		this.unit = Math.max(0.05, player.getScale());

		// Vivecraft teleports move the player in a single frame, nothing a player does is faster than 70 blocks/s
		this.teleported = this.first || newFeet.distanceTo(this.feet) > Math.max(1.0, 70.0 * realDt);
		if (this.teleported) {
			this.velocitySmooth.reset(Vec3.ZERO);
			this.velocity = Vec3.ZERO;
		} else if (dt > 0) {
			this.velocity = this.velocitySmooth.update(newFeet.subtract(this.feet).scale(1.0 / dt), 0.25, dt);
		}
		this.speed = this.velocity.length();
		this.feet = newFeet;

		this.head = vr.hmd.getPosition();
		this.headDir = new Vec3(vr.hmd.getDirection());
		// the headset should be right above the player, if it isn't, something is off and the entity is the safer bet
		if (this.head.distanceTo(newFeet) > 4.0 * this.unit + 2.0) {
			this.head = player.getEyePosition(partialTick);
		}
		this.center = this.feet.lerp(this.head, config.aimHeight);
		this.hands = vr.getController(0).getPosition().lerp(vr.getController(1).getPosition(), 0.5);

		double bodyYaw = vr.getBodyYawRad();
		if (this.teleported) {
			this.facingSmooth.reset(bodyYaw);
		} else {
			this.facingSmooth.update(bodyYaw, 0.3, dt);
		}
		this.facing = this.facingSmooth.get();

		if (this.target != null && (!this.target.isAlive() || this.target.level() != player.level())) {
			this.target = null;
		}
		this.targetCenter = this.target == null ? null :
				this.target.getPosition(partialTick).add(0, this.target.getBbHeight() * 0.5, 0);
		this.first = false;
	}
}
