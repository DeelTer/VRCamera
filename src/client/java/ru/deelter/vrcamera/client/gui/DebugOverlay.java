package ru.deelter.vrcamera.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * Text on the hud about what the camera is doing and why, to tune the settings with.
 */
public final class DebugOverlay {
	private static final int LINE_HEIGHT = 10;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int BACKGROUND_COLOR = 0x90000000;

	public static void extract(GuiGraphicsExtractor graphics) {
		Minecraft mc = Minecraft.getInstance();
		CameraController controller = CameraController.INSTANCE;
		if (!controller.debugEnabled() || controller.mode() == Mode.OFF || mc.player == null) {
			return;
		}
		int y = 4;
		for (String line : lines(controller)) {
			graphics.fill(2, y - 1, 6 + mc.font.width(line), y + LINE_HEIGHT - 1, BACKGROUND_COLOR);
			graphics.text(mc.font, line, 4, y, TEXT_COLOR, false);
			y += LINE_HEIGHT;
		}
	}

	public static List<String> lines(CameraController controller) {
		Mode mode = controller.mode();
		Director director = controller.director();
		List<String> lines = new ArrayList<>();

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

		Shot shot = controller.shot();
		if (shot != null) {
			// shots without an end have no duration worth showing
			String time = shot.duration == Double.MAX_VALUE ? format("%.1fs", shot.age) :
					format("%.1f/%.1fs", shot.age, shot.duration);
			lines.add("shot: " + shot.type + (shot.side < 0 ? " left " : " right ") + time +
					(director.isHolding() ? " HOLD" : ""));
		}
		if (mode == Mode.DIRECTOR) {
			lines.add("why: " + director.lastReason());
			lines.add("context: " + director.context() + (director.isTight() ? " tight" : "") +
					(director.event() != Director.Event.NONE ? " event " + director.event() : ""));
			lines.add(format("blocked: %.1fs", Math.max(0, director.occludedTime())));
		} else {
			lines.add("preset: " + controller.presetLabel());
		}

		Rig rig = controller.rig();
		Subject subject = controller.subject();
		lines.add(format("arm: %.0f%%%s  fov: %.0f", rig.arm() * 100.0, rig.lookingPast() ? " (looking past)" : "",
				rig.fov()));
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
