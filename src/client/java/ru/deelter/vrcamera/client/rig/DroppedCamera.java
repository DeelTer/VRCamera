package ru.deelter.vrcamera.client.rig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
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

import java.util.ArrayList;
import java.util.List;
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
	private static final double GRAVITY = 16.0;

	private static final double RADIUS = 0.06;

	private static final double BOUNCE = 0.35;
	private static final double FRICTION = 0.55;

	private static final double REST_SPEED = 0.9;

	private static final double AIR_DRAG = 0.2;
	private static final double FLUID_DRAG = 3.0;
	private static final double FLUID_GRAVITY = 0.15;

	private static final double SETTLE_TIME = 1.5;

	private static final double SPIN = 2.5;

	private static final double IMPACT_MIN_SPEED = 1.0;

	private static final double FOV_KICK = 1.5;
	private static final double FOV_KICK_MAX = 12.0;
	private static final double FOV_KICK_TIME = 0.2;
	private static final double FOV_KICK_FREQUENCY = 3.5;

	private static final double KICK_MIN_SPEED = 1.5;
	private static final double KICK_LIFT = 2.2;

	private static final double KICK_IGNORE_TIME = 0.6;
	private static final double PLAYER_KICK_WIDTH = 0.6;

	private static final double PLACE_MAX_SPEED = 0.8;
	private static final double PLACE_REACH = 0.3;

	private static final double MOUNT_REACH = 0.16;

	private static final double WALL_GAP = 0.12;
	private static final double CEILING_GAP = 0.2;

	private static final double ATTACH_REACH = 0.1;
	private static final double SELF_ATTACH_REACH = 0.3;

	private static final double HEAD_ZONE = 0.2;

	private static final double HEAD_REACH = 0.35;

	private static final double MAX_LEAD = 0.5;

	private static final double HEAD_LENGTH = 0.8;

	private static final double FOUR_LEGS_SHAPE = 0.5;
	private static final double STRIKE_BOUNCE = 0.6;
	private final Random random = new Random();
	private final Quaternionf rotation = new Quaternionf();
	private final Vector3f spinAxis = new Vector3f(1, 0, 0);
	private final Quaternionf randomRest = new Quaternionf();
	private final Vector3f attachedOffset = new Vector3f();
	private final Quaternionf attachedRotation = new Quaternionf();
	private Vec3 position = Vec3.ZERO;
	private Vec3 velocity = Vec3.ZERO;
	private boolean falling;
	private boolean resting;
	private Vec3 mountedOn;
	private double settleTime;
	private double spinSpeed;
	private Entity carrier;
	private Vec3 carrierOffset = Vec3.ZERO;
	private float carrierYaw;
	private float carriedTurn;

	private boolean attached;
	private boolean attachedToHead;
	private boolean attachedToSelf;
	private Entity newHost;
	private boolean selfAllowed = true;

	private Vec3 attachedOrigin;
	private Entity kicker;
	private double kickIgnoreTime;
	private Impact impact;
	private double fovKick;
	private double fovKickAge;
	private boolean sinking;
	private double sinkTime;

	/**
	 * @return if the camera collides with the entity: everything with a hitbox that can be hit, so not items or arrows
	 */
	private static boolean isSolid(Entity entity) {
		return entity.isPickable() && !entity.isSpectator();
	}

	/**
	 * @return if the camera is held to the head of that entity: around the way from its neck to where it looks
	 */
	private static boolean atHead(Entity entity, Subject subject, Vec3 position) {
		if (!(entity instanceof LivingEntity)) {
			return false;
		}
		final Vec3 neck = neck(entity, subject);
		final Vec3 along = entity.getViewVector(subject.partialTick).scale(entity.getBbWidth() * HEAD_LENGTH);
		final double alongNeck = position.subtract(neck).dot(along) / Math.max(1.0E-6, along.lengthSqr());
		final double part = CamMath.clamp(alongNeck, 0.0, 1.0);
		return position.distanceTo(neck.add(along.scale(part))) < HEAD_REACH * Math.max(1.0, entity.getBbWidth());
	}

	/**
	 * @return if the head of that entity is in front of its body and not on top of it, like the one of a sheep.
	 * Told by its shape: what walks on four legs is about as long as it is high
	 */
	private static boolean headInFront(Entity entity) {
		return entity.getBbWidth() > entity.getBbHeight() * FOUR_LEGS_SHAPE;
	}

	/**
	 * @return the point the head of that entity turns around. For one that walks upright that is between its
	 * eyes. For one on four legs it is at the front of its body: turned around its middle, a camera on its head
	 * would swing past the head
	 */
	private static Vec3 neck(Entity entity, Subject subject) {
		final Vec3 eyes = entity.getEyePosition(subject.partialTick);
		if (!(entity instanceof LivingEntity living) || !headInFront(entity)) {
			return eyes;
		}
		final float bodyYaw = Mth.rotLerp(subject.partialTick, living.yBodyRotO, living.yBodyRot) * Mth.DEG_TO_RAD;
		final double forward = entity.getBbWidth() * 0.5;
		return eyes.add(-Math.sin(bodyYaw) * forward, 0, Math.cos(bodyYaw) * forward);
	}

	private static boolean swims(Entity entity) {
		final MobCategory kind = entity.getType().getCategory();
		return entity instanceof LivingEntity && (kind == MobCategory.WATER_CREATURE ||
				kind == MobCategory.WATER_AMBIENT || kind == MobCategory.UNDERGROUND_WATER_CREATURE ||
				kind == MobCategory.AXOLOTLS);
	}

	private static Vec3 frameOrigin(Entity entity, Subject subject, boolean head) {
		if (!head) {
			return entity.getPosition(subject.partialTick);
		}

		return entity == subject.player ? subject.head : neck(entity, subject);
	}

	private static Quaternionf frameRotation(Entity entity, Subject subject, boolean head) {
		if (head && entity == subject.player) {
			final Quaternionf look = new Quaternionf();
			CamMath.lookRotation(subject.headDir, look);
			return look;
		}
		final float yaw = entity instanceof LivingEntity living && !head ?
				Mth.rotLerp(subject.partialTick, living.yBodyRotO, living.yBodyRot) :
				entity.getViewYRot(subject.partialTick);

		final Quaternionf frame = new Quaternionf().rotationY(-yaw * Mth.DEG_TO_RAD);
		return head ? frame.rotateX(entity.getViewXRot(subject.partialTick) * Mth.DEG_TO_RAD) : frame;
	}

	/**
	 * @return which way the side of the box points, that the point is on
	 */
	private static Vec3 faceNormal(AABB box, Vec3 point) {
		Vec3 normal = new Vec3(0, 1, 0);
		double nearest = Math.abs(point.y - box.maxY);
		final double[] distances = {
				Math.abs(point.y - box.minY), Math.abs(point.x - box.minX), Math.abs(point.x - box.maxX),
				Math.abs(point.z - box.minZ), Math.abs(point.z - box.maxZ)
		};
		final Vec3[] normals = {
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

	public Vec3 position() {
		return position;
	}

	public Quaternionf rotation() {
		return rotation;
	}

	/**
	 * @return if the camera was let go of, and is falling or lying somewhere
	 */
	public boolean isDropped() {
		return falling || resting;
	}

	public boolean isResting() {
		return resting;
	}

	/**
	 * @return if the camera is lying on an entity that carries it along
	 */
	public boolean isCarried() {
		return resting && carrier != null;
	}

	/**
	 * @return the last hit since this was asked the last time, null if there was none
	 */
	public Impact pollImpact() {
		final Impact last = impact;
		impact = null;
		return last;
	}

	/**
	 * @return degrees to add to the field of view, the jolt of a hit that swings out
	 */
	public double fovOffset() {
		return fovKick * Math.exp(-fovKickAge / FOV_KICK_TIME) *
				Math.cos(fovKickAge * Math.PI * 2.0 * FOV_KICK_FREQUENCY);
	}

	/**
	 * lets a camera that lies still turn to its focus once more
	 */
	public void settleAgain() {
		if (resting) {
			settleTime = SETTLE_TIME;
		}
	}

	public void pickUp() {
		falling = false;
		resting = false;
		mountedOn = null;
		carrier = null;
		fovKick = 0;
	}

	/**
	 * @param entity what the camera came to rest on, null for a block
	 */

	/**
	 * @param velocity speed of the hand when it let go, a thrown camera flies
	 */
	public void drop(Vec3 position, Quaternionfc rotation, Vec3 velocity) {
		this.position = position;
		this.velocity = velocity;
		this.rotation.set(rotation);
		falling = true;
		resting = false;
		carrier = null;
		kicker = null;
		sinking = false;
		randomRest.rotationYXZ(
				(float) (random.nextDouble() * Math.PI * 2.0),
				(float) CamMath.lerp(-0.5, 0.3, random.nextDouble()),
				(float) (CamMath.lerp(0.6, 1.4, random.nextDouble()) * (random.nextBoolean() ? 1 : -1)));
		tumble(Math.max(1.0, velocity.length()));
	}

	/**
	 * @param focus what the lens turns to while the camera comes to rest
	 */
	public void update(Subject subject, double dt, CameraConfig config, Vec3 focus) {
		if (dt <= 0) {
			return;
		}
		fovKickAge += dt;
		kickIgnoreTime -= dt;

		if (resting) {
			if (carrier != null) {
				ride(subject);
			} else if (!supported(subject)) {
				startFalling();
			} else if (mountedOn == null) {
				getKicked(subject);
			}
		}
		if (falling) {
			fall(subject, dt);
		}

		final Quaternionf target = restRotation(focus, config);
		if (falling) {
			rotation.rotateAxis((float) (spinSpeed * dt), spinAxis);
			if (sinking && config.underwaterLook) {
				spinSpeed *= Math.exp(-4.0 * dt);
				sinkTime += dt;
				rotation.rotateZ((float) (Math.cos(sinkTime * 1.4) * 0.35 * dt))
						.rotateLocalY((float) (0.25 * dt));
			} else {
				spinSpeed *= Math.exp(-0.5 * dt);
			}

			rotation.slerp(target, (float) (config.physicsAim * (1.0 - Math.exp(-dt / 1.2))));
		} else if (settleTime > 0) {
			settleTime -= dt;
			rotation.slerp(target, (float) (1.0 - Math.exp(-dt / 0.25)));
		}
	}

	/**
	 * A hand or foot hits the camera. Counts how fast they close in on each other, so a camera falling onto a still
	 * hand bounces off it as well.
	 *
	 * @param normal from the limb to the camera
	 * @return false if they are not closing in fast enough
	 */
	public boolean strike(Vec3 limbVelocity, Vec3 normal, double power, double minSpeed) {
		if (!isDropped()) {
			return false;
		}
		final double closing = limbVelocity.subtract(velocity).dot(normal);
		if (closing < minSpeed) {
			return false;
		}
		velocity = velocity.add(normal.scale(closing * (1.0 + STRIKE_BOUNCE) * power));
		startFalling();
		tumble(closing);
		hit(new Impact(position, normal, closing, null));
		return true;
	}

	/**
	 * Puts the camera down where the hand let go of it, the way it was held: it does not fall, tumble or turn.
	 *
	 * @param position where the camera is let go, out of any wall
	 * @param heldAt   where the hand has it, which can be inside a wall it was pushed against
	 * @return false if it was not put down but dropped or thrown, or there is nothing to put it on
	 */
	public boolean place(Subject subject, Vec3 position, Vec3 heldAt, Quaternionfc rotation, Vec3 handVelocity) {
		if (handVelocity.length() > PLACE_MAX_SPEED) {
			return false;
		}
		final Entity host = host(subject, position);
		if (host != null) {
			drop(position, rotation, Vec3.ZERO);
			falling = false;
			resting = true;
			settleTime = 0;
			attach(host, subject);
			return true;
		}
		final BlockHitResult below = subject.player.level().clip(new ClipContext(position,
				position.add(0, -PLACE_REACH, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject.player));
		if (below.getType() != HitResult.Type.MISS && below.getDirection() == Direction.UP) {
			drop(new Vec3(position.x, below.getLocation().y + RADIUS, position.z), rotation, Vec3.ZERO);
			falling = false;
			resting = true;
			settleTime = 0;
			return true;
		}

		final BlockHitResult wall = wall(subject, position, heldAt);
		if (wall == null) {
			return false;
		}
		final Direction face = wall.getDirection();
		final Vec3 out = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());

		drop(wall.getLocation().add(out.scale(face == Direction.DOWN ? CEILING_GAP : WALL_GAP)), rotation, Vec3.ZERO);
		falling = false;
		resting = true;
		mountedOn = out;
		settleTime = 0;
		return true;
	}

	/**
	 * @return if the camera would stay up there if it was let go slowly right now: on a wall, a ceiling or an
	 * entity. Not for the floor, where it is put down like anywhere
	 */
	public boolean canPutUp(Subject subject, Vec3 position, Vec3 heldAt) {
		return host(subject, position) != null || wall(subject, position, heldAt) != null;
	}

	/**
	 * @return if the camera was put up on a wall or a ceiling
	 */
	public boolean isMounted() {
		return resting && mountedOn != null;
	}

	/**
	 * @param allowed if the camera can be put on the head of the player themselves
	 */
	public void allowSelf(boolean allowed) {
		selfAllowed = allowed;
	}

	/**
	 * @return what the camera was put on since this was asked the last time, null if nothing
	 */
	public Entity pollHost() {
		final Entity host = newHost;
		newHost = null;
		return host;
	}

	/**
	 * @return how fast what the camera was put on moves, in blocks per second. 0 if it is not on anything
	 */
	public double attachedSpeed() {
		if (!isAttached()) {
			return 0;
		}
		return new Vec3(carrier.getX() - carrier.xo, 0, carrier.getZ() - carrier.zo).length() * 20.0;
	}

	/**
	 * @return if the camera was put on an entity by hand and goes along with it
	 */
	public boolean isAttached() {
		return resting && attached && carrier != null;
	}

	/**
	 * @return if the camera sits on the head of the player themselves
	 */
	public boolean isOnPlayer() {
		return isAttached() && attachedToSelf;
	}

	/**
	 * @return the wall or ceiling the camera is held against, null if there is none that close
	 */
	private BlockHitResult wall(Subject subject, Vec3 position, Vec3 heldAt) {
		BlockHitResult nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		final List<Vec3> ways = new ArrayList<>();
		for (final Direction direction : Direction.values()) {
			if (direction != Direction.DOWN) {
				ways.add(position.add(new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ())
						.scale(MOUNT_REACH)));
			}
		}

		final Vec3 pushed = heldAt.subtract(position);
		if (pushed.lengthSqr() > 1.0E-6) {
			ways.add(heldAt.add(pushed.normalize().scale(MOUNT_REACH)));
		}
		for (final Vec3 way : ways) {
			final BlockHitResult hit = subject.player.level().clip(new ClipContext(position, way,
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject.player));
			final double distance = hit.getLocation().distanceToSqr(position);
			if (hit.getType() != HitResult.Type.MISS && hit.getDirection() != Direction.UP &&
					distance < nearestDistance) {
				nearest = hit;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	private void startFalling() {
		resting = false;
		mountedOn = null;
		falling = true;
		carrier = null;
	}

	/**
	 * moves and turns the camera with the entity it is lying on
	 */
	private void ride(Subject subject) {
		if (attached && attachedToSelf) {
			if (subject.player.isDeadOrDying()) {
				startFalling();
				return;
			}

			carrier = subject.player;
		} else if (!carrier.isAlive() || carrier.level() != subject.player.level()) {
			startFalling();
			return;
		}
		if (attached) {
			final Quaternionf frame = frameRotation(carrier, subject, attachedToHead);
			final Vector3f offset = frame.transform(new Vector3f(attachedOffset));
			final Vec3 origin = frameOrigin(carrier, subject, attachedToHead);

			Vec3 ahead = attachedOrigin == null ? Vec3.ZERO : origin.subtract(attachedOrigin);
			attachedOrigin = origin;
			if (ahead.lengthSqr() > MAX_LEAD * MAX_LEAD) {
				ahead = Vec3.ZERO;
			}
			position = origin.add(offset.x, offset.y, offset.z).add(ahead);
			frame.mul(attachedRotation, rotation);
			return;
		}
		final float turn = Mth.wrapDegrees(carrier.getViewYRot(subject.partialTick) - carrierYaw);

		final float radians = -turn * Mth.DEG_TO_RAD;
		position = carrier.getPosition(subject.partialTick).add(carrierOffset.yRot(radians));
		rotation.rotateLocalY(-(turn - carriedTurn) * Mth.DEG_TO_RAD);
		carriedTurn = turn;
	}

	/**
	 * a camera lying on the ground is kicked away by whatever walks into it, the player included
	 */
	private void getKicked(Subject subject) {
		final AABB reach = new AABB(position, position).inflate(1.0);
		for (final Entity entity : subject.player.level().getEntities((Entity) null, reach, DroppedCamera::isSolid)) {
			AABB box = entity.getBoundingBox();
			if (entity == subject.player) {
				final double shrink = box.getXsize() * (1.0 - PLAYER_KICK_WIDTH) / 2.0;
				box = box.inflate(-shrink, 0, -shrink);
			} else {
				box = box.inflate(RADIUS);
			}
			if (!box.contains(position)) {
				continue;
			}

			final Vec3 motion = new Vec3(entity.getX() - entity.xo, 0, entity.getZ() - entity.zo).scale(20.0);
			final double speed = motion.length();
			if (speed < KICK_MIN_SPEED) {
				continue;
			}
			velocity = motion.scale(1.1).add(0, KICK_LIFT, 0);
			kicker = entity;
			kickIgnoreTime = KICK_IGNORE_TIME;
			startFalling();
			tumble(speed * 1.5);
			hit(new Impact(position, new Vec3(0, 1, 0), speed,
					BlockPos.containing(position.add(0, -(RADIUS + 0.1), 0))));
			return;
		}
	}

	private void fall(Subject subject, double dt) {
		final boolean inFluid = WorldProbe.inFluid(subject, position);
		sinking = inFluid;
		final double gravity = GRAVITY * (inFluid ? FLUID_GRAVITY : 1.0);
		velocity = velocity.add(0, -gravity * dt, 0)
				.scale(Math.exp(-(inFluid ? FLUID_DRAG : AIR_DRAG) * dt));

		final Vec3 step = velocity.scale(dt);
		final double distance = step.length();
		if (distance < 1.0E-6) {
			return;
		}

		final Vec3 ahead = position.add(step.scale((distance + RADIUS) / distance));
		final Hit hit = trace(subject, position, ahead);
		if (hit == null) {
			position = position.add(step);
			if (position.y < subject.player.level().getMinY() - 16) {
				falling = false;
				resting = true;
			}
			return;
		}

		if (hit.entity != null && sinking && swims(hit.entity)) {
			putOnHead(hit.entity, subject);
			return;
		}
		final Vec3 normal = hit.normal;
		position = hit.location.add(normal.scale(RADIUS));

		final double into = velocity.dot(normal);
		final Vec3 along = velocity.subtract(normal.scale(into));
		velocity = along.scale(FRICTION).add(normal.scale(-into * BOUNCE));
		if (-into > IMPACT_MIN_SPEED) {
			hit(new Impact(hit.location, normal, -into,
					hit.entity != null ? null : BlockPos.containing(hit.location.subtract(normal.scale(0.05)))));
		}

		final double speed = velocity.length();
		if (normal.y > 0.5 && speed < REST_SPEED) {
			velocity = Vec3.ZERO;
			falling = false;
			resting = true;
			settleTime = SETTLE_TIME;
			landOn(hit.entity, subject);
		} else {
			tumble(speed);
		}
	}

	private void hit(Impact impact) {
		this.impact = impact;
		fovKick = -Math.min(FOV_KICK * impact.speed, FOV_KICK_MAX);
		fovKickAge = 0;
	}

	/**
	 * @return what the camera is held against closely enough to be put on it, null if nothing
	 */
	private Entity host(Subject subject, Vec3 position) {
		Entity nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		final AABB around = new AABB(position, position).inflate(1.5);
		for (final Entity entity : subject.player.level().getEntities((Entity) null, around, DroppedCamera::isSolid)) {
			if (entity == subject.player) {
				if (!selfAllowed) {
					continue;
				}

				if (position.distanceTo(subject.head) > SELF_ATTACH_REACH) {
					continue;
				}
			} else if (!entity.getBoundingBox().inflate(ATTACH_REACH).contains(position) &&
					!atHead(entity, subject, position)) {
				continue;
			}
			final double distance = entity.getBoundingBox().getCenter().distanceToSqr(position);
			if (distance < nearestDistance) {
				nearest = entity;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/**
	 * A camera that sinks onto something that swims is taken along by it: on its head, filming where it swims
	 */
	private void putOnHead(Entity entity, Subject subject) {
		final Vec3 look = entity.getViewVector(subject.partialTick);
		position = neck(entity, subject).add(look.scale(entity.getBbWidth() * 0.45))
				.add(0, entity.getBbHeight() * 0.3, 0);
		CamMath.lookRotation(look, rotation);
		velocity = Vec3.ZERO;
		falling = false;
		resting = true;
		settleTime = 0;
		attach(entity, subject);
	}

	private void attach(Entity entity, Subject subject) {
		carrier = entity;
		newHost = entity;
		attached = true;
		attachedOrigin = null;
		attachedToSelf = entity == subject.player;

		attachedToHead = entity instanceof LivingEntity && (atHead(entity, subject, position) ||
				(!headInFront(entity) &&
						position.y > frameOrigin(entity, subject, true).y - HEAD_ZONE * entity.getBbHeight()));
		final Quaternionf inverse = frameRotation(entity, subject, attachedToHead).invert();
		final Vec3 offset = position.subtract(frameOrigin(entity, subject, attachedToHead));
		inverse.transform(attachedOffset.set((float) offset.x, (float) offset.y, (float) offset.z));
		inverse.mul(rotation, attachedRotation);
	}

	private void landOn(Entity entity, Subject subject) {
		carrier = entity;
		attached = false;
		if (entity != null) {
			carrierOffset = position.subtract(entity.getPosition(subject.partialTick));
			carrierYaw = entity.getViewYRot(subject.partialTick);
			carriedTurn = 0;
		}
	}

	/**
	 * @return the first thing in the way from one point to the other, null if the way is free
	 */
	private Hit trace(Subject subject, Vec3 from, Vec3 to) {
		final Level level = subject.player.level();
		Hit nearest = null;
		double nearestDistance = Double.MAX_VALUE;

		final BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, subject.player));
		if (block.getType() != HitResult.Type.MISS) {
			final Direction face = block.getDirection();
			nearest = new Hit(block.getLocation(), new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()), null);
			nearestDistance = block.getLocation().distanceToSqr(from);
		}

		for (final Entity entity : level.getEntities(subject.player, new AABB(from, to).inflate(0.5),
				DroppedCamera::isSolid)) {
			if (entity == kicker && kickIgnoreTime > 0) {
				continue;
			}
			final AABB box = entity.getBoundingBox();
			if (box.contains(from)) {
				return new Hit(new Vec3(from.x, box.maxY, from.z), new Vec3(0, 1, 0), entity);
			}
			final Optional<Vec3> point = box.clip(from, to);
			if (point.isPresent() && point.get().distanceToSqr(from) < nearestDistance) {
				nearestDistance = point.get().distanceToSqr(from);
				nearest = new Hit(point.get(), faceNormal(box, point.get()), entity);
			}
		}
		return nearest;
	}

	/**
	 * starts spinning around some new axis, faster the faster the camera moves
	 */
	private void tumble(double speed) {
		spinAxis.set(random.nextFloat() - 0.5F, random.nextFloat() - 0.5F,
				random.nextFloat() - 0.5F);
		if (spinAxis.lengthSquared() < 1.0E-4F) {
			spinAxis.set(1, 0, 0);
		}
		spinAxis.normalize();
		spinSpeed = SPIN * speed;
	}

	private boolean supported(Subject subject) {
		if (mountedOn != null) {
			final Vec3 into = position.subtract(mountedOn.scale(CEILING_GAP + 0.15));
			return subject.player.level().clip(new ClipContext(position, into, ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE, subject.player)).getType() != HitResult.Type.MISS;
		}
		final Vec3 below = position.add(0, -(RADIUS + 0.15), 0);
		return subject.player.level().clip(new ClipContext(position, below, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, subject.player)).getType() != HitResult.Type.MISS;
	}

	/**
	 * @return how the camera should lie: somewhere between any odd way and with the lens right at the focus
	 */
	private Quaternionf restRotation(Vec3 focus, CameraConfig config) {
		final Quaternionf atFocus = new Quaternionf();
		if (!CamMath.lookRotation(focus.subtract(position), atFocus)) {
			return new Quaternionf(randomRest);
		}
		return randomRest.slerp(atFocus, (float) CamMath.clamp(config.physicsAim, 0.0, 1.0), new Quaternionf());
	}

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
}
