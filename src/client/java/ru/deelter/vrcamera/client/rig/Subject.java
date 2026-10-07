package ru.deelter.vrcamera.client.rig;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.math.SmoothAngle;
import ru.deelter.vrcamera.client.math.SmoothVec;

/**
 * per frame snapshot of the player the camera films
 */
public final class Subject {
	public Player player;
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
	 * if it is known where the hands are. Without VR it is not, and nothing is filmed for them
	 */
	public boolean tracksHands = true;
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
	 * someone to have in the picture next to the player while there is no fight: a friend they film with. Null for
	 * no one
	 */
	public Entity partner;
	/**
	 * middle of the {@link #target}, null without one
	 */
	public Vec3 targetCenter;

	// further away than this a partner is not in the picture anymore
	private static final double PARTNER_REACH = 24.0;

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
	 * A player at a screen, from what the game knows of them. For one in VR see {@link VrSubject}
	 *
	 * @param dt     seconds since the last update, limited to a sane step size
	 * @param realDt actual seconds since the last update
	 */
	public void updateWithoutVR(Player player, float partialTick, double dt, double realDt, CameraConfig config) {
		move(player, partialTick, dt, realDt);
		this.head = player.getEyePosition(partialTick);
		this.headDir = player.getViewVector(partialTick);
		this.center = this.feet.lerp(this.head, config.aimHeight);
		this.hands = this.center;
		this.tracksHands = false;
		// a menu on a screen is nowhere in the world
		this.guiCenter = null;
		turn(player, partialTick, Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot) * Mth.DEG_TO_RAD, dt);
	}

	Vec3 move(Player player, float partialTick, double dt, double realDt) {
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
		return newFeet;
	}

	void turn(Player player, float partialTick, double bodyYaw, double dt) {
		if (this.teleported) {
			this.facingSmooth.reset(bodyYaw);
		} else {
			this.facingSmooth.update(bodyYaw, 0.3, dt);
		}
		this.facing = this.facingSmooth.get();

		if (this.target != null && (!this.target.isAlive() || this.target.level() != player.level())) {
			this.target = null;
		}
		// whoever they fight comes first, a partner is for the quiet moments
		Entity framed = this.target;
		if (framed == null && this.partner != null && this.partner.isAlive() &&
				this.partner.level() == player.level() && this.partner.distanceTo(player) <= PARTNER_REACH) {
			framed = this.partner;
		}
		this.targetCenter = framed == null ? null :
				framed.getPosition(partialTick).add(0, framed.getBbHeight() * 0.5, 0);
		this.first = false;
	}
}
