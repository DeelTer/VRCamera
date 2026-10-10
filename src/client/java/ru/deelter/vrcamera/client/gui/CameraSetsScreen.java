package ru.deelter.vrcamera.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The sets of free cameras as buttons: going from one to another, making and deleting them, and giving one to
 * someone else through the clipboard. The same as the commands /cam set, export and import do.
 */
public class CameraSetsScreen extends Screen {
	private static final Pattern SET_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
	private static final String NEW_NAME = "set-";
	private static final int WIDTH = 260;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 4;

	private final Screen parent;
	private final DesktopCamera camera = DesktopCamera.INSTANCE;
	private Button setButton;
	private Button deleteButton;
	private EditBox name;
	private StringWidget status;
	private boolean deleting;

	public CameraSetsScreen(Screen parent) {
		super(Component.translatable("vrcamera.gui.sets"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		final int x = width / 2 - WIDTH / 2;
		final int half = (WIDTH - GAP) / 2;
		int y = Math.max(8, height / 2 - 110);
		addRenderableWidget(new StringWidget(x, y, WIDTH, BUTTON_HEIGHT, title, font));
		y += BUTTON_HEIGHT + GAP;
		setButton = addRenderableWidget(Button.builder(setLabel(), button -> {
			camera.nextCameraSet();
			refresh("vrcamera.gui.sets.opened");
		}).bounds(x, y, WIDTH, BUTTON_HEIGHT).build());
		y += BUTTON_HEIGHT + GAP;
		final Component export = Component.translatable("vrcamera.gui.sets.export");
		addRenderableWidget(Button.builder(export, button -> refresh(camera.exportCameras() ?
						"vrcamera.gui.sets.copied" : "vrcamera.command.set.none"))
				.bounds(x, y, half, BUTTON_HEIGHT).build());
		final Component delete = Component.translatable("vrcamera.gui.sets.delete");
		deleteButton = addRenderableWidget(Button.builder(delete, button -> {
			if (!deleting) {
				deleting = true;
				button.setMessage(Component.translatable("vrcamera.gui.sets.delete.confirm"));
				return;
			}
			refresh(camera.deleteCameraSet() ? "vrcamera.gui.sets.deleted" : "vrcamera.gui.sets.delete.failed");
		}).bounds(x + half + GAP, y, half, BUTTON_HEIGHT).build());
		y += BUTTON_HEIGHT + GAP * 4;
		name = addRenderableWidget(new EditBox(font, x, y, WIDTH, BUTTON_HEIGHT,
				Component.translatable("vrcamera.gui.sets.name")));
		name.setMaxLength(32);
		name.setHint(Component.translatable("vrcamera.gui.sets.name"));
		y += BUTTON_HEIGHT + GAP;
		addRenderableWidget(Button.builder(Component.translatable("vrcamera.gui.sets.open"), button -> {
			final String set = named();
			if (set != null) {
				camera.useCameraSet(set);
				name.setValue("");
				refresh("vrcamera.gui.sets.created");
			}
		}).bounds(x, y, half, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.translatable("vrcamera.gui.sets.import"), button -> {
			final String set = named();
			if (set != null) {
				final boolean pasted = camera.importCameras(set);
				if (pasted) {
					name.setValue("");
				}
				refresh(pasted ? "vrcamera.gui.sets.pasted" : "vrcamera.gui.sets.import.none");
			}
		}).bounds(x + half + GAP, y, half, BUTTON_HEIGHT).build());
		y += BUTTON_HEIGHT + GAP;
		status = addRenderableWidget(new StringWidget(x, y, WIDTH, BUTTON_HEIGHT, Component.empty(), font));
		y += BUTTON_HEIGHT;
		final MultiLineTextWidget help = addRenderableWidget(new MultiLineTextWidget(
				Component.translatable("vrcamera.gui.sets.help"), font).setMaxWidth(WIDTH).setCentered(true));
		help.setPosition(x + (WIDTH - help.getWidth()) / 2, y);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(x, Math.min(height - BUTTON_HEIGHT - GAP, y + help.getHeight() + GAP * 2), WIDTH, BUTTON_HEIGHT)
				.build());
		refresh(null);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/**
	 * @return the name typed for a new set, one made up if none is typed, null if what is typed can't be a name
	 */
	private String named() {
		final String typed = name.getValue().trim();
		if (typed.isEmpty()) {
			final List<String> taken = camera.cameraSets();
			int number = 1;
			while (taken.contains(NEW_NAME + number)) {
				number++;
			}
			return NEW_NAME + number;
		}
		if (!SET_NAME.matcher(typed).matches()) {
			refresh("vrcamera.gui.sets.name.bad");
			return null;
		}
		return typed;
	}

	private void refresh(String said) {
		deleting = false;
		setButton.setMessage(setLabel());
		deleteButton.setMessage(Component.translatable("vrcamera.gui.sets.delete"));
		deleteButton.active = !camera.cameraSet().isEmpty();
		status.setMessage(said == null ? Component.empty() : Component.translatable(said));
	}

	private Component setLabel() {
		final String set = camera.cameraSet();
		return Component.translatable("vrcamera.gui.sets.current",
				set.isEmpty() ? Component.translatable("vrcamera.message.set.usual") : set);
	}
}
