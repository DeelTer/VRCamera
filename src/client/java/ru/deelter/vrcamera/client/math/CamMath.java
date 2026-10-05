package ru.deelter.vrcamera.client.math;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class CamMath {

	/**
	 * @return angle wrapped to -PI..PI
	 */
	public static double wrap(double radians) {
		radians %= Math.PI * 2.0;
		if (radians > Math.PI) {
			radians -= Math.PI * 2.0;
		} else if (radians < -Math.PI) {
			radians += Math.PI * 2.0;
		}
		return radians;
	}

	public static double clamp(double value, double min, double max) {
		return value < min ? min : Math.min(value, max);
	}

	public static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	public static double smoothstep(double t) {
		t = clamp(t, 0, 1);
		return t * t * (3.0 - 2.0 * t);
	}

	/**
	 * @param yaw yaw in radians, in the Minecraft convention, 0 is south
	 * @return horizontal unit vector pointing along the yaw
	 */
	public static Vec3 forward(double yaw) {
		return new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
	}

	/**
	 * @return unit vector pointing from the orbit center to a point on the orbit
	 */
	public static Vec3 orbit(double azimuth, double elevation) {
		double cos = Math.cos(elevation);
		return new Vec3(-Math.sin(azimuth) * cos, Math.sin(elevation), Math.cos(azimuth) * cos);
	}

	public static double azimuthOf(Vec3 dir) {
		return Math.atan2(-dir.x, dir.z);
	}

	public static double elevationOf(Vec3 dir) {
		double length = dir.length();
		return length < 1.0E-6 ? 0 : Math.asin(clamp(dir.y / length, -1, 1));
	}

	/**
	 * builds a camera rotation without roll, the camera looks along its local -Z axis
	 *
	 * @param dir  direction to look in, doesn't need to be normalized
	 * @param dest quaternion to store the rotation in
	 * @return false if {@code dir} was too short to get a rotation from
	 */
	public static boolean lookRotation(Vec3 dir, Quaternionf dest) {
		Vector3f forward = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
		if (forward.lengthSquared() < 1.0E-8F) {
			return false;
		}
		forward.normalize();
		// keep away from straight up/down, there the roll would be undefined
		if (Math.abs(forward.y) > 0.995F) {
			forward.add(0.1F, 0, 0).normalize();
		}
		Vector3f right = forward.cross(0, 1, 0, new Vector3f()).normalize();
		Vector3f up = right.cross(forward, new Vector3f());
		dest.setFromNormalized(new Matrix3f(right, up, forward.negate(new Vector3f())));
		return true;
	}
}
