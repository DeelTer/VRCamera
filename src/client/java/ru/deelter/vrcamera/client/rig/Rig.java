package ru.deelter.vrcamera.client.rig;

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
	// seconds a blended transition is slowed down for
	private static final double BLEND_TIME = 1.6;
	// seconds the smoothed player position lags behind
	private static final double ANCHOR_LAG = 0.18;

	private final SmoothAngle azimuth = new SmoothAngle();
	private final Smooth elevation = new Smooth();
	private final Smooth distance = new Smooth();
	private final Smooth fov = new Smooth();
	private final SmoothVec look = new SmoothVec();
	private final SmoothVec anchor = new SmoothVec();

	// fraction of the way from the player to the wanted position the camera is at, lower when walls are in the way
	private double arm = 1.0;
	// seconds the camera is looking past something thin, instead of moving in front of it
	private double softTime;
	// looking past things is only allowed once the camera had a clear view, not right after a jump to a new place
	private boolean softArmed;
	private double sinceTransition = BLEND_TIME;
	private boolean ready;

	private Vec3 position = Vec3.ZERO;
	private final Quaternionf rotation = new Quaternionf();

	public Vec3 position() {
		return this.position;
	}

	public Quaternionf rotation() {
		return this.rotation;
	}

	public double fov() {
		return this.fov.get();
	}

	public double arm() {
		return this.arm;
	}

	/**
	 * @return if something thin is between the camera and the player right now, and the camera waits for it to pass
	 */
	public boolean lookingPast() {
		return this.softTime > 0;
	}

	/**
	 * @return if the rig has a valid camera position, that could be blended from
	 */
	public boolean ready() {
		return this.ready;
	}

	public void reset() {
		this.ready = false;
	}

	/**
	 * hard cut, the camera is at the shots position on the next update
	 */
	public void snap(Shot shot, Subject subject) {
		this.anchor.reset(subject.center);
		this.azimuth.reset(shot.azimuth);
		this.elevation.reset(shot.elevation);
		this.distance.reset(shot.distance);
		this.fov.reset(shot.fov);
		this.look.reset(shot.lookTarget);
		this.arm = 1.0;
		this.softTime = 0;
		this.softArmed = false;
		this.sinceTransition = BLEND_TIME;
		this.ready = true;
	}

	/**
	 * the player jumped to a new place, jump with them and keep the framing
	 */
	public void rebase(Subject subject) {
		this.anchor.reset(subject.center);
		this.look.reset(subject.center);
		this.arm = 1.0;
		this.softTime = 0;
		this.softArmed = false;
	}

	/**
	 * the camera swings over to the next shot, instead of jumping there
	 */
	public void blend() {
		this.sinceTransition = 0;
	}

	/**
	 * cut to the shot, but with the camera at the given position
	 */
	public void adopt(Vec3 cameraPos, Shot shot, Subject subject) {
		snap(shot, subject);
		Vec3 offset = cameraPos.subtract(subject.center);
		this.azimuth.reset(CamMath.azimuthOf(offset));
		this.elevation.reset(CamMath.elevationOf(offset));
		this.distance.reset(offset.length());
	}

	public void update(Shot shot, Subject subject, double dt, CameraConfig config) {
		this.sinceTransition += dt;
		// take it slow right after a blend started, so the swing is a visible move and not a jerk
		double lag = config.positionLag * (1.0 + 2.5 * (1.0 - CamMath.smoothstep(this.sinceTransition / BLEND_TIME)));

		// smooth the player position, to not pass on every head bob, and add the lag back that causes
		Vec3 center = this.anchor.update(subject.center, ANCHOR_LAG, dt).add(subject.velocity.scale(ANCHOR_LAG));

		Vec3 wanted;
		if (shot.isWorld()) {
			wanted = shot.worldPos;
		} else {
			// smoothing the orbit instead of the position makes the camera swing around the player, and not through them
			wanted = shot.position(center, subject,
				this.azimuth.update(shot.azimuth, lag, dt),
				this.elevation.update(shot.elevation, lag, dt),
				this.distance.update(shot.distance, lag, dt));
		}

		// pull the camera in front of anything between it and the player, that is instant, moving back out is slow.
		// this also guarantees a free line of sight to the player
		double free = WorldProbe.armFraction(subject, subject.center, wanted, config);
		if (free < this.arm) {
			Vec3 current = subject.center.lerp(wanted, this.arm);
			// a tree or post passing through the view is over in a moment, jumping in front of it would look worse.
			// only when the camera itself is in the open, it never stays inside a block
			if (this.softArmed && this.softTime < config.softOcclusionTime && WorldProbe.spotFree(subject, current, config) &&
				WorldProbe.thin(subject, subject.center, current))
			{
				this.softTime += dt;
			} else {
				this.arm = free;
			}
		} else {
			this.softTime = 0;
			this.softArmed = true;
			if (dt > 0) {
				this.arm += (free - this.arm) * (1.0 - Math.exp(-dt / 0.6));
			}
		}
		this.position = subject.center.lerp(wanted, this.arm);

		Vec3 aim = shot.lookTarget;
		if (config.leadRoom > 0 && !shot.exactAim()) {
			// aim a bit ahead of a moving player, so the frame has more room where they are going
			Vec3 lead = new Vec3(subject.velocity.x, 0, subject.velocity.z).scale(config.leadRoom);
			double max = config.leadRoomMax * subject.unit;
			if (lead.length() > max) {
				lead = lead.normalize().scale(max);
			}
			aim = aim.add(lead);
		}
		Vec3 target = this.look.update(aim, config.lookLag, dt)
			.add(subject.velocity.scale(config.lookLag));
		CamMath.lookRotation(target.subtract(this.position), this.rotation);

		this.fov.update(shot.fov, 0.5, dt);
	}
}
