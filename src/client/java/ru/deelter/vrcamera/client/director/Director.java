package ru.deelter.vrcamera.client.director;

import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.ShotConfig;
import ru.deelter.vrcamera.client.config.Transition;
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

	// shots are further away in flight, and closer where there is little room
	private static final double FLY_DISTANCE_SCALE = 1.6;
	private static final double TIGHT_DISTANCE_SCALE = 0.7;
	// the shot shown when nothing else has room
	private static final double FALLBACK_DISTANCE_SCALE = 0.6;
	private static final double BOOST = 4.0;
	// how well first person fits where it is tight, it does not fit anywhere else
	private static final double TIGHT_POV_FIT = 0.6;
	private static final double SCREEN_POV_FIT = 0.2;
	// how well the shot of two fits while the player films with a friend: it is what they asked for
	private static final double PARTNER_FIT = 3.0;
	// shots between two stays in first person, this many or one more
	private static final int HOME_ASIDES = 2;
	// fights are cut faster
	private static final double COMBAT_DURATION_SCALE = 0.6;

	// what makes a candidate for the next shot less likely to be picked
	private static final double HIDDEN_HEAD_PENALTY = 0.6;
	private static final double ACROSS_WATER_PENALTY = 0.25;
	private static final double SAME_TYPE_PENALTY = 0.25;
	private static final double SIMILAR_VIEW_PENALTY = 0.5;
	private static final double CROSSED_LINE_PENALTY = 0.35;
	// a view has to turn by more than 30 degrees to count as a different one
	private static final double SIMILAR_VIEW_DOT = Math.cos(Math.toRadians(30));
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
	private boolean atScreen;
	private boolean partnered;
	// other shots left to show before first person is come back to
	private int asides;
	private double tightTimer;
	private double combatTimer;
	// seconds of recent mining, goes up while a block is being broken, slowly down otherwise
	private double mineTime;
	private double fallTimer;
	// an event the player wanted to see something else instead of, ignored until it is over
	private Event dismissed = Event.NONE;
	private double stillTime;
	// what the player hit since the last update
	private Entity attacked;
	public Director(CameraConfig config) {
		this.config = config;
	}

	/**
	 * @return the context, with all the regular moving around on foot counted as one
	 */
	private static Context activity(Context context) {
		return context == Context.IDLE || context == Context.RUN ? Context.WALK : context;
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
		this.dismissed = Event.NONE;
		this.boost = null;
		this.attacked = null;
		this.combatTimer = 0;
		this.mineTime = 0;
		this.fallTimer = 0;
	}

	/**
	 * replaces the current shot on the next update
	 */
	public void next() {
		this.forceNext = true;
	}

	/**
	 * the player hit something
	 */
	public void onAttack(Entity entity) {
		this.attacked = entity;
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

		// by hand the player decides on every shot, like on one they asked to keep
		boolean held = this.hold || this.config.directorManual;
		Event detected = held ? Event.NONE : detectEvent(subject, dt);
		if (detected != this.dismissed) {
			// what was dismissed is over
			this.dismissed = Event.NONE;
		}
		if (this.forceNext && detected != Event.NONE) {
			// the player asked for another shot, that goes before the event
			this.dismissed = detected;
		}
		Event newEvent = detected == this.dismissed ? Event.NONE : detected;
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
			} else if (held) {
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
			} else if (this.current.age > this.config.minShotTime && this.current.type != ShotType.CUSTOM &&
					!this.current.forced && !(isHome() && this.current.type == ShotType.POV)) {
				Context now = activity(this.context);
				if (fit(this.current.type) <= 0) {
					reason = "unfit for " + this.context;
				} else if (now != Context.WALK && now != activity(this.shotContext)) {
					// only when something starts. When a fight or a flight is over the shot may stay, a change of
					// shot on both ends would be too restless
					reason = "now " + this.context;
				}
			}
		}

		if (reason != null) {
			choose(subject, rig, cut, reason);
		}
		this.current.update(subject, this.config, dt);
	}

	private Event detectEvent(Subject subject, double dt) {
		if (!this.config.events) {
			return Event.NONE;
		}
		Player player = subject.player;
		if (player.isDeadOrDying()) {
			return Event.DEATH;
		}
		boolean falling = false;
		if (!player.onGround() && !player.isFallFlying() && !player.isInWater() && !player.isPassenger() &&
				!player.getAbilities().flying) {
			if (player.fallDistance > 5.0) {
				falling = true;
			} else if (player.getDeltaMovement().y < -0.3) {
				// just went over an edge, don't wait until most of the fall is over
				falling = WorldProbe.groundDistance(subject, 24.0) > 6.0 * subject.unit;
			}
		}
		if (falling) {
			// also show the landing
			this.fallTimer = 1.0;
		} else {
			this.fallTimer -= dt;
		}
		if (this.fallTimer > 0) {
			return Event.FALL;
		}
		return subject.guiCenter != null ? Event.MENU : Event.NONE;
	}

	private void startEvent(Subject subject, Rig rig) {
		Shot shot = eventShot(subject);

		// a fall is over before the camera could swing there
		if (this.event != Event.FALL && shot.blends() && this.current != null && rig.ready() && this.current.blends() &&
				!subject.teleported && this.config.transition != Transition.CUT) {
			rig.blend();
		} else {
			rig.snap(shot, subject);
		}
		this.current = shot;
		this.lastType = shot.type;
		this.shotContext = this.context;
		this.occludedTime = -OCCLUSION_GRACE;
		this.lastReason = "event " + this.event + (shot.type == this.event.shot ? "" : ", no room");
	}

	/**
	 * @return the shot for the event that just started
	 */
	private Shot eventShot(Subject subject) {
		ShotType type = this.event.shot;
		ShotConfig shotConfig = this.config.shot(type);
		if (this.event != Event.MENU) {
			Shot shot = new Shot(type, shotConfig, randomSide());
			shot.start(subject, this.config);
			return shot;
		}

		// A menu is opened anywhere, also with the back to a wall. Take the shoulder with more room behind it
		Shot best = null;
		double bestRoom = -1;
		for (int side = -1; side <= 1; side += 2) {
			Shot shot = new Shot(type, shotConfig, side);
			shot.start(subject, this.config);
			Vec3 wanted = shot.desiredPosition(subject);
			double room = wanted.distanceTo(subject.center) *
					WorldProbe.armFraction(subject, subject.center, wanted, this.config);
			if (room > bestRoom) {
				bestRoom = room;
				best = shot;
			}
		}
		if (bestRoom < type.minDistance * subject.unit && this.config.shot(ShotType.POV).enabled) {
			// no room on either side, the camera would end up inside the player. In first person the menu is
			// right in front of the camera anyway
			Shot shot = new Shot(ShotType.POV, this.config.shot(ShotType.POV), 1);
			shot.start(subject, this.config);
			return shot;
		}
		return best;
	}

	private void choose(Subject subject, Rig rig, boolean cut, String reason) {
		Selection selection = new Selection(subject, rig);
		double distanceScale = (this.context == Context.FLY ? FLY_DISTANCE_SCALE : 1.0) *
				(this.tight ? TIGHT_DISTANCE_SCALE : 1.0);

		if (this.forceType == ShotType.CUSTOM) {
			selection.consider(new Shot(ShotType.CUSTOM, this.config.preset(), 1), 1.0, 1.0);
		} else if (this.forceType != null) {
			// the player asked for this one, it doesn't have to fit the situation or be enabled
			selection.considerBothSides(this.forceType, 1.0, distanceScale);
		} else if (isHome() && this.asides <= 0 && (this.current == null || this.current.type != ShotType.POV)) {
			selection.considerBothSides(ShotType.POV, 1.0, distanceScale);
		} else {
			for (ShotType type : ShotType.values()) {
				ShotConfig shotConfig = this.config.shot(type);
				if (type == ShotType.CUSTOM || !shotConfig.enabled || (type == ShotType.POV && isHome()) ||
						(type == ShotType.DUEL && subject.targetCenter == null) ||
						(type == ShotType.HANDS && !subject.tracksHands)) {
					continue;
				}
				double weight = shotConfig.weight * fit(type) * (type == this.boost ? BOOST : 1.0);
				if (weight > 0) {
					selection.considerBothSides(type, weight, distanceScale);
				}
			}
			if (this.config.customInRotation) {
				// hand placed shots only have the side they were placed on
				for (ShotConfig preset : this.config.presets) {
					if (preset.enabled && preset.weight > 0) {
						selection.consider(new Shot(ShotType.CUSTOM, preset, 1), preset.weight, 1.0);
					}
				}
			}
		}

		Shot next = selection.best;
		if (next == null) {
			reason += ", no room";
			next = fallback(subject, distanceScale);
		}

		next.forced = this.forceType != null;
		next.duration = CamMath.lerp(next.config.minDuration, next.config.maxDuration, this.random.nextDouble()) *
				(this.context == Context.COMBAT ? COMBAT_DURATION_SCALE : 1.0);
		if (isHome() && !next.forced) {
			if (next.type == ShotType.POV) {
				next.duration = this.config.povHomeSeconds;
				this.asides = HOME_ASIDES + this.random.nextInt(2);
			} else {
				this.asides--;
			}
		}

		if (next == this.current) {
			// still the only shot with room, carry on with it
			next.age = 0;
			reason += ", kept";
		} else if (!cut && next.blends() && this.current != null && this.current.blends() && wantsBlend(next)) {
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
	 * @return if first person is where the director of a player at a screen comes back to and stays, with a few
	 * other shots in between
	 */
	private boolean isHome() {
		return this.config.povHome && this.atScreen;
	}

	/**
	 * @return how well a shot fits what the player is doing and where, 0 means it should not be used
	 */
	private double fit(ShotType type) {
		if (type == ShotType.POV) {
			// At a screen it is the view the player has themselves, with their hand and what they do with it: worth
			// a cut now and then. In VR it is the shaking view of a headset, for where nothing else fits
			return this.tight ? TIGHT_POV_FIT : this.atScreen ? SCREEN_POV_FIT : 0.0;
		}
		if (type == ShotType.DUEL && this.partnered) {
			// the shot that has two in the picture, for the friend the player films with
			return PARTNER_FIT;
		}
		return type.weight(this.context) * (this.tight ? type.tightFactor : 1.0);
	}

	/**
	 * @return the shot to show when none has room. The rig keeps the camera out of the walls on any shot, but it
	 * does that by moving it closer to the player, up to inside of them
	 */
	private Shot fallback(Subject subject, double distanceScale) {
		if (this.forceType != null) {
			// asked for, so shown anyway
			Shot shot = new Shot(this.forceType, this.config.shot(this.forceType),
					this.forceType == ShotType.CUSTOM ? 1 : randomSide());
			shot.distanceScale = distanceScale;
			shot.start(subject, this.config);
			return shot;
		}
		// first person needs no room, without it stay close behind the player
		boolean firstPerson = this.config.shot(ShotType.POV).enabled;
		ShotType type = firstPerson ? ShotType.POV : ShotType.SHOULDER;
		if (this.current != null && this.current.type == type && !this.current.forced) {
			return this.current;
		}
		Shot shot = new Shot(type, this.config.shot(type), randomSide());
		shot.distanceScale = firstPerson ? 1.0 : FALLBACK_DISTANCE_SCALE;
		shot.start(subject, this.config);
		return shot;
	}

	private int randomSide() {
		return this.random.nextBoolean() ? 1 : -1;
	}

	private boolean wantsBlend(Shot next) {
		return switch (this.config.transition) {
			case CUT -> false;
			case BLEND -> true;
			// swinging more than a third around the player takes too long
			case AUTO -> this.random.nextDouble() < this.config.blendChance &&
					Math.abs(CamMath.wrap(next.azimuth - this.current.azimuth)) < Math.toRadians(130);
		};
	}

	private void updateContext(Subject subject, double dt) {
		Minecraft mc = Minecraft.getInstance();
		Player player = subject.player;
		this.partnered = subject.partner != null && subject.target == null && subject.targetCenter != null;

		this.combatTimer -= dt;
		Entity attacked = this.attacked;
		this.attacked = null;
		if (attacked instanceof LivingEntity && attacked.isAlive()) {
			// hitting a boat or an item frame is no fight
			this.combatTimer = 5.0;
			subject.target = attacked;
		} else if (player.hurtTime > 0 || player.isDeadOrDying()) {
			// only what was done by someone is a fight, not falling or burning
			DamageSource source = player.getLastDamageSource();
			Entity attacker = source == null ? null : source.getEntity();
			if (attacker instanceof LivingEntity && attacker != player && attacker.isAlive()) {
				this.combatTimer = 5.0;
				subject.target = attacker;
			}
		}
		if (player.isDeadOrDying() && subject.target != null) {
			// keep the killer for the death shot
			this.combatTimer = 5.0;
		}
		if (subject.target != null && (this.combatTimer <= 0 ||
				subject.target.distanceTo(player) > 16.0 + 8.0 * subject.unit
		)) {
			subject.target = null;
		}
		// counts as mining after a second of it, breaking one block on the way is not worth a change of shot
		if (mc.gameMode != null && mc.gameMode.isDestroying()) {
			this.mineTime = Math.min(3.0, this.mineTime + dt);
		} else {
			this.mineTime = Math.max(0.0, this.mineTime - 0.5 * dt);
		}
		this.stillTime = subject.speed > 0.5 ? 0 : this.stillTime + dt;

		Context previous = this.context;
		if (player.isFallFlying()) {
			this.context = Context.FLY;
		} else if (player.isPassenger()) {
			this.context = Context.RIDE;
		} else if (this.combatTimer > 0) {
			this.context = Context.COMBAT;
		} else if (this.mineTime > 1.0) {
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
		this.atScreen = !subject.tracksHands;
	}

	/**
	 * something happening to the player, that gets its own shot for as long as it lasts
	 */
	public enum Event {
		NONE(null),
		DEATH(ShotType.DEATH),
		FALL(ShotType.FALL),
		/**
		 * the player has an inventory or chest open
		 */
		MENU(ShotType.MENU);

		final ShotType shot;

		Event(ShotType shot) {
			this.shot = shot;
		}
	}

	/**
	 * rates the shots that could come next, and keeps the best one
	 */
	private final class Selection {
		private final Subject subject;
		// direction the camera looks in right now, null if there is no camera position yet
		private final Vec3 viewDir;
		// The line the player moves along has a left and a right side, the camera should stay on one of them from
		// shot to shot. Otherwise the player runs to the right in one shot and to the left in the next.
		// This points to the right side
		private final Vec3 right;
		private final int currentSide;
		private final boolean headInFluid;

		private Shot best;
		private double bestScore;

		Selection(Subject subject, Rig rig) {
			this.subject = subject;
			Vec3 travel = new Vec3(subject.velocity.x, 0, subject.velocity.z);
			travel = travel.length() > 1.0 ? travel.normalize() : CamMath.forward(subject.facing);
			this.right = new Vec3(-travel.z, 0, travel.x);
			this.headInFluid = WorldProbe.inFluid(subject, subject.head);
			if (rig.ready()) {
				this.viewDir = subject.center.subtract(rig.position()).normalize();
				this.currentSide = side(rig.position());
			} else {
				this.viewDir = null;
				this.currentSide = 0;
			}
		}

		void considerBothSides(ShotType type, double weight, double distanceScale) {
			ShotConfig shotConfig = Director.this.config.shot(type);
			consider(new Shot(type, shotConfig, -1), weight, distanceScale);
			consider(new Shot(type, shotConfig, 1), weight, distanceScale);
		}

		void consider(Shot shot, double weight, double distanceScale) {
			shot.distanceScale = distanceScale;
			shot.start(this.subject, Director.this.config);

			Vec3 center = this.subject.center;
			Vec3 wanted = shot.desiredPosition(this.subject);
			double free = WorldProbe.armFraction(this.subject, center, wanted, Director.this.config);
			// a camera that stays in place is only good at the spot it was planned for
			if (shot.isWorld() && free < 0.9) {
				return;
			}
			Vec3 actual = center.lerp(wanted, free);
			if (actual.distanceTo(center) < shot.type.minDistance * this.subject.unit) {
				return;
			}

			double score = weight * (0.35 + 0.65 * free) * (0.8 + 0.4 * Director.this.random.nextDouble());
			if (!WorldProbe.visible(this.subject, actual, this.subject.head)) {
				score *= HIDDEN_HEAD_PENALTY;
			}
			// don't film from under water when the player is above, or the other way around
			if (WorldProbe.inFluid(this.subject, actual) != this.headInFluid) {
				score *= ACROSS_WATER_PENALTY;
			}
			if (shot.type == Director.this.lastType) {
				score *= SAME_TYPE_PENALTY;
			}
			// a new shot should look clearly different, or it reads as a glitch
			if (this.viewDir != null && this.viewDir.dot(center.subtract(actual).normalize()) > SIMILAR_VIEW_DOT) {
				score *= SIMILAR_VIEW_PENALTY;
			}
			if (!shot.type.orbits() && this.currentSide * side(actual) < 0) {
				score *= CROSSED_LINE_PENALTY;
			}
			if (score > this.bestScore) {
				this.bestScore = score;
				this.best = shot;
			}
		}

		/**
		 * @return on which side of the line the player moves along a camera position is: -1, 1, or 0 if it is
		 * close to that line
		 */
		private int side(Vec3 cameraPos) {
			Vec3 offset = cameraPos.subtract(this.subject.center);
			double length = offset.length();
			if (length < 1.0E-3) {
				return 0;
			}
			double lateral = offset.dot(this.right) / length;
			return Math.abs(lateral) < 0.2 ? 0 : (int) Math.signum(lateral);
		}
	}

}
