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

	// blocks a jump takes the feet up at most
	private static final double JUMP_HEIGHT = 1.3;
	// Staying at a height is going up or down slower than this, in blocks per second, for longer than this. The
	// camera then takes about that many seconds to come along
	private static final double SETTLED_SPEED = 0.5;
	private static final double SETTLE_AFTER = 0.15;
	private static final double GROUND_LAG = 0.25;
	// The height a player at a screen is filmed from while they jump: where it belongs, where it is on its way
	// there and how fast it goes. How far above it they are, and for how long they stayed at one height
	private double rest;
	private double ground;
	private double groundSpeed;
	private double hop;
	private double settled;

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
		// the feet where they really were: how fast they go is counted from there
		this.feet = this.feet.add(0, this.hop, 0);
		move(player, partialTick, dt, realDt);
		this.hop = hop(player, dt);
		this.feet = this.feet.subtract(0, this.hop, 0);
		this.head = player.getEyePosition(partialTick).subtract(0, this.hop, 0);
		this.headDir = player.getViewVector(partialTick);
		this.center = this.feet.lerp(this.head, config.aimHeight);
		this.hands = this.center;
		this.tracksHands = false;
		// a menu on a screen is nowhere in the world
		this.guiCenter = null;
		turn(player, partialTick, Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot) * Mth.DEG_TO_RAD, dt);
	}

	/**
	 * A player at a screen gets around in jumps, and a camera that goes up and down with every one of them is hard
	 * to watch. The camera films them from the height they jump off: they jump in its picture. It goes to another
	 * height once they stay on it, and where no jump goes: further up, or down. Never in a jolt, it has a weight.
	 *
	 * @return how far above the height they are filmed from the feet are right now, 0 for whoever does not jump
	 * but flies, swims, climbs or rides
	 */
	private double hop(Player player, double dt) {
		double y = this.feet.y;
		if (this.teleported || player.isFallFlying() || player.isPassenger() || player.isInWater() ||
				player.onClimbable() || player.getAbilities().flying) {
			this.rest = y;
			this.ground = y;
			this.groundSpeed = 0;
			this.settled = 0;
			return 0;
		}
		double reach = JUMP_HEIGHT * this.unit;
		this.settled = Math.abs(this.velocity.y) < SETTLED_SPEED ? this.settled + dt : 0;
		// where the height to film from belongs: it turns corners, which the camera must not
		this.rest = this.settled > SETTLE_AFTER ? y : Math.clamp(this.rest, y - reach, y);
		if (dt > 0) {
			// comes after it like something heavy does, without a jolt at either end of the way
			double pull = 2.0 / GROUND_LAG;
			double step = pull * dt;
			double fade = 1.0 / (1.0 + step + 0.48 * step * step + 0.235 * step * step * step);
			double off = this.ground - this.rest;
			double carried = (this.groundSpeed + pull * off) * dt;
			this.groundSpeed = (this.groundSpeed - pull * carried) * fade;
			this.ground = this.rest + (off + carried) * fade;
		}
		// in a long fall it does not stay behind by more than the picture has room for
		this.ground = Math.clamp(this.ground, y - reach, y + reach);
		// the camera is told how fast the height it films from moves, not how fast the feet do
		this.velocity = new Vec3(this.velocity.x, this.groundSpeed, this.velocity.z);
		return y - this.ground;
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
