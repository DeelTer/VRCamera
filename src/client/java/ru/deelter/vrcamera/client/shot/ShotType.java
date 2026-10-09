package ru.deelter.vrcamera.client.shot;

import ru.deelter.vrcamera.client.config.ShotConfig;
import ru.deelter.vrcamera.client.director.Context;

public enum ShotType {

	/**
	 * behind the player, looking over the shoulder
	 */
	SHOULDER(new ShotConfig(1.0, 152, 12, 6.0, 70, 9, 16), 1.0, 1.0, 0.6, 1.2, 1.2, 1.4, 1.2, 1.2, 1.2, 1.0),
	/**
	 * in front of the player, backing away from them, moves in closer while they stand still
	 */
	FRONT(new ShotConfig(1.0, 18, 4, 6.2, 60, 7, 12), 0.9, 1.0, 1.2, 1.0, 0.8, 0.6, 1.0, 1.0, 0.6, 0.8),
	/**
	 * slowly circles the player
	 */
	ORBIT(new ShotConfig(1.0, 90, 18, 4.5, 65, 10, 18), 0.4, 1.0, 1.6, 0.8, 0.4, 0.3, 0.8, 0.8, 1.0, 0.6),
	/**
	 * stands ahead on the path and pans while the player passes by
	 */
	FLYBY(new ShotConfig(1.0, 0, 0, 7.0, 55, 5, 9), 0.3, 1.0, 0.0, 1.2, 1.6, 1.5, 1.4, 0.8, 0.0, 0.0),
	/**
	 * rises high up behind the player
	 */
	CRANE(new ShotConfig(0.8, 180, 55, 8.0, 70, 8, 13), 0.1, 1.0, 0.8, 0.8, 1.0, 1.2, 1.0, 0.6, 0.4, 0.2),
	/**
	 * from the ground, looking up at the player
	 */
	LOW(new ShotConfig(0.8, 35, -8, 5.6, 78, 5, 8), 0.8, 1.0, 0.8, 0.8, 1.0, 0.0, 0.6, 0.0, 1.4, 0.6),
	/**
	 * close up on the hands
	 */
	HANDS(new ShotConfig(0.8, 55, 25, 1.5, 50, 5, 9), 1.0, 0.7, 0.6, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.8),
	/**
	 * Over the shoulder of the player onto what they fight, with both in frame. The azimuth is relative to the
	 * direction to the opponent, instead of the direction the player faces.
	 */
	DUEL(new ShotConfig(1.2, 150, 14, 3.2, 68, 6, 11), 0.9, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 2.2, 0.0),
	/**
	 * circles the dead player and moves away, only shown by the death event
	 */
	DEATH(new ShotConfig(1.0, 30, 35, 4.0, 60, 6, 10), 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
	/**
	 * from straight above, only shown by the fall event
	 */
	FALL(new ShotConfig(1.0, 180, 72, 6.5, 75, 6, 10), 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
	/**
	 * First person: right in front of the face, looking where the player looks. For places with no room for a camera
	 * around the player. Not picked by its fit to what the player does, but when it is tight around them. The
	 * distance is how far in front of the eyes the camera is, the angles are not used.
	 */
	POV(new ShotConfig(0.6, 0, 0, 0.34, 90, 6, 12), 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
	/**
	 * Close over the shoulder onto an open menu: inventory, chest, pause menu. Only shown while one is open. The
	 * azimuth is relative to the direction to the menu.
	 */
	MENU(new ShotConfig(1.0, 138, 18, 1.7, 55, 8, 14), 1.0, 0.9, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
	/**
	 * High above the player and to the side, circling them on a course of its own whichever way they turn: the
	 * player in the place they are in
	 */
	DRONE(new ShotConfig(1.0, 180, 45, 14.0, 60, 10, 18), 0.1, 1.0, 0.6, 0.9, 1.2, 0.8, 1.2, 0.8, 0.4, 0.2),
	/**
	 * placed by hand
	 */
	CUSTOM(null, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);

	/**
	 * weight multiplier in tight spaces
	 */
	public final double tightFactor;
	/**
	 * closest the camera may get to the player, in player scales, before the shot is not worth showing
	 */
	public final double minDistance;
	private final ShotConfig defaults;
	private final double[] contextWeights;

	ShotType(ShotConfig defaults, double tightFactor, double minDistance, double... contextWeights) {
		this.defaults = defaults;
		this.tightFactor = tightFactor;
		this.minDistance = minDistance;
		this.contextWeights = contextWeights;
	}

	/**
	 * @return a fresh copy of the default settings
	 */
	public ShotConfig defaults() {
		return defaults.copy();
	}

	/**
	 * @return how well this shot fits the context, 0 means it should not be used
	 */
	public double weight(Context context) {
		return contextWeights[context.ordinal()];
	}

	/**
	 * @return if the camera can not swing from or to this shot, because it looks away from the player and not at them
	 */
	public boolean cutsOnly() {
		return this == POV;
	}

	/**
	 * @return if the camera circles the player on its own
	 */
	public boolean orbits() {
		return this == ORBIT || this == DEATH;
	}
}
