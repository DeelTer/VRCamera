package ru.deelter.vrcamera.client.rig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.math.CamMath;

import java.util.Optional;
import java.util.Random;

/**
 * A camera that was let go of. Falls, bounces off blocks and entities and comes to rest on the ground, or on an entity
 * like a boat, which then carries it along. Entities that walk into it kick it away.
 * <p>
 * Not real physics: where it ends up is simulated, but how it ends up lying there is not. It turns its lens towards
 * the player while it comes to rest, so what is filmed after a drop is still worth watching.
 */
public final class DroppedCamera {
	// blocks per second squared
	private static final double GRAVITY = 16.0;
	// size of the camera, half of it, in blocks
	private static final double RADIUS = 0.1;
	// part of the speed that is left after bouncing off something, straight back and along the surface
	private static final double BOUNCE = 0.35;
	private static final double FRICTION = 0.55;
	// slower than this on the ground it stops, blocks per second
	private static final double REST_SPEED = 0.9;
	// speed lost per second in the air and in a fluid, as an exponent
	private static final double AIR_DRAG = 0.2;
	private static final double FLUID_DRAG = 3.0;
	// it sinks, but slowly
	private static final double FLUID_GRAVITY = 0.15;
	// seconds it still turns to the player after it stopped moving
	private static final double SETTLE_TIME = 1.5;
	// radians per second of tumbling per block per second of speed
	private static final double SPIN = 2.5;

	// slower hits than this are not worth showing, blocks per second
	private static final double IMPACT_MIN_SPEED = 1.0;
	// the view jolts on a hit: degrees per block per second of the hit, most of it, how fast it is over and swings
	private static final double FOV_KICK = 1.5;
	private static final double FOV_KICK_MAX = 12.0;
	private static final double FOV_KICK_TIME = 0.2;
	private static final double FOV_KICK_FREQUENCY = 3.5;

	// something has to move this fast to kick the camera away, blocks per second
	private static final double KICK_MIN_SPEED = 1.5;
	// upwards speed of a kicked camera
	private static final double KICK_LIFT = 2.2;
	// seconds a kicked camera passes through what kicked it, or it would land on its head
	private static final double KICK_IGNORE_TIME = 0.6;

	/**
	 * the camera ran into something
	 *
	 * @param speed how fast it hit, in blocks per second
	 * @param block the block that was hit, null if it was an entity
	 */
	public record Impact(Vec3 position, Vec3 normal, double speed, BlockPos block) {
	}

	/**
	 * @param entity the entity that was hit, null for a block
	 */
	private record Hit(Vec3 location, Vec3 normal, Entity entity) {
	}

	private final Random random = new Random();

	private Vec3 position = Vec3.ZERO;
	private Vec3 velocity = Vec3.ZERO;
	private final Quaternionf rotation = new Quaternionf();
	private boolean falling;
	private boolean resting;
	private double settleTime;

	// tumbling in the air
	private final Vector3f spinAxis = new Vector3f(1, 0, 0);
	private double spinSpeed;
	// how it would lie on the ground if it did not care about the player, different on every drop
	private final Quaternionf randomRest = new Quaternionf();

	// the entity the camera is lying on, null on the ground. Where on it, and how the entity was turned when it landed
	private Entity carrier;
	private Vec3 carrierOffset = Vec3.ZERO;
	private float carrierYaw;
	private float carriedTurn;

	// what kicked the camera, it does not collide with that for a moment
	private Entity kicker;
	private double kickIgnoreTime;

	private Impact impact;
	private double fovKick;
	private double fovKickAge;

	public Vec3 position() {
		return this.position;
	}

	public Quaternionf rotation() {
		return this.rotation;
	}

	/**
	 * @return if the camera was let go of, and is falling or lying somewhere
	 */
	public boolean isDropped() {
		return this.falling || this.resting;
	}

	public boolean isResting() {
		return this.resting;
	}

	/**
	 * @return if the camera is lying on an entity that carries it along
	 */
	public boolean isCarried() {
		return this.resting && this.carrier != null;
	}

	/**
	 * @return the last hit since this was asked the last time, null if there was none
	 */
	public Impact pollImpact() {
		Impact last = this.impact;
		this.impact = null;
		return last;
	}

	/**
	 * @return degrees to add to the field of view, the jolt of a hit that swings out
	 */
	public double fovOffset() {
		return this.fovKick * Math.exp(-this.fovKickAge / FOV_KICK_TIME) *
				Math.cos(this.fovKickAge * Math.PI * 2.0 * FOV_KICK_FREQUENCY);
	}

	/**
	 * lets a camera that lies still turn to its focus once more
	 */
	public void settleAgain() {
		if (this.resting) {
			this.settleTime = SETTLE_TIME;
		}
	}

	public void pickUp() {
		this.falling = false;
		this.resting = false;
		this.carrier = null;
		this.fovKick = 0;
	}

	/**
	 * @param velocity speed of the hand when it let go, a thrown camera flies
	 */
	public void drop(Vec3 position, Quaternionfc rotation, Vec3 velocity) {
		this.position = position;
		this.velocity = velocity;
		this.rotation.set(rotation);
		this.falling = true;
		this.resting = false;
		this.carrier = null;
		this.kicker = null;
		this.randomRest.rotationYXZ(
				(float) (this.random.nextDouble() * Math.PI * 2.0),
				(float) CamMath.lerp(-0.5, 0.3, this.random.nextDouble()),
				(float) (CamMath.lerp(0.6, 1.4, this.random.nextDouble()) * (this.random.nextBoolean() ? 1 : -1)));
		tumble(Math.max(1.0, velocity.length()));
	}

	/**
	 * @param focus what the lens turns to while the camera comes to rest
	 */
	public void update(Subject subject, double dt, CameraConfig config, Vec3 focus) {
		if (dt <= 0) {
			return;
		}
		this.fovKickAge += dt;
		this.kickIgnoreTime -= dt;

		if (this.resting) {
			if (this.carrier != null) {
				ride(subject);
			} else if (!supported(subject)) {
				startFalling();
			} else {
				getKicked(subject);
			}
		}
		if (this.falling) {
			fall(subject, dt);
		}

		Quaternionf target = restRotation(focus, config);
		if (this.falling) {
			this.rotation.rotateAxis((float) (this.spinSpeed * dt), this.spinAxis);
			this.spinSpeed *= Math.exp(-0.5 * dt);
			// already starts to turn the right way in the air, so the landing is not one sudden twist
			this.rotation.slerp(target, (float) (config.physicsAim * (1.0 - Math.exp(-dt / 1.2))));
		} else if (this.settleTime > 0) {
			this.settleTime -= dt;
			this.rotation.slerp(target, (float) (1.0 - Math.exp(-dt / 0.25)));
		}
	}

	private void startFalling() {
		this.resting = false;
		this.falling = true;
		this.carrier = null;
	}

	/**
	 * moves and turns the camera with the entity it is lying on
	 */
	private void ride(Subject subject) {
		if (!this.carrier.isAlive() || this.carrier.level() != subject.player.level()) {
			startFalling();
			return;
		}
		float turn = Mth.wrapDegrees(this.carrier.getViewYRot(subject.partialTick) - this.carrierYaw);
		// yaw of entities goes the other way around than rotations around the y axis
		float radians = -turn * Mth.DEG_TO_RAD;
		this.position = this.carrier.getPosition(subject.partialTick).add(this.carrierOffset.yRot(radians));
		this.rotation.rotateLocalY(-(turn - this.carriedTurn) * Mth.DEG_TO_RAD);
		this.carriedTurn = turn;
	}

	/**
	 * a camera lying on the ground is kicked away by whatever walks into it, the player included
	 */
	private void getKicked(Subject subject) {
		AABB reach = new AABB(this.position, this.position).inflate(1.0);
		for (Entity entity : subject.player.level().getEntities((Entity) null, reach, DroppedCamera::isSolid)) {
			if (!entity.getBoundingBox().inflate(RADIUS).contains(this.position)) {
				continue;
			}
			// per tick to per second
			Vec3 motion = new Vec3(entity.getX() - entity.xo, 0, entity.getZ() - entity.zo).scale(20.0);
			double speed = motion.length();
			if (speed < KICK_MIN_SPEED) {
				continue;
			}
			this.velocity = motion.scale(1.1).add(0, KICK_LIFT, 0);
			this.kicker = entity;
			this.kickIgnoreTime = KICK_IGNORE_TIME;
			startFalling();
			tumble(speed * 1.5);
			hit(new Impact(this.position, new Vec3(0, 1, 0), speed,
					BlockPos.containing(this.position.add(0, -(RADIUS + 0.1), 0))));
			return;
		}
	}

	/**
	 * @return if the camera collides with the entity: everything with a hitbox that can be hit, so not items or arrows
	 */
	private static boolean isSolid(Entity entity) {
		return entity.isPickable() && !entity.isSpectator();
	}

	private void fall(Subject subject, double dt) {
		boolean inFluid = WorldProbe.inFluid(subject, this.position);
		double gravity = GRAVITY * (inFluid ? FLUID_GRAVITY : 1.0);
		this.velocity = this.velocity.add(0, -gravity * dt, 0)
				.scale(Math.exp(-(inFluid ? FLUID_DRAG : AIR_DRAG) * dt));

		Vec3 step = this.velocity.scale(dt);
		double distance = step.length();
		if (distance < 1.0E-6) {
			return;
		}
		// look a camera size ahead, to stop at the surface and not with the middle of the camera in it
		Vec3 ahead = this.position.add(step.scale((distance + RADIUS) / distance));
		Hit hit = trace(subject, this.position, ahead);
		if (hit == null) {
			this.position = this.position.add(step);
			if (this.position.y < subject.player.level().getMinY() - 16) {
				// fell out of the world, nothing to land on down there
				this.falling = false;
				this.resting = true;
			}
			return;
		}

		Vec3 normal = hit.normal;
		this.position = hit.location.add(normal.scale(RADIUS));

		// bounce: back along the normal with part of the speed, and slowed down along the surface
		double into = this.velocity.dot(normal);
		Vec3 along = this.velocity.subtract(normal.scale(into));
		this.velocity = along.scale(FRICTION).add(normal.scale(-into * BOUNCE));
		if (-into > IMPACT_MIN_SPEED) {
			hit(new Impact(hit.location, normal, -into,
					hit.entity != null ? null : BlockPos.containing(hit.location.subtract(normal.scale(0.05)))));
		}

		double speed = this.velocity.length();
		if (normal.y > 0.5 && speed < REST_SPEED) {
			this.velocity = Vec3.ZERO;
			this.falling = false;
			this.resting = true;
			this.settleTime = SETTLE_TIME;
			landOn(hit.entity, subject);
		} else {
			tumble(speed);
		}
	}

	private void hit(Impact impact) {
		this.impact = impact;
		// zooms in first
		this.fovKick = -Math.min(FOV_KICK * impact.speed, FOV_KICK_MAX);
		this.fovKickAge = 0;
	}

	/**
	 * @param entity what the camera came to rest on, null for a block
	 */
	private void landOn(Entity entity, Subject subject) {
		this.carrier = entity;
		if (entity != null) {
			this.carrierOffset = this.position.subtract(entity.getPosition(subject.partialTick));
			this.carrierYaw = entity.getViewYRot(subject.partialTick);
			this.carriedTurn = 0;
		}
	}

	/**
	 * @return the first thing in the way from one point to the other, null if the way is free
	 */
	private Hit trace(Subject subject, Vec3 from, Vec3 to) {
		Level level = subject.player.level();
		Hit nearest = null;
		double nearestDistance = Double.MAX_VALUE;

		BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, subject.player));
		if (block.getType() != HitResult.Type.MISS) {
			Direction face = block.getDirection();
			nearest = new Hit(block.getLocation(), new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()), null);
			nearestDistance = block.getLocation().distanceToSqr(from);
		}

		// not the player, the camera comes out of their hand
		for (Entity entity : level.getEntities(subject.player, new AABB(from, to).inflate(0.5),
				DroppedCamera::isSolid)) {
			if (entity == this.kicker && this.kickIgnoreTime > 0) {
				continue;
			}
			AABB box = entity.getBoundingBox();
			if (box.contains(from)) {
				// let go of inside of it, like in the boat the player sits in. Put it on top
				return new Hit(new Vec3(from.x, box.maxY, from.z), new Vec3(0, 1, 0), entity);
			}
			Optional<Vec3> point = box.clip(from, to);
			if (point.isPresent() && point.get().distanceToSqr(from) < nearestDistance) {
				nearestDistance = point.get().distanceToSqr(from);
				nearest = new Hit(point.get(), faceNormal(box, point.get()), entity);
			}
		}
		return nearest;
	}

	/**
	 * @return which way the side of the box points, that the point is on
	 */
	private static Vec3 faceNormal(AABB box, Vec3 point) {
		Vec3 normal = new Vec3(0, 1, 0);
		double nearest = Math.abs(point.y - box.maxY);
		double[] distances = {
				Math.abs(point.y - box.minY), Math.abs(point.x - box.minX), Math.abs(point.x - box.maxX),
				Math.abs(point.z - box.minZ), Math.abs(point.z - box.maxZ)
		};
		Vec3[] normals = {
				new Vec3(0, -1, 0), new Vec3(-1, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 0, -1), new Vec3(0, 0, 1)
		};
		for (int i = 0; i < distances.length; i++) {
			if (distances[i] < nearest) {
				nearest = distances[i];
				normal = normals[i];
			}
		}
		return normal;
	}

	/**
	 * starts spinning around some new axis, faster the faster the camera moves
	 */
	private void tumble(double speed) {
		this.spinAxis.set(this.random.nextFloat() - 0.5F, this.random.nextFloat() - 0.5F,
				this.random.nextFloat() - 0.5F);
		if (this.spinAxis.lengthSquared() < 1.0E-4F) {
			this.spinAxis.set(1, 0, 0);
		}
		this.spinAxis.normalize();
		this.spinSpeed = SPIN * speed;
	}

	private boolean supported(Subject subject) {
		Vec3 below = this.position.add(0, -(RADIUS + 0.15), 0);
		return subject.player.level().clip(new ClipContext(this.position, below, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, subject.player)).getType() != HitResult.Type.MISS;
	}

	/**
	 * @return how the camera should lie: somewhere between any odd way and with the lens right at the focus
	 */
	private Quaternionf restRotation(Vec3 focus, CameraConfig config) {
		Quaternionf atFocus = new Quaternionf();
		if (!CamMath.lookRotation(focus.subtract(this.position), atFocus)) {
			return new Quaternionf(this.randomRest);
		}
		return this.randomRest.slerp(atFocus, (float) CamMath.clamp(config.physicsAim, 0.0, 1.0), new Quaternionf());
	}
}
