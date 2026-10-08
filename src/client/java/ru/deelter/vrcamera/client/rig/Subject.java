package ru.deelter.vrcamera.client.rig;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.JumpSteady;
import ru.deelter.vrcamera.client.math.SmoothAngle;
import ru.deelter.vrcamera.client.math.SmoothVec;

/**
 * per frame snapshot of the player the camera films
 */
public final class Subject {
	// further away than this a partner is not in the picture anymore
	private static final double PARTNER_REACH = 24.0;
	// blocks a jump takes the feet up at most
	private static final double JUMP_HEIGHT = 1.3;
	// Having landed is going up or down slower than this, in blocks per second, for longer than this: longer than
	// the top of a jump lasts, and than the one tick a player who hops on is on the ground. The camera then takes
	// about that many seconds to come along
	private static final double SETTLED_SPEED = 0.3;
	private static final double SETTLE_AFTER = 0.12;
	private static final double GROUND_LAG = 0.15;
	// A jump follows another one if the player was on the ground for no longer than this in between. And the
	// seconds it takes to go over from going along with jumps to holding still, and back
	private static final double HOP_GAP = 0.3;
	private static final double HOP_EASE = 0.3;
	// seconds it takes to go over from one who jumps to one who flies or climbs, and back
	private static final double LOOSE_TIME = 0.4;
	private final SmoothVec velocitySmooth = new SmoothVec();
	private final SmoothAngle facingSmooth = new SmoothAngle();
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
	private boolean first = true;
	// The height a player at a screen is filmed from while they jump: where it belongs, where it is on its way
	// there and how fast it goes. How far above it they are, and for how long they stayed at one height
	private double rest;
	private double ground;
	private double groundSpeed;
	private double hop;
	private double settled;
	private double lastY;
	// how many jumps followed one another, how long the player is on the ground since the last one, and from 0 for
	// a camera that goes along with jumps to 1 for one that holds still
	private int hops;
	private double groundTime;
	private boolean wasGrounded;
	private double hopping;
	// from 0 for one who jumps to 1 for one who does not, on its way between the two
	private double loose;

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
		this.hop = hop(player, dt, config.jumpSteady);
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
	 * A player at a screen gets around in jumps, one right after the other, and a camera that goes up and down
	 * with every one of them is hard to watch. From the second jump in a row on the camera films them from the
	 * height they jump off: they jump in its picture. It goes to another height once they stay on it, and where no
	 * jump goes: further up, or down. Never in a jolt, it has a weight.
	 * <p>
	 * A single jump it goes along with, like it always did. That one is meant to be seen.
	 *
	 * @return how far above the height they are filmed from the feet are right now. Nothing for whoever does not
	 * jump but flies, swims, climbs or rides: the camera goes with them, and takes a moment to go over to that
	 */
	private double hop(Player player, double dt, JumpSteady steady) {
		double y = this.feet.y;
		// how fast the feet go in this very frame: the speed the camera is told of is evened out, and slow to
		// notice a landing
		double rising = this.teleported || dt <= 0 ? 0 : (y - this.lastY) / dt;
		this.lastY = y;
		if (this.teleported) {
			this.rest = y;
			this.ground = y;
			this.groundSpeed = 0;
			this.settled = 0;
			return 0;
		}
		boolean jumps = !player.isFallFlying() && !player.isPassenger() && !player.isInWater() &&
				!player.onClimbable() && !player.getAbilities().flying;
		this.loose = Math.clamp(this.loose + (jumps ? -dt : dt) / LOOSE_TIME, 0.0, 1.0);
		double free = this.loose * this.loose * (3.0 - 2.0 * this.loose);
		hopsInARow(player, jumps, rising, dt, steady);
		double held = (1.0 - free) * this.hopping * this.hopping * (3.0 - 2.0 * this.hopping);
		double feetSpeed = this.velocity.y;
		double reach = JUMP_HEIGHT * this.unit;
		this.settled = jumps && Math.abs(rising) < SETTLED_SPEED ? this.settled + dt : 0;
		// where the height to film from belongs: it turns corners, which the camera must not
		this.rest = !jumps || this.settled > SETTLE_AFTER ? y : Math.clamp(this.rest, y - reach, y);
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
		this.velocity = new Vec3(this.velocity.x, feetSpeed + (this.groundSpeed - feetSpeed) * held,
				this.velocity.z);
		return (y - this.ground) * held;
	}

	/**
	 * counts the jumps that follow one another without a pause, and goes over to holding the camera still once
	 * there are two of them
	 */
	private void hopsInARow(Player player, boolean jumps, double rising, double dt, JumpSteady steady) {
		boolean grounded = player.onGround();
		if (!jumps) {
			this.hops = 0;
		} else if (grounded) {
			this.groundTime += dt;
			if (this.groundTime > HOP_GAP) {
				this.hops = 0;
			}
		} else {
			// off the ground and going up is a jump, off the ground and going down is a step off an edge
			if (this.wasGrounded && rising > 1.0) {
				this.hops++;
			}
			this.groundTime = 0;
		}
		this.wasGrounded = grounded;
		boolean still = steady == JumpSteady.ALWAYS || (steady == JumpSteady.SERIES && this.hops >= 2);
		double wanted = still ? 1.0 : 0.0;
		this.hopping += Math.clamp(wanted - this.hopping, -dt / HOP_EASE, dt / HOP_EASE);
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
