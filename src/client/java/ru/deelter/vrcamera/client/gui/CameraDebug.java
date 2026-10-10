package ru.deelter.vrcamera.client.gui;

import ru.deelter.vrcamera.client.CameraController;
import ru.deelter.vrcamera.client.CameraController.Mode;
import ru.deelter.vrcamera.client.director.Director;
import ru.deelter.vrcamera.client.rig.Rig;
import ru.deelter.vrcamera.client.rig.Subject;
import ru.deelter.vrcamera.client.shot.Shot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * what the camera of Vivecraft is doing and why, in words: for the debug overlay and /vrcam status
 */
public final class CameraDebug {
	private CameraDebug() {
	}

	public static List<String> lines(CameraController controller) {
		final Mode mode = controller.mode();
		final Director director = controller.director();
		final List<String> lines = new ArrayList<>();

		String state = "VRCamera " + mode;
		if (mode != Mode.OFF && !controller.isEngaged()) {
			state += " (waiting for VR)";
		}
		if (controller.parkedTime() > 0) {
			state += format(" (parked %.0fs)", controller.parkedTime());
		}
		lines.add(state);
		if (mode == Mode.OFF) {
			return lines;
		}

		final Shot shot = controller.shot();
		if (shot != null) {
			final String time = shot.duration == Double.MAX_VALUE ? format("%.1fs", shot.age) :
					format("%.1f/%.1fs", shot.age, shot.duration);
			lines.add("shot: " + shot.type + (shot.side < 0 ? " left " : " right ") + time +
					(director.isHolding() ? " HOLD" : ""));
		}
		if (mode == Mode.DIRECTOR) {
			lines.add("why: " + director.lastReason());
			lines.add("context: " + director.context() + (director.isTight() ? " tight" : "") +
					(director.event() != Director.Event.NONE ? " event " + director.event() : ""));
			lines.add(format("blocked: %.1fs", Math.max(0, director.occludedTime())));
		} else if (mode == Mode.PHYSICS) {
			lines.add("camera: " + controller.physicsState());
		} else {
			lines.add("preset: " + controller.presetLabel());
		}

		final Rig rig = controller.rig();
		final Subject subject = controller.subject();

		if (mode != Mode.PHYSICS) {
			lines.add(format("arm: %.0f%%%s  fov: %.0f", rig.arm() * 100.0,
					rig.lookingPast() ? " (looking past)" : "", rig.fov()));
		}
		lines.add(format("speed: %.1f  scale: %.2f", subject.speed, subject.unit));
		if (subject.target != null) {
			lines.add("target: " + subject.target.getName().getString());
		}
		return lines;
	}

	private static String format(String format, Object... args) {
		return String.format(Locale.ROOT, format, args);
	}
}
