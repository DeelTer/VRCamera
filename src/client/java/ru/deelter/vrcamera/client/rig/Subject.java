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

	private static final double PARTNER_REACH = 24.0;

	private final SmoothVec velocitySmooth = new SmoothVec();
	private final SmoothAngle facingSmooth = new SmoothAngle();
	private boolean first = true;

	private static final double JUMP_HEIGHT = 1.3;

	private static final double SETTLED_SPEED = 0.3;
	private static final double SETTLE_AFTER = 0.12;
	private static final double GROUND_LAG = 0.15;

	private static final double HOP_GAP = 0.3;
	private static final double HOP_EASE = 0.3;

	private static final double LOOSE_TIME = 0.4;

	private double rest;
	private double ground;
	private double groundSpeed;
	private double hop;
	private double settled;
	private double lastY;

	private int hops;
	private double groundTime;
	private boolean wasGrounded;
	private double hopping;

	private double loose;

	public void reset() {
		first = true;
		target = null;
		targetCenter = null;
		guiCenter = null;
	}

	/**
	 * A player at a screen, from what the game knows of them. For one in VR see {@link VrSubject}
	 *
	 * @param dt     seconds since the last update, limited to a sane step size
	 * @param realDt actual seconds since the last update
	 */
	public void updateWithoutVR(Player player, float partialTick, double dt, double realDt, CameraConfig config) {

		feet = feet.add(0, hop, 0);
		move(player, partialTick, dt, realDt);
		hop = hop(player, dt, config.jumpSteady);
		feet = feet.subtract(0, hop, 0);
		head = player.getEyePosition(partialTick).subtract(0, hop, 0);
		headDir = player.getViewVector(partialTick);
		center = feet.lerp(head, config.aimHeight);
		hands = center;
		tracksHands = false;

		guiCenter = null;
		turn(player, partialTick, Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot) * Mth.DEG_TO_RAD, dt);
	}

	Vec3 move(Player player, float partialTick, double dt, double realDt) {
		this.player = player;
		this.partialTick = partialTick;
		final Vec3 newFeet = player.getPosition(partialTick);
		unit = Math.max(0.05, player.getScale());

		teleported = first || newFeet.distanceTo(feet) > Math.max(1.0, 70.0 * realDt);
		if (teleported) {
			velocitySmooth.reset(Vec3.ZERO);
			velocity = Vec3.ZERO;
		} else if (dt > 0) {
			velocity = velocitySmooth.update(newFeet.subtract(feet).scale(1.0 / dt), 0.25, dt);
		}
		speed = velocity.length();
		feet = newFeet;
		return newFeet;
	}

	void turn(Player player, float partialTick, double bodyYaw, double dt) {
		if (teleported) {
			facingSmooth.reset(bodyYaw);
		} else {
			facingSmooth.update(bodyYaw, 0.3, dt);
		}
		facing = facingSmooth.get();

		if (target != null && (!target.isAlive() || target.level() != player.level())) {
			target = null;
		}

		Entity framed = target;
		if (framed == null && partner != null && partner.isAlive() &&
				partner.level() == player.level() && partner.distanceTo(player) <= PARTNER_REACH) {
			framed = partner;
		}
		targetCenter = framed == null ? null :
				framed.getPosition(partialTick).add(0, framed.getBbHeight() * 0.5, 0);
		first = false;
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
		final double y = feet.y;

		final double rising = teleported || dt <= 0 ? 0 : (y - lastY) / dt;
		lastY = y;
		if (teleported) {
			rest = y;
			ground = y;
			groundSpeed = 0;
			settled = 0;
			return 0;
		}
		final boolean jumps = !player.isFallFlying() && !player.isPassenger() && !player.isInWater() &&
				!player.onClimbable() && !player.getAbilities().flying;
		loose = Math.clamp(loose + (jumps ? -dt : dt) / LOOSE_TIME, 0.0, 1.0);
		final double free = loose * loose * (3.0 - 2.0 * loose);
		hopsInARow(player, jumps, rising, dt, steady);
		final double held = (1.0 - free) * hopping * hopping * (3.0 - 2.0 * hopping);
		final double feetSpeed = velocity.y;
		final double reach = JUMP_HEIGHT * unit;
		settled = jumps && Math.abs(rising) < SETTLED_SPEED ? settled + dt : 0;

		rest = !jumps || settled > SETTLE_AFTER ? y : Math.clamp(rest, y - reach, y);
		if (dt > 0) {

			double pull = 2.0 / GROUND_LAG;
			final double step = pull * dt;
			final double fade = 1.0 / (1.0 + step + 0.48 * step * step + 0.235 * step * step * step);
			final double off = ground - rest;
			final double carried = (groundSpeed + pull * off) * dt;
			groundSpeed = (groundSpeed - pull * carried) * fade;
			ground = rest + (off + carried) * fade;
		}

		ground = Math.clamp(ground, y - reach, y + reach);

		velocity = new Vec3(velocity.x, feetSpeed + (groundSpeed - feetSpeed) * held,
				velocity.z);
		return (y - ground) * held;
	}

	/**
	 * counts the jumps that follow one another without a pause, and goes over to holding the camera still once
	 * there are two of them
	 */
	private void hopsInARow(Player player, boolean jumps, double rising, double dt, JumpSteady steady) {
		final boolean grounded = player.onGround();
		if (!jumps) {
			hops = 0;
		} else if (grounded) {
			groundTime += dt;
			if (groundTime > HOP_GAP) {
				hops = 0;
			}
		} else {

			if (wasGrounded && rising > 1.0) {
				hops++;
			}
			groundTime = 0;
		}
		wasGrounded = grounded;
		final boolean still = steady == JumpSteady.ALWAYS || (steady == JumpSteady.SERIES && hops >= 2);
		final double wanted = still ? 1.0 : 0.0;
		hopping += Math.clamp(wanted - hopping, -dt / HOP_EASE, dt / HOP_EASE);
	}
}
