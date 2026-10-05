package ru.deelter.vrcamera.client.rig;

import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.math.CamMath;

import java.util.Random;

/**
 * A camera that was let go of. Falls, bounces off blocks and comes to rest on the ground.
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
	 * the camera is back in the hand
	 */
	public void pickUp() {
		this.falling = false;
		this.resting = false;
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
		this.randomRest.rotationYXZ(
			(float) (this.random.nextDouble() * Math.PI * 2.0),
			(float) CamMath.lerp(-0.5, 0.3, this.random.nextDouble()),
			(float) (CamMath.lerp(0.6, 1.4, this.random.nextDouble()) * (this.random.nextBoolean() ? 1 : -1)));
		tumble(Math.max(1.0, velocity.length()));
	}

	public void update(Subject subject, double dt, CameraConfig config) {
		if (dt <= 0) {
			return;
		}
		if (this.resting && !supported(subject)) {
			// the block it was lying on is gone
			this.resting = false;
			this.falling = true;
		}
		if (this.falling) {
			fall(subject, dt);
		}

		Quaternionf target = restRotation(subject, config);
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
		BlockHitResult hit = subject.player.level().clip(new ClipContext(this.position, ahead,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject.player));
		if (hit.getType() == HitResult.Type.MISS) {
			this.position = this.position.add(step);
			if (this.position.y < subject.player.level().getMinY() - 16) {
				// fell out of the world, nothing to land on down there
				this.falling = false;
				this.resting = true;
			}
			return;
		}

		Direction face = hit.getDirection();
		Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		this.position = hit.getLocation().add(normal.scale(RADIUS));

		// bounce: back along the normal with part of the speed, and slowed down along the surface
		double into = this.velocity.dot(normal);
		Vec3 along = this.velocity.subtract(normal.scale(into));
		this.velocity = along.scale(FRICTION).add(normal.scale(-into * BOUNCE));

		double speed = this.velocity.length();
		if (face == Direction.UP && speed < REST_SPEED) {
			this.velocity = Vec3.ZERO;
			this.falling = false;
			this.resting = true;
			this.settleTime = SETTLE_TIME;
		} else {
			tumble(speed);
		}
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
	 * @return how the camera should lie: somewhere between any odd way and with the lens right at the player
	 */
	private Quaternionf restRotation(Subject subject, CameraConfig config) {
		Quaternionf atPlayer = new Quaternionf();
		if (!CamMath.lookRotation(subject.center.subtract(this.position), atPlayer)) {
			return new Quaternionf(this.randomRest);
		}
		return this.randomRest.slerp(atPlayer, (float) CamMath.clamp(config.physicsAim, 0.0, 1.0), new Quaternionf());
	}
}
