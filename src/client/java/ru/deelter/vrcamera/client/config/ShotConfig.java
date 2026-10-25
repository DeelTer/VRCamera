package ru.deelter.vrcamera.client.config;

/**
 * Tunables of a single shot. Distances are in blocks for a player of scale 1 and get multiplied by the player scale.
 */
public class ShotConfig {
	public boolean enabled = true;
	/**
	 * relative chance of this shot being picked
	 */
	public double weight = 1.0;
	/**
	 * degrees around the player, 0 = in front, 180 = behind. Mirrored randomly to the left/right side
	 */
	public double azimuth;
	/**
	 * degrees above the horizon, seen from the player
	 */
	public double elevation;
	public double distance;
	public double fov = 70;
	public double minDuration = 6;
	public double maxDuration = 10;
	/**
	 * if the camera turns to what the player aims at with a bow, null for what suits the shot
	 */
	public Boolean aimTurn;

	public ShotConfig() {
	}

	public ShotConfig(
			double weight, double azimuth, double elevation, double distance, double fov, double minDuration,
			double maxDuration) {
		this.weight = weight;
		this.azimuth = azimuth;
		this.elevation = elevation;
		this.distance = distance;
		this.fov = fov;
		this.minDuration = minDuration;
		this.maxDuration = maxDuration;
	}

	/**
	 * @return this shot, left out until the player turns it on
	 */
	public ShotConfig off() {
		enabled = false;
		return this;
	}

	public ShotConfig copy() {
		final ShotConfig copy = new ShotConfig(weight, azimuth, elevation, distance, fov,
				minDuration, maxDuration);
		copy.enabled = enabled;
		copy.aimTurn = aimTurn;
		return copy;
	}
}
