package ru.deelter.vrcamera.client.rig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.math.Smooth;
import ru.deelter.vrcamera.client.math.SmoothAngle;
import ru.deelter.vrcamera.client.math.SmoothVec;
import ru.deelter.vrcamera.client.shot.Shot;

/**
 * Moves the camera to where the current {@link Shot} wants it. Smooths the movement, keeps the camera out of blocks
 * and aims it at the player.
 */
public final class Rig {
	private static final double BLEND_TIME = 1.6;

	private static final double ANCHOR_LAG = 0.18;
	private static final double SOLID_STEP = 0.2;
	private static final double BACK_OUT_AFTER = 0.5;

	private static final double USUAL_VIEW = Math.tan(Math.toRadians(35.0));

	private final SmoothAngle azimuth = new SmoothAngle();
	private final Smooth elevation = new Smooth();
	private final Smooth distance = new Smooth();
	private final Smooth fov = new Smooth();
	private final SmoothVec look = new SmoothVec();
	private final SmoothVec anchor = new SmoothVec();
	private final boolean zoomIsCloseness;
	private final Quaternionf rotation = new Quaternionf();
	private double arm = 1.0;
	private boolean viewBlocked;
	private double softTime;
	/**
	 * seconds the way out has been free. The camera of a player at a screen waits before it backs out again:
	 * past the edge of a block the way is free and blocked by turns, and it would go back and forth
	 */
	private double clearTime;
	private boolean softArmed;
	private double sinceTransition = BLEND_TIME;
	private boolean ready;
	private Vec3 position = Vec3.ZERO;

	/**
	 * @return if something thin is between the camera and the player right now, and the camera waits for it to pass
	 */
	public Rig() {
		this(false);
	}

	/**
	 * @param zoomIsCloseness if a camera that is zoomed in is taken to be as close as it looks to be, for what of
	 *                        the player it shows: zoomed in from far away it fits as little of them as from close by
	 */
	public Rig(boolean zoomIsCloseness) {
		this.zoomIsCloseness = zoomIsCloseness;
	}

	/**
	 * For a camera that shows the player through what is in the way. It stays where the shot has it, behind
	 * blocks and in leaves, glass or plants as well. Only not inside a block that nothing is seen through: there
	 * is no picture from in there, and it stops in front of it, at once and not in a move through it.
	 *
	 * @param clear how far out the way from the player is free
	 * @return how far out the camera goes, 1 for all the way to where the shot has it
	 */
	public static double outOfSolid(Subject subject, Vec3 wanted, double clear) {
		final double length = wanted.distanceTo(subject.center);
		if (length < 1.0E-3) {
			return clear;
		}
		final Level level = subject.player.level();
		final double step = SOLID_STEP * subject.unit / length;
		for (double out = 1.0; out > clear; out -= step) {
			if (!level.getBlockState(BlockPos.containing(subject.center.lerp(wanted, out))).isSolidRender()) {
				return out < 1.0 ? Math.max(clear, out - step) : 1.0;
			}
		}
		return clear;
	}

	public Vec3 position() {
		return position;
	}

	public Quaternionf rotation() {
		return rotation;
	}

	public double fov() {
		return fov.get();
	}

	public double arm() {
		return arm;
	}

	/**
	 * @return if something is between the player and where the shot has the camera
	 */
	public boolean viewBlocked() {
		return viewBlocked;
	}

	public boolean lookingPast() {
		return softTime > 0;
	}

	/**
	 * @return if the rig has a valid camera position, that could be blended from
	 */
	public boolean ready() {
		return ready;
	}

	public void reset() {
		ready = false;
	}

	/**
	 * hard cut, the camera is at the shots position on the next update
	 */
	public void snap(Shot shot, Subject subject) {
		anchor.reset(subject.center);
		azimuth.reset(shot.azimuth);
		elevation.reset(shot.elevation);
		distance.reset(shot.distance);
		fov.reset(shot.fov);
		look.reset(shot.lookTarget);
		arm = 1.0;
		softTime = 0;
		softArmed = false;
		sinceTransition = BLEND_TIME;
		ready = true;
	}

	/**
	 * the player jumped to a new place, jump with them and keep the framing
	 */
	public void rebase(Subject subject) {
		anchor.reset(subject.center);
		look.reset(subject.center);
		arm = 1.0;
		softTime = 0;
		softArmed = false;
	}

	/**
	 * the camera swings over to the next shot, instead of jumping there
	 */
	public void blend() {
		sinceTransition = 0;
	}

	/**
	 * cut to the shot, but with the camera at the given position
	 */
	public void adopt(Vec3 cameraPos, Shot shot, Subject subject) {
		snap(shot, subject);
		final Vec3 offset = cameraPos.subtract(subject.center);
		azimuth.reset(CamMath.azimuthOf(offset));
		elevation.reset(CamMath.elevationOf(offset));
		distance.reset(offset.length());
	}

	/**
	 * @param point what the camera looked at before the rig had it: it goes on from there, and does not start
	 *              over from the middle of the player
	 */
	public void lookFrom(Vec3 point) {
		look.reset(point);
	}

	public void update(Shot shot, Subject subject, double dt, CameraConfig config) {
		sinceTransition += dt;

		final double lag = config.positionLag * (1.0 + 2.5 * (1.0 - CamMath.smoothstep(sinceTransition / BLEND_TIME)));

		final Vec3 center = anchor.update(subject.center, ANCHOR_LAG, dt).add(subject.velocity.scale(ANCHOR_LAG));

		Vec3 wanted;
		if (shot.isWorld()) {
			wanted = shot.worldPos;
		} else {
			wanted = shot.position(center, subject,
					azimuth.update(shot.azimuth, lag, dt),
					elevation.update(shot.elevation, lag, dt),
					distance.update(shot.distance, lag, dt));
		}

		final double clear = WorldProbe.armFraction(subject, subject.center, wanted, config);
		viewBlocked = clear < 0.999;
		final boolean through = subject.seenThrough;
		final double free = through ? outOfSolid(subject, wanted, clear) : clear;
		if (through) {
			arm = free;
		} else if (free < arm) {
			final Vec3 current = subject.center.lerp(wanted, arm);

			if (softArmed && softTime < config.softOcclusionTime && WorldProbe.spotFree(subject, current, config) &&
					WorldProbe.thin(subject, subject.center, current)) {
				softTime += dt;
			} else {
				arm = free;
				clearTime = 0;
			}
		} else {
			softTime = 0;
			softArmed = true;
			clearTime += dt;
			if (dt > 0 && (!subject.atScreen || clearTime > BACK_OUT_AFTER)) {
				arm += (free - arm) * (1.0 - Math.exp(-dt / 0.6));
			}
		}
		position = subject.center.lerp(wanted, arm);

		Vec3 aim = shot.lookTarget;
		if (config.faceDistance > 0 && !shot.exactAim()) {
			final double near = config.faceDistance * subject.unit;
			double away = position.distanceTo(subject.center);
			if (zoomIsCloseness) {
				away *= Math.tan(Math.toRadians(fov.get()) / 2.0) / USUAL_VIEW;
			}
			final double closeness = 1.0 - CamMath.smoothstep((away - near) / near);
			aim = aim.add(subject.head.subtract(subject.center).scale(closeness));
		}
		if (config.leadRoom > 0 && !shot.exactAim()) {
			Vec3 lead = new Vec3(subject.velocity.x, 0, subject.velocity.z).scale(config.leadRoom);
			final double max = config.leadRoomMax * subject.unit;
			if (lead.length() > max) {
				lead = lead.normalize().scale(max);
			}
			aim = aim.add(lead);
		}
		final Vec3 target = look.update(aim, config.lookLag, dt)
				.add(subject.velocity.scale(config.lookLag));
		CamMath.lookRotation(target.subtract(position), rotation);

		fov.update(shot.fov, 0.5, dt);
	}
}
