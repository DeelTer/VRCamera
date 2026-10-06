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
	// blocks per second squared
	private static final double GRAVITY = 16.0;
	// size of the camera, half of it, in blocks
	private static final double RADIUS = 0.06;
	// part of the speed that is left after bouncing off something, straight back and along the surface
	private static final double BOUNCE = 0.35;
	private static final double FRICTION = 0.55;
	// slower than this on the ground it stops, blocks per second
	private static final double REST_SPEED = 0.9;
	// speed lost per second in the air and in a fluid, as an exponent
	private static final double AIR_DRAG = 0.2;
	private static final double FLUID_DRAG = 3.0;
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
	private static final double KICK_LIFT = 2.2;
	// seconds a kicked camera passes through what kicked it, or it would land on its head
	private static final double KICK_IGNORE_TIME = 0.6;
	private static final double PLAYER_KICK_WIDTH = 0.6;
	// a camera let go of slower than this, with a block this close below, is put down and not dropped
	private static final double PLACE_MAX_SPEED = 0.8;
	private static final double PLACE_REACH = 0.3;
	// blocks from the middle of the camera to a wall it is put up on
	private static final double MOUNT_REACH = 0.16;
	// blocks from a wall or a ceiling to the middle of a camera put up on it
	private static final double WALL_GAP = 0.12;
	private static final double CEILING_GAP = 0.2;
	// blocks around an entity in which a camera is put on it, and around the own head
	private static final double ATTACH_REACH = 0.1;
	private static final double SELF_ATTACH_REACH = 0.3;
	// the part of an entity below its eyes that still counts as its head
	private static final double HEAD_ZONE = 0.2;
	// blocks around the head of an entity in which a camera is put on it, for one that is a block wide or less
	private static final double HEAD_REACH = 0.35;
	// how long a head is, in widths of its entity
	private static final double HEAD_LENGTH = 0.8;
	// wider than this part of its height an entity is taken to walk on four legs
	private static final double FOUR_LEGS_SHAPE = 0.5;
	private static final double STRIKE_BOUNCE = 0.6;

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
	// resting against a wall or under a ceiling, where the player put it. Pointing away from that block
	private Vec3 mountedOn;
	private double settleTime;

	private final Vector3f spinAxis = new Vector3f(1, 0, 0);
	private double spinSpeed;
	// how it would lie on the ground if it did not care about the player, different on every drop
	private final Quaternionf randomRest = new Quaternionf();

	// the entity the camera is lying on, null on the ground. Where on it, and how the entity was turned when it landed
	private Entity carrier;
	private Vec3 carrierOffset = Vec3.ZERO;
	private float carrierYaw;
	private float carriedTurn;
	// Put on the carrier by hand, not just lying on it: it stays where it was put, on the head it also nods and
	// turns with the head. Where and how it sits, seen from the body or the head of the carrier
	private boolean attached;
	private boolean attachedToHead;
	private boolean attachedToSelf;
	private Entity newHost;
	private final Vector3f attachedOffset = new Vector3f();
	private final Quaternionf attachedRotation = new Quaternionf();

	// what kicked the camera, it does not collide with that for a moment
	private Entity kicker;
	private double kickIgnoreTime;

	private Impact impact;
	private double fovKick;
	private double fovKickAge;
	private boolean sinking;
	private double sinkTime;

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
		this.mountedOn = null;
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
		this.sinking = false;
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
			} else if (this.mountedOn == null) {
				// one that was put up on a wall is not knocked off by walking past it, only by a hit
				getKicked(subject);
			}
		}
		if (this.falling) {
			fall(subject, dt);
		}

		Quaternionf target = restRotation(focus, config);
		if (this.falling) {
			this.rotation.rotateAxis((float) (this.spinSpeed * dt), this.spinAxis);
			if (this.sinking && config.underwaterLook) {
				// water stops the tumbling, what is left is a slow roll from side to side while it turns
				this.spinSpeed *= Math.exp(-4.0 * dt);
				this.sinkTime += dt;
				this.rotation.rotateZ((float) (Math.cos(this.sinkTime * 1.4) * 0.35 * dt))
						.rotateLocalY((float) (0.25 * dt));
			} else {
				this.spinSpeed *= Math.exp(-0.5 * dt);
			}
			// already starts to turn the right way in the air, so the landing is not one sudden twist
			this.rotation.slerp(target, (float) (config.physicsAim * (1.0 - Math.exp(-dt / 1.2))));
		} else if (this.settleTime > 0) {
			this.settleTime -= dt;
			this.rotation.slerp(target, (float) (1.0 - Math.exp(-dt / 0.25)));
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
		double closing = limbVelocity.subtract(this.velocity).dot(normal);
		if (closing < minSpeed) {
			return false;
		}
		this.velocity = this.velocity.add(normal.scale(closing * (1.0 + STRIKE_BOUNCE) * power));
		startFalling();
		tumble(closing);
		hit(new Impact(this.position, normal, closing, null));
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
		Entity host = host(subject, position);
		if (host != null) {
			drop(position, rotation, Vec3.ZERO);
			this.falling = false;
			this.resting = true;
			this.settleTime = 0;
			attach(host, subject);
			return true;
		}
		BlockHitResult below = subject.player.level().clip(new ClipContext(position,
				position.add(0, -PLACE_REACH, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject.player));
		if (below.getType() != HitResult.Type.MISS && below.getDirection() == Direction.UP) {
			drop(new Vec3(position.x, below.getLocation().y + RADIUS, position.z), rotation, Vec3.ZERO);
			this.falling = false;
			this.resting = true;
			this.settleTime = 0;
			return true;
		}
		// Held right against a wall or a ceiling: it stays up there, turned the way it was held
		BlockHitResult wall = wall(subject, position, heldAt);
		if (wall == null) {
			return false;
		}
		Direction face = wall.getDirection();
		Vec3 out = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		// Far enough out for the whole camera to be in front of the block, whichever way it is turned. Under a
		// ceiling there is the screen on top of it as well
		drop(wall.getLocation().add(out.scale(face == Direction.DOWN ? CEILING_GAP : WALL_GAP)), rotation, Vec3.ZERO);
		this.falling = false;
		this.resting = true;
		this.mountedOn = out;
		this.settleTime = 0;
		return true;
	}

	/**
	 * @return the wall or ceiling the camera is held against, null if there is none that close
	 */
	private BlockHitResult wall(Subject subject, Vec3 position, Vec3 heldAt) {
		BlockHitResult nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		List<Vec3> ways = new ArrayList<>();
		for (Direction direction : Direction.values()) {
			if (direction != Direction.DOWN) {
				ways.add(position.add(new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ())
						.scale(MOUNT_REACH)));
			}
		}
		// A hand pushes the camera into the wall, and it is let go a bit in front of that: the wall is then
		// further away than it would be looked for, but it is where the hand is
		Vec3 pushed = heldAt.subtract(position);
		if (pushed.lengthSqr() > 1.0E-6) {
			ways.add(heldAt.add(pushed.normalize().scale(MOUNT_REACH)));
		}
		for (Vec3 way : ways) {
			BlockHitResult hit = subject.player.level().clip(new ClipContext(position, way,
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject.player));
			double distance = hit.getLocation().distanceToSqr(position);
			if (hit.getType() != HitResult.Type.MISS && hit.getDirection() != Direction.UP &&
					distance < nearestDistance) {
				nearest = hit;
				nearestDistance = distance;
			}
		}
		return nearest;
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
		return this.resting && this.mountedOn != null;
	}

	private void startFalling() {
		this.resting = false;
		this.mountedOn = null;
		this.falling = true;
		this.carrier = null;
	}

	/**
	 * moves and turns the camera with the entity it is lying on
	 */
	private void ride(Subject subject) {
		if (this.attached && this.attachedToSelf) {
			if (subject.player.isDeadOrDying()) {
				startFalling();
				return;
			}
			// The game makes a new player for every dimension and every life. On the head of the player the
			// camera goes along to the next dimension, a death it does not survive up there
			this.carrier = subject.player;
		} else if (!this.carrier.isAlive() || this.carrier.level() != subject.player.level()) {
			startFalling();
			return;
		}
		if (this.attached) {
			Quaternionf frame = frameRotation(this.carrier, subject, this.attachedToHead);
			Vector3f offset = frame.transform(new Vector3f(this.attachedOffset));
			this.position = frameOrigin(this.carrier, subject, this.attachedToHead).add(offset.x, offset.y, offset.z);
			frame.mul(this.attachedRotation, this.rotation);
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
			AABB box = entity.getBoundingBox();
			if (entity == subject.player) {
				// Smaller for the player, or bending down to pick the camera up kicks it away: roomscale moves
				// the whole hitbox along with the head
				double shrink = box.getXsize() * (1.0 - PLAYER_KICK_WIDTH) / 2.0;
				box = box.inflate(-shrink, 0, -shrink);
			} else {
				box = box.inflate(RADIUS);
			}
			if (!box.contains(this.position)) {
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
		this.sinking = inFluid;
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

		if (hit.entity != null && this.sinking && swims(hit.entity)) {
			putOnHead(hit.entity, subject);
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
		this.fovKick = -Math.min(FOV_KICK * impact.speed, FOV_KICK_MAX);
		this.fovKickAge = 0;
	}

	/**
	 * @param entity what the camera came to rest on, null for a block
	 */
	/**
	 * @return what the camera is held against closely enough to be put on it, null if nothing
	 */
	private Entity host(Subject subject, Vec3 position) {
		Entity nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		AABB around = new AABB(position, position).inflate(1.5);
		for (Entity entity : subject.player.level().getEntities((Entity) null, around, DroppedCamera::isSolid)) {
			if (entity == subject.player) {
				// Only on the head. A hand lets go of the camera near the own body all the time, and it is not
				// meant to stick there every time
				if (position.distanceTo(subject.head) > SELF_ATTACH_REACH) {
					continue;
				}
			} else if (!entity.getBoundingBox().inflate(ATTACH_REACH).contains(position) &&
					!atHead(entity, subject, position)) {
				continue;
			}
			double distance = entity.getBoundingBox().getCenter().distanceToSqr(position);
			if (distance < nearestDistance) {
				nearest = entity;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/**
	 * @return if the camera is held to the head of that entity: around the way from its neck to where it looks
	 */
	private static boolean atHead(Entity entity, Subject subject, Vec3 position) {
		if (!(entity instanceof LivingEntity)) {
			return false;
		}
		Vec3 neck = neck(entity, subject);
		Vec3 along = entity.getViewVector(subject.partialTick).scale(entity.getBbWidth() * HEAD_LENGTH);
		double part = CamMath.clamp(position.subtract(neck).dot(along) / Math.max(1.0E-6, along.lengthSqr()), 0.0, 1.0);
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
		Vec3 eyes = entity.getEyePosition(subject.partialTick);
		if (!(entity instanceof LivingEntity living) || !headInFront(entity)) {
			return eyes;
		}
		float bodyYaw = Mth.rotLerp(subject.partialTick, living.yBodyRotO, living.yBodyRot) * Mth.DEG_TO_RAD;
		double forward = entity.getBbWidth() * 0.5;
		return eyes.add(-Math.sin(bodyYaw) * forward, 0, Math.cos(bodyYaw) * forward);
	}

	private static boolean swims(Entity entity) {
		MobCategory kind = entity.getType().getCategory();
		return entity instanceof LivingEntity && (kind == MobCategory.WATER_CREATURE ||
				kind == MobCategory.WATER_AMBIENT || kind == MobCategory.UNDERGROUND_WATER_CREATURE ||
				kind == MobCategory.AXOLOTLS);
	}

	/**
	 * A camera that sinks onto something that swims is taken along by it: on its head, filming where it swims
	 */
	private void putOnHead(Entity entity, Subject subject) {
		Vec3 look = entity.getViewVector(subject.partialTick);
		this.position = neck(entity, subject).add(look.scale(entity.getBbWidth() * 0.45))
				.add(0, entity.getBbHeight() * 0.3, 0);
		CamMath.lookRotation(look, this.rotation);
		this.velocity = Vec3.ZERO;
		this.falling = false;
		this.resting = true;
		this.settleTime = 0;
		attach(entity, subject);
	}

	/**
	 * @return what the camera was put on since this was asked the last time, null if nothing
	 */
	public Entity pollHost() {
		Entity host = this.newHost;
		this.newHost = null;
		return host;
	}

	private void attach(Entity entity, Subject subject) {
		this.carrier = entity;
		this.newHost = entity;
		this.attached = true;
		this.attachedToSelf = entity == subject.player;
		// near the eyes it goes with the head, anywhere else with the body
		// On four legs the back is as high as the head, there only what is at the head counts
		this.attachedToHead = entity instanceof LivingEntity && (atHead(entity, subject, this.position) ||
				(!headInFront(entity) &&
						this.position.y > frameOrigin(entity, subject, true).y - HEAD_ZONE * entity.getBbHeight()));
		Quaternionf inverse = frameRotation(entity, subject, this.attachedToHead).invert();
		Vec3 offset = this.position.subtract(frameOrigin(entity, subject, this.attachedToHead));
		inverse.transform(this.attachedOffset.set((float) offset.x, (float) offset.y, (float) offset.z));
		inverse.mul(this.rotation, this.attachedRotation);
	}

	private static Vec3 frameOrigin(Entity entity, Subject subject, boolean head) {
		if (!head) {
			return entity.getPosition(subject.partialTick);
		}
		// the head of the player in VR is where the headset is, not where the game has the eyes
		return entity == subject.player ? subject.head : neck(entity, subject);
	}

	private static Quaternionf frameRotation(Entity entity, Subject subject, boolean head) {
		if (head && entity == subject.player) {
			Quaternionf look = new Quaternionf();
			CamMath.lookRotation(subject.headDir, look);
			return look;
		}
		float yaw = entity instanceof LivingEntity living && !head ?
				Mth.rotLerp(subject.partialTick, living.yBodyRotO, living.yBodyRot) :
				entity.getViewYRot(subject.partialTick);
		// yaw of entities goes the other way around than rotations around the y axis
		Quaternionf frame = new Quaternionf().rotationY(-yaw * Mth.DEG_TO_RAD);
		return head ? frame.rotateX(entity.getViewXRot(subject.partialTick) * Mth.DEG_TO_RAD) : frame;
	}

	/**
	 * @return how fast what the camera was put on moves, in blocks per second. 0 if it is not on anything
	 */
	public double attachedSpeed() {
		if (!isAttached()) {
			return 0;
		}
		return new Vec3(this.carrier.getX() - this.carrier.xo, 0, this.carrier.getZ() - this.carrier.zo).length() * 20.0;
	}

	/**
	 * @return if the camera was put on an entity by hand and goes along with it
	 */
	public boolean isAttached() {
		return this.resting && this.attached && this.carrier != null;
	}

	/**
	 * @return if the camera sits on the head of the player themselves
	 */
	public boolean isOnPlayer() {
		return isAttached() && this.attachedToSelf;
	}

	private void landOn(Entity entity, Subject subject) {
		this.carrier = entity;
		this.attached = false;
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
		if (this.mountedOn != null) {
			Vec3 into = this.position.subtract(this.mountedOn.scale(CEILING_GAP + 0.15));
			return subject.player.level().clip(new ClipContext(this.position, into, ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE, subject.player)).getType() != HitResult.Type.MISS;
		}
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
