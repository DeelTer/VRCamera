package ru.deelter.vrcamera.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The mode of the camera in the pause menu: a button that opens the list of every mode in the place of the buttons
 * under it, to go to one right away instead of through all the others.
 */
public final class ModePicker {
	private final Button opener;
	private final List<Button> choices = new ArrayList<>();
	private final List<Button> covered = new ArrayList<>();
	private boolean open;

	public ModePicker() {
		opener = Button.builder(Component.empty(), button -> {
					open = !open;
					show();
				}).tooltip(Tooltip.create(Component.translatable("vrcamera.gui.mode.tooltip")))
				.build();
		for (final DesktopCamera.Mode mode : DesktopCamera.Mode.values()) {
			choices.add(Button.builder(Component.empty(), button -> pick(mode))
					.tooltip(Tooltip.create(Component.translatable(key(mode) + ".tooltip"))).build());
		}
		show();
	}

	private static String key(DesktopCamera.Mode mode) {
		return "vrcamera.mode." + mode.name().toLowerCase(Locale.ROOT);
	}

	private static Component name(DesktopCamera.Mode mode) {
		return Component.translatable(key(mode));
	}

	public Button opener() {
		return opener;
	}

	/**
	 * @return a button for every mode, in the order of the modes
	 */
	public List<Button> choices() {
		return choices;
	}

	/**
	 * @param button one that is hidden while the list is open, it stands where the list goes
	 */
	public void covers(Button button) {
		covered.add(button);
		show();
	}

	private void pick(DesktopCamera.Mode mode) {
		if (mode != DesktopCamera.INSTANCE.mode()) {
			DesktopCamera.INSTANCE.setMode(mode);
		}
		open = false;
		show();
	}

	private void show() {
		final DesktopCamera.Mode now = DesktopCamera.INSTANCE.mode();
		opener.setMessage(Component.translatable("vrcamera.gui.mode", name(now)).append(open ? " ▲" : " ▼"));
		for (int index = 0; index < choices.size(); index++) {
			final DesktopCamera.Mode mode = DesktopCamera.Mode.values()[index];
			final Button choice = choices.get(index);
			choice.visible = open;
			choice.setMessage(mode == now ? Component.literal("✔ ").append(name(mode)) : name(mode));
		}
		covered.forEach(button -> button.visible = !open);
	}
}
