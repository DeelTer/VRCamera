package ru.deelter.vrcamera.client.director;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.ShotConfig;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.rig.Rig;
import ru.deelter.vrcamera.client.rig.Subject;
import ru.deelter.vrcamera.client.rig.WorldProbe;
import ru.deelter.vrcamera.client.shot.Shot;
import ru.deelter.vrcamera.client.shot.ShotType;

import java.util.Random;

/**
 * Decides which shot is shown and when it is replaced. Replaces shots instead of steering around obstacles, when the
 * player gets blocked from view, it switches to an angle that can see them.
 */
public final class Director {
	// seconds after a new shot, in which a blocked view is tolerated
	private static final double OCCLUSION_GRACE = 1.0;

	/**
	 * something happening to the player, that gets its own shot for as long as it lasts
	 */
	public enum Event {
		NONE(null),
		DEATH(ShotType.DEATH),
		FALL(ShotType.FALL);

		final ShotType shot;

		Event(ShotType shot) {
			this.shot = shot;
		}
	}

	private final CameraConfig config;
	private final Random random = new Random();

	private Shot current;
	private ShotType lastType;
	private boolean hold;
	private boolean forceNext;
	// the only shot type allowed on the next pick, asked for by the player
	private ShotType forceType;
	private double occludedTime;
	private String lastReason = "";

	private Context context = Context.IDLE;
	// context the current shot was picked for
	private Context shotContext = Context.IDLE;
	private Event event = Event.NONE;
	// shot type that gets a better chance on the next pick
	private ShotType boost;
	private boolean tight;
	private double tightTimer;
	private double combatTimer;
	private double mineTimer;
	private double fallTimer;
	private double stillTime;

	// candidate search state
	private Shot best;
	private double bestScore;

	public Director(CameraConfig config) {
		this.config = config;
	}

	public Shot current() {
		return this.current;
	}

	public Context context() {
		return this.context;
	}

	public Event event() {
		return this.event;
	}

	public boolean isTight() {
		return this.tight;
	}

	public boolean isHolding() {
		return this.hold;
	}

	public double occludedTime() {
		return this.occludedTime;
	}

	/**
	 * @return why the current shot was picked
	 */
	public String lastReason() {
		return this.lastReason;
	}

	public void reset() {
		this.current = null;
		this.hold = false;
		this.forceNext = false;
		this.forceType = null;
		this.event = Event.NONE;
	}

	/**
	 * replaces the current shot on the next update
	 */
	public void next() {
		this.forceNext = true;
	}

	/**
	 * replaces the current shot with one of the given type on the next update
	 */
	public void force(ShotType type) {
		this.forceType = type;
		this.forceNext = true;
	}

	/**
	 * @return if the current shot is now kept until released
	 */
	public boolean toggleHold() {
		this.hold = !this.hold;
		return this.hold;
	}

	/**
	 * shows a hand placed shot for a while, before the regular rotation continues
	 */
	public void showManual(Shot shot) {
		shot.duration = this.config.manualHoldSeconds;
		this.current = shot;
		this.lastType = shot.type;
		this.shotContext = this.context;
		this.occludedTime = -OCCLUSION_GRACE;
		this.lastReason = "manual";
	}

	public void update(Subject subject, Rig rig, double dt) {
		updateContext(subject, dt);

		Event newEvent = this.hold ? Event.NONE : detectEvent(subject, dt);
		boolean eventOver = newEvent == Event.NONE && this.event != Event.NONE;
		if (newEvent != this.event) {
			this.event = newEvent;
			if (newEvent != Event.NONE) {
				startEvent(subject, rig);
			}
		}
		if (this.event != Event.NONE && this.current != null && rig.ready()) {
			// the event shot stays for as long as the event lasts
			if (subject.teleported) {
				rig.rebase(subject);
			}
			this.current.update(subject, this.config, dt);
			return;
		}

		String reason = null;
		boolean cut = false;
		if (this.current == null || !rig.ready()) {
			reason = "start";
			cut = true;
		} else if (subject.teleported && this.current.isWorld()) {
			reason = "teleport";
			cut = true;
		} else {
			if (subject.teleported) {
				// same shot at the new place, a new shot on every teleport hop would be too restless
				rig.rebase(subject);
				this.occludedTime = -OCCLUSION_GRACE;
			}
			double limit = this.current.isWorld() ? 0.97 : this.config.occlusionRatio;
			if (rig.arm() < limit) {
				this.occludedTime += dt;
			} else {
				// free again, also lets the grace period run out
				this.occludedTime = Math.min(0, this.occludedTime + dt);
			}
			boolean blocked = this.occludedTime > this.config.occlusionCutTime;
			boolean finished = this.current.finished(subject);

			if (this.forceNext) {
				reason = this.forceType != null ? "asked for " + this.forceType : "key";
			} else if (eventOver) {
				reason = "event over";
			} else if (this.hold) {
				// a camera that stays behind can't be held forever
				if (this.current.isWorld() && (blocked || finished)) {
					reason = "held shot ended";
					cut = true;
				}
			} else if (blocked) {
				reason = "blocked";
				// swinging the camera through the wall that blocks it would not look good
				cut = true;
			} else if (finished) {
				reason = "finished";
			} else if (this.current.age >= this.current.duration) {
				reason = "time";
			} else if (this.current.age > this.config.minShotTime && this.current.type != ShotType.CUSTOM) {
				if (this.current.type.weight(this.context) <= 0) {
					reason = "unfit for " + this.context;
				} else if (activity(this.context) != activity(this.shotContext)) {
					reason = "now " + this.context;
				}
			}
		}

		if (reason != null) {
			choose(subject, rig, cut, reason);
		}
		this.current.update(subject, this.config, dt);
	}

	/**
	 * @return the context, with all the regular moving around on foot counted as one
	 */
	private static Context activity(Context context) {
		return context == Context.IDLE || context == Context.RUN ? Context.WALK : context;
	}

	private Event detectEvent(Subject subject, double dt) {
		if (!this.config.events) {
			return Event.NONE;
		}
		LocalPlayer player = subject.player;
		if (player.isDeadOrDying()) {
			return Event.DEATH;
		}
		boolean falling = player.fallDistance > 5.0 && !player.onGround() && !player.isFallFlying() &&
			!player.isInWater() && !player.isPassenger();
		if (falling) {
			// also show the landing
			this.fallTimer = 1.0;
		} else {
			this.fallTimer -= dt;
		}
		return this.fallTimer > 0 ? Event.FALL : Event.NONE;
	}

	private void startEvent(Subject subject, Rig rig) {
		ShotType type = this.event.shot;
		ShotConfig shotConfig = this.config.shot(type);
		Shot shot = new Shot(type, shotConfig != null ? shotConfig : type.defaults(),
			this.random.nextBoolean() ? 1 : -1);
		shot.start(subject, this.config);

		if (this.current != null && rig.ready() && !this.current.isWorld() && !subject.teleported &&
			!"cut".equals(this.config.transition))
		{
			rig.blend();
		} else {
			rig.snap(shot, subject);
		}
		this.current = shot;
		this.lastType = type;
		this.shotContext = this.context;
		this.occludedTime = -OCCLUSION_GRACE;
		this.lastReason = "event " + this.event;
	}

	private void choose(Subject subject, Rig rig, boolean cut, String reason) {
		Vec3 viewDir = rig.ready() ? subject.center.subtract(rig.position()).normalize() : null;
		double distanceScale = (this.context == Context.FLY ? 1.6 : 1.0) * (this.tight ? 0.7 : 1.0);
		boolean headInFluid = WorldProbe.inFluid(subject, subject.head);

		this.best = null;
		this.bestScore = 0;
		for (ShotType type : ShotType.values()) {
			if (type == ShotType.CUSTOM) {
				continue;
			}
			ShotConfig shotConfig = this.config.shot(type);
			if (shotConfig == null) {
				continue;
			}
			double weight;
			if (this.forceType != null) {
				// the player asked for this one, it doesn't have to fit the situation or be enabled
				if (type != this.forceType) {
					continue;
				}
				weight = 1.0;
			} else {
				if (!shotConfig.enabled || (type == ShotType.DUEL && subject.targetCenter == null)) {
					continue;
				}
				weight = shotConfig.weight * type.weight(this.context) * (this.tight ? type.tightFactor : 1.0) *
					(type == this.boost ? 4.0 : 1.0);
			}
			if (weight <= 0) {
				continue;
			}
			// try both sides of the player
			for (int side = -1; side <= 1; side += 2) {
				consider(new Shot(type, shotConfig, side), weight, distanceScale, subject, viewDir, headInFluid);
			}
		}
		if (this.forceType == ShotType.CUSTOM) {
			consider(new Shot(ShotType.CUSTOM, this.config.preset(), 1), 1.0, 1.0, subject, viewDir, headInFluid);
		} else if (this.config.customInRotation && this.forceType == null) {
			// hand placed shots only have the side they were placed on
			for (ShotConfig preset : this.config.presets) {
				if (preset.enabled && preset.weight > 0) {
					consider(new Shot(ShotType.CUSTOM, preset, 1), preset.weight, 1.0, subject, viewDir,
						headInFluid);
				}
			}
		}

		Shot next = this.best;
		if (next == null && this.forceType != null) {
			// asked for, so shown even where it has no room, the rig keeps it out of the walls
			ShotType type = this.forceType;
			ShotConfig forced = this.config.shot(type);
			next = new Shot(type, forced != null ? forced : type.defaults(),
				type == ShotType.CUSTOM || this.random.nextBoolean() ? 1 : -1);
			next.distanceScale = distanceScale;
			next.start(subject, this.config);
			reason += ", no room";
		}
		if (next == null) {
			// nothing fits, stay close behind the player, the rig keeps that out of the walls
			ShotConfig fallback = this.config.shot(ShotType.SHOULDER);
			next = new Shot(ShotType.SHOULDER, fallback != null ? fallback : ShotType.SHOULDER.defaults(),
				this.random.nextBoolean() ? 1 : -1);
			next.distanceScale = 0.6;
			next.start(subject, this.config);
			reason += ", nothing fits";
		}

		next.duration = CamMath.lerp(next.config.minDuration, next.config.maxDuration, this.random.nextDouble()) *
			(this.context == Context.COMBAT ? 0.6 : 1.0);

		if (!cut && !next.isWorld() && this.current != null && !this.current.isWorld() && wantsBlend(next)) {
			rig.blend();
			reason += ", blend";
		} else {
			rig.snap(next, subject);
			reason += ", cut";
		}

		this.current = next;
		this.lastType = next.type;
		this.shotContext = this.context;
		this.boost = null;
		this.forceNext = false;
		this.forceType = null;
		this.occludedTime = -OCCLUSION_GRACE;
		this.lastReason = reason;
	}

	/**
	 * rates a shot, and remembers it if it is the best one so far
	 */
	private void consider(
		Shot shot, double weight, double distanceScale, Subject subject, Vec3 viewDir, boolean headInFluid)
	{
		shot.distanceScale = distanceScale;
		shot.start(subject, this.config);

		Vec3 wanted = shot.desiredPosition(subject);
		double free = WorldProbe.armFraction(subject, subject.center, wanted, this.config);
		// a camera that stays in place is only good at the spot it was planned for
		if (shot.isWorld() && free < 0.9) {
			return;
		}
		Vec3 actual = subject.center.lerp(wanted, free);
		if (actual.distanceTo(subject.center) < shot.type.minDistance * subject.unit) {
			return;
		}

		double score = weight * (0.35 + 0.65 * free) * (0.8 + 0.4 * this.random.nextDouble());
		if (!WorldProbe.visible(subject, actual, subject.head)) {
			score *= 0.6;
		}
		// don't film from under water when the player is above, or the other way around
		if (WorldProbe.inFluid(subject, actual) != headInFluid) {
			score *= 0.25;
		}
		if (shot.type == this.lastType) {
			score *= 0.25;
		}
		// a new shot should look clearly different, or it reads as a glitch
		if (viewDir != null &&
			viewDir.dot(subject.center.subtract(actual).normalize()) > Math.cos(Math.toRadians(30)))
		{
			score *= 0.5;
		}
		if (score > this.bestScore) {
			this.bestScore = score;
			this.best = shot;
		}
	}

	private boolean wantsBlend(Shot next) {
		return switch (this.config.transition) {
			case "cut" -> false;
			case "blend" -> true;
			// swinging more than a third around the player takes too long
			default -> this.random.nextDouble() < this.config.blendChance &&
				Math.abs(CamMath.wrap(next.azimuth - this.current.azimuth)) < Math.toRadians(130);
		};
	}

	private void updateContext(Subject subject, double dt) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = subject.player;

		this.combatTimer -= dt;
		this.mineTimer -= dt;
		if (player.swinging && mc.hitResult instanceof EntityHitResult hit &&
			hit.getEntity() instanceof LivingEntity && hit.getEntity().isAlive())
		{
			// the player attacks something, hitting a boat or an item frame is no fight
			this.combatTimer = 5.0;
			subject.target = hit.getEntity();
		} else if (player.hurtTime > 0) {
			this.combatTimer = 5.0;
			if (subject.target == null) {
				subject.target = nearestEnemy(subject);
			}
		}
		if (subject.target != null && (this.combatTimer <= 0 ||
			subject.target.distanceTo(player) > 16.0 + 8.0 * subject.unit
		))
		{
			subject.target = null;
		}
		if (mc.gameMode != null && mc.gameMode.isDestroying()) {
			// long enough to not drop out of it between two blocks
			this.mineTimer = 6.0;
		}
		this.stillTime = subject.speed > 0.5 ? 0 : this.stillTime + dt;

		Context previous = this.context;
		if (player.isFallFlying()) {
			this.context = Context.FLY;
		} else if (player.isPassenger()) {
			this.context = Context.RIDE;
		} else if (this.combatTimer > 0) {
			this.context = Context.COMBAT;
		} else if (this.mineTimer > 0) {
			this.context = Context.MINE;
		} else if (player.isSwimming() || player.isInWater()) {
			this.context = Context.SWIM;
		} else if (subject.speed > 4.8) {
			this.context = Context.RUN;
		} else if (this.stillTime > 1.2) {
			this.context = Context.IDLE;
		} else if (subject.speed > 0.5 || this.context != Context.IDLE) {
			// short stops while walking don't count as standing around
			this.context = Context.WALK;
		}
		if (this.context == Context.FLY && previous != Context.FLY) {
			// show the take off from the ground
			this.boost = ShotType.FLYBY;
		}

		this.tightTimer -= dt;
		if (this.tightTimer <= 0) {
			this.tightTimer = 0.5;
			this.tight = WorldProbe.openness(subject) < 0.55;
		}
	}

	/**
	 * @return the closest hostile mob around the player, or null if there is none
	 */
	private static Entity nearestEnemy(Subject subject) {
		LocalPlayer player = subject.player;
		Entity nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Entity entity : player.level().getEntities(player,
			player.getBoundingBox().inflate(8.0 + 4.0 * subject.unit), e -> e instanceof Enemy && e.isAlive()))
		{
			double distance = entity.distanceToSqr(player);
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearest = entity;
			}
		}
		return nearest;
	}
}
