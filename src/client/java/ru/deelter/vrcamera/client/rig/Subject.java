package ru.deelter.vrcamera.client.rig;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.JumpSteady;
import ru.deelter.vrcamera.client.math.Smooth;
import ru.deelter.vrcamera.client.math.SmoothAngle;
import ru.deelter.vrcamera.client.math.SmoothVec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * per frame snapshot of the player the camera films
 */
public final class Subject {
	private static final double PARTNER_REACH = 24.0;
	/**
	 * how many of the nearest others are filmed together with the player at most, and the seconds it takes to
	 * go over to a shot of all of them and back
	 */
	private static final int CROWD_MOST = 4;
	private static final double CROWD_LAG = 1.2;
	/**
	 * blocks a drawn bow is looked along for what it aims at, and the seconds the camera takes to turn to that
	 */
	private static final double AIM_REACH = 64.0;
	private static final double AIM_LAG = 0.4;
	private static final Set<String> AIMING = Set.of("BOW", "CROSSBOW", "SPEAR", "TRIDENT");
	private static final double JUMP_HEIGHT = 1.3;
	private static final double SETTLED_SPEED = 0.3;
	private static final double SETTLE_AFTER = 0.12;
	private static final double GROUND_LAG = 0.15;
	private static final double HOP_GAP = 0.3;
	private static final double HOP_EASE = 0.3;
	private static final double LOOSE_TIME = 0.4;
	private final SmoothVec velocitySmooth = new SmoothVec();
	private final SmoothAngle facingSmooth = new SmoothAngle();
	private final SmoothVec crowdSmooth = new SmoothVec();
	private final Smooth crowdSpreadSmooth = new Smooth();
	private final Smooth crowdWeightSmooth = new Smooth();
	private final Smooth aimSmooth = new Smooth();
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
	 * if the player is at a screen and not in VR
	 */
	public boolean atScreen;
	/**
	 * if the camera shows the player through what is in the way, and has no need to keep clear of it
	 */
	public boolean seenThrough;
	/**
	 * if blocks like leaves are drawn see-through for the camera, and are not in its way
	 */
	public boolean softBlocks;
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
	/**
	 * halfway between the player and the middle of the others close to them, so the player is never pushed to the
	 * edge by a crowd, how far they are all spread around it, and how much
	 * the camera films them together right now: from 0, the player alone, to 1. It goes over to that softly,
	 * people come and go
	 */
	public Vec3 crowd = Vec3.ZERO;
	public double crowdSpread;
	public double crowdWeight;
	/**
	 * if the player draws a bow, a crossbow or a trident, or holds a loaded crossbow. What they aim at then, the
	 * first block or entity on their line of sight, and how much the camera turns to it right now, from 0 to 1
	 */
	public boolean aiming;
	public Vec3 aimTarget = Vec3.ZERO;
	public double aimWeight;
	private boolean first = true;
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
		atScreen = true;

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
	 * Finds out if the player aims with a bow, a crossbow or a trident, and at what
	 *
	 * @param wanted if the camera is to turn to what they aim at
	 */
	public void aim(boolean wanted, double dt) {
		aiming = wanted && isAiming(player);
		if (aiming) {
			aimTarget = aimedAt();
		}
		aimWeight = aimSmooth.update(aiming ? 1.0 : 0.0, AIM_LAG, dt);
	}

	private static boolean isAiming(Player player) {
		if (player.isUsingItem() && AIMING.contains(player.getUseItem().getUseAnimation().name())) {
			return true;
		}
		final ItemStack held = player.getMainHandItem();
		return held.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(held);
	}

	/**
	 * @return the first block or entity on the line of sight of the player, or the end of it
	 */
	private Vec3 aimedAt() {
		final Vec3 eyes = player.getEyePosition(partialTick);
		final Vec3 end = eyes.add(player.getViewVector(partialTick).scale(AIM_REACH));
		final BlockHitResult wall = player.level().clip(new ClipContext(eyes, end, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, player));
		Vec3 hit = wall.getType() == HitResult.Type.MISS ? end : wall.getLocation();
		for (final Entity entity : player.level().getEntities(player, new AABB(eyes, hit).inflate(1.0),
				entity -> entity.isPickable() && !entity.isSpectator())) {
			final Vec3 on = entity.getBoundingBox().inflate(0.3).clip(eyes, hit).orElse(null);
			if (on != null) {
				hit = on;
			}
		}
		return hit;
	}

	/**
	 * Finds the others the player stands with: the nearest few in a circle around them. Not while the
	 * camera already films the player with someone, a partner or a foe
	 *
	 * @param wanted if a shot of the player is to show the others around them too
	 * @param reach  blocks around the player in which others are
	 */
	public void gather(boolean wanted, double reach, double dt) {
		final List<Vec3> around = new ArrayList<>();
		if (wanted && partner == null && target == null) {
			player.level().players().stream()
					.filter(other -> other != player && other.isAlive() && !other.isSpectator() &&
							!other.isInvisible() && other.distanceTo(player) < reach * unit)
					.sorted(Comparator.comparingDouble(other -> other.distanceToSqr(player)))
					.limit(CROWD_MOST)
					.forEach(other -> around.add(other.getPosition(partialTick).add(0, other.getBbHeight() * 0.5, 0)));
		}
		Vec3 others = Vec3.ZERO;
		for (final Vec3 other : around) {
			others = others.add(other);
		}
		final Vec3 middle = around.isEmpty() ? center : center.lerp(others.scale(1.0 / around.size()), 0.5);
		double spread = middle.distanceTo(center);
		for (final Vec3 other : around) {
			spread = Math.max(spread, middle.distanceTo(other));
		}
		if (teleported) {
			crowdSmooth.reset(middle.subtract(center));
			crowdSpreadSmooth.reset(spread);
		}
		crowdWeight = crowdWeightSmooth.update(around.isEmpty() ? 0.0 : 1.0, CROWD_LAG, dt);
		crowd = center.add(crowdSmooth.update(middle.subtract(center), CROWD_LAG, dt));
		crowdSpread = crowdSpreadSmooth.update(spread, CROWD_LAG, dt);
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
			final double pull = 2.0 / GROUND_LAG;
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
