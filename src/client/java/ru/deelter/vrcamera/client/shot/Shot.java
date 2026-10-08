package ru.deelter.vrcamera.client.shot;

import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.ShotConfig;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.math.SmoothVec;
import ru.deelter.vrcamera.client.rig.Subject;

/**
 * A running shot. Describes where the camera wants to be, the {@link ru.deelter.vrcamera.client.rig.Rig} then moves
 * the camera there and keeps it out of walls.
 */
public final class Shot {
	// seconds the view of the first person shot lags behind the head
	private static final double POV_AIM_LAG = 0.25;

	public final ShotType type;
	public final ShotConfig config;
	/**
	 * -1 or 1, which side of the player the shot is on
	 */
	public final int side;
	// where the first person shot looks, follows the head with a delay to calm it down
	private final SmoothVec aim = new SmoothVec();
	/**
	 * seconds this shot is running
	 */
	public double age;
	/**
	 * seconds this shot should run
	 */
	public double duration = Double.MAX_VALUE;
	public double distanceScale = 1.0;
	/**
	 * asked for by the player, stays even if it does not fit what they are doing
	 */
	public boolean forced;
	// where the camera should be, on an orbit around the player, angles in radians
	public double azimuth;
	public double elevation;
	public double distance;
	public double fov;
	public Vec3 lookTarget = Vec3.ZERO;
	/**
	 * fixed camera position, only for shots that don't move with the player
	 */
	public Vec3 worldPos;
	private double maxRange;
	// seconds the player stood still during this shot
	private double stillTime;
	// if a duel ever had an opponent, one that was asked for without any is not over right away
	private boolean hadTarget;

	public Shot(ShotType type, ShotConfig config, int side) {
		this.type = type;
		this.config = config;
		this.side = side;
	}

	/**
	 * @return if the camera stays in place, instead of moving with the player
	 */
	public boolean isWorld() {
		return this.type == ShotType.FLYBY;
	}

	/**
	 * @return if the camera should aim exactly where the shot says, without leaving room in front of the player
	 */
	public boolean exactAim() {
		return this.type == ShotType.HANDS || this.type == ShotType.DUEL || this.type == ShotType.DEATH ||
				this.type == ShotType.POV || this.type == ShotType.MENU;
	}

	/**
	 * @return if the camera can swing over from or to this shot, instead of jumping
	 */
	public boolean blends() {
		return !isWorld() && !this.type.cutsOnly();
	}

	/**
	 * @return azimuth the camera should be at, for where the player is and looks right now
	 */
	private double wantedAzimuth(Subject subject) {
		double reference = subject.facing;
		Vec3 focus = focus(subject);
		if (focus != null) {
			Vec3 toFocus = focus.subtract(subject.center);
			// with it right on top of the player there is no direction to it
			if (toFocus.horizontalDistance() > 0.5 * subject.unit) {
				// relative to it, so the camera ends up behind the player, looking at both
				reference = CamMath.azimuthOf(toFocus);
			}
		}
		return reference + Math.toRadians(this.config.azimuth) * this.side;
	}

	/**
	 * @return what this shot shows together with the player, null for the shots that only show the player
	 */
	private Vec3 focus(Subject subject) {
		return switch (this.type) {
			case DUEL -> subject.targetCenter;
			case MENU -> subject.guiCenter;
			default -> null;
		};
	}

	public void start(Subject subject, CameraConfig config) {
		this.age = 0;
		this.stillTime = 0;
		this.azimuth = wantedAzimuth(subject);
		this.aim.reset(subject.headDir);

		if (this.type == ShotType.FLYBY) {
			// stand next to the path ahead of the player
			Vec3 dir = CamMath.forward(subject.facing);
			Vec3 horizontal = new Vec3(subject.velocity.x, 0, subject.velocity.z);
			if (horizontal.length() > 1.0) {
				dir = horizontal.normalize();
			}
			Vec3 right = new Vec3(-dir.z, 0, dir.x);
			double base = this.config.distance * subject.unit * this.distanceScale;
			double lead = base * CamMath.clamp(0.6 + subject.speed / 8.0, 0.6, 2.2);
			this.worldPos = subject.center
					.add(dir.scale(lead))
					.add(right.scale(this.side * 0.4 * base))
					.add(0, 0.3 * subject.unit, 0);
			this.maxRange = Math.max(lead * 2.2, 10.0 * subject.unit);
		}
		update(subject, config, 0);
	}

	public void update(Subject subject, CameraConfig config, double dt) {
		this.age += dt;
		this.stillTime = subject.speed < 0.5 ? this.stillTime + dt : Math.max(0, this.stillTime - 2.0 * dt);

		// first person has to keep its distance, any closer and the camera is inside the head of the player model
		double scale = this.type == ShotType.POV ? 1.0 : this.distanceScale;
		double baseDistance = this.config.distance * subject.unit * scale;
		this.elevation = Math.toRadians(this.config.elevation);
		this.distance = baseDistance;
		this.fov = this.config.fov;
		this.lookTarget = subject.center;

		if (this.type == ShotType.FLYBY) {
			Vec3 offset = this.worldPos.subtract(subject.center);
			this.distance = offset.length();
			this.azimuth = CamMath.azimuthOf(offset);
			this.elevation = CamMath.elevationOf(offset);
			// zoom in to keep the player at a similar size in frame
			this.fov = CamMath.clamp(Math.toDegrees(2.0 * Math.atan(2.9 * subject.unit / this.distance)), 20.0,
					this.config.fov);
			return;
		}

		if (this.type.orbits()) {
			this.azimuth += this.side * Math.toRadians(config.orbitSpeed) * dt;
		} else {
			// swing around when the player turns, but ignore small head movements
			// these have to stay lined up with what they show
			boolean tight = focus(subject) != null;
			double error = CamMath.wrap(wantedAzimuth(subject) - this.azimuth);
			double deadzone = Math.toRadians(tight ? 4.0 : config.turnDeadzone);
			double lag = Math.max(0.01, tight ? config.turnLag * 0.6 : config.turnLag);
			if (Math.abs(error) > deadzone && dt > 0) {
				this.azimuth += (error - Math.signum(error) * deadzone) * (1.0 - Math.exp(-dt / lag));
			}
		}

		switch (this.type) {
			case CRANE -> {
				double progress = CamMath.smoothstep(this.age / Math.max(1.0, this.config.maxDuration));
				this.elevation *= CamMath.lerp(0.5, 1.0, progress);
				this.distance *= CamMath.lerp(0.6, 1.0, progress);
			}
			case FRONT -> this.distance *= CamMath.lerp(1.0, 0.72, CamMath.smoothstep(this.stillTime / 8.0));
			case DEATH -> {
				this.distance *= CamMath.lerp(1.0, 1.6, CamMath.smoothstep(this.age / 8.0));
				if (subject.targetCenter != null) {
					// keep what killed the player in the picture
					this.lookTarget = subject.center.lerp(subject.targetCenter, 0.35);
				}
			}
			case POV -> {
				Vec3 aim = this.aim.update(subject.headDir, POV_AIM_LAG, dt);
				this.lookTarget = subject.head.add(aim.scale(8.0 * subject.unit));
			}
			case MENU -> {
				if (subject.guiCenter != null) {
					// the menu is what is of interest, the player only has to be in the picture
					this.lookTarget = subject.center.lerp(subject.guiCenter, 0.8);
				}
			}
			case HANDS -> {
				if (subject.hands.distanceTo(subject.center) < 1.5 * subject.unit) {
					this.lookTarget = subject.center.lerp(subject.hands, 0.7);
				}
			}
			case DUEL -> {
				if (subject.targetCenter != null) {
					this.hadTarget = true;
					// far enough back to have both in frame, aimed between them
					this.distance += 0.35 * subject.targetCenter.distanceTo(subject.center);
					this.lookTarget = subject.center.lerp(subject.targetCenter, 0.4);
				}
			}
			default -> {
			}
		}

		if (config.speedFov) {
			this.fov += CamMath.clamp((subject.speed - 6.0) * 0.8, 0.0, 14.0);
		}
	}

	/**
	 * @return if the shot has nothing more to show
	 */
	public boolean finished(Subject subject) {
		return switch (this.type) {
			case FLYBY -> this.age > 1.5 && this.distance > this.maxRange;
			case DUEL -> this.hadTarget && subject.targetCenter == null;
			default -> false;
		};
	}

	/**
	 * @return camera position this shot would have for the given orbit values
	 */
	public Vec3 position(Vec3 center, Subject subject, double azimuth, double elevation, double distance) {
		if (this.type == ShotType.POV) {
			// not at the eyes but in front of the face, or the head of the player model would be all there is to see
			Vec3 aim = this.aim.get();
			Vec3 ahead = new Vec3(aim.x, 0, aim.z);
			ahead = ahead.length() < 0.2 ? CamMath.forward(subject.facing) : ahead.normalize();
			// center is the smoothed place of the player, take the same smoothing for the head
			return subject.head.add(center.subtract(subject.center)).add(ahead.scale(distance));
		}
		Vec3 pos = center.add(CamMath.orbit(azimuth, elevation).scale(distance));
		if (this.type == ShotType.LOW) {
			// don't dig into the ground
			pos = new Vec3(pos.x, Math.max(pos.y, subject.feet.y + 0.25 * subject.unit), pos.z);
		}
		return pos;
	}

	public Vec3 desiredPosition(Subject subject) {
		return isWorld() ? this.worldPos :
				position(subject.center, subject, this.azimuth, this.elevation, this.distance);
	}
}
