package ru.deelter.vrcamera.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;

import java.util.regex.Pattern;

/**
 * The sets of free cameras as buttons: going from one to another, and giving one to someone else through the
 * clipboard. The same as the commands /cam set, export and import do.
 */
public class CameraSetsScreen extends Screen {
	private static final Pattern SET_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
	private static final int WIDTH = 240;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 4;

	private final Screen parent;
	private final DesktopCamera camera = DesktopCamera.INSTANCE;
	private Button setButton;
	private EditBox name;
	private StringWidget status;

	public CameraSetsScreen(Screen parent) {
		super(Component.translatable("vrcamera.gui.sets"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		final int x = width / 2 - WIDTH / 2;
		int y = Math.max(8, height / 2 - 100);
		addRenderableWidget(new StringWidget(x, y, WIDTH, BUTTON_HEIGHT, title, font));
		y += BUTTON_HEIGHT + GAP;
		setButton = addRenderableWidget(Button.builder(setLabel(), button -> {
			camera.nextCameraSet();
			refresh("vrcamera.gui.sets.opened");
		}).bounds(x, y, WIDTH, BUTTON_HEIGHT).build());
		y += BUTTON_HEIGHT + GAP;
		addRenderableWidget(Button.builder(Component.translatable("vrcamera.gui.sets.export"),
						button -> refresh(camera.exportCameras() ? "vrcamera.gui.sets.copied" : "vrcamera.command.set.none"))
				.bounds(x, y, WIDTH, BUTTON_HEIGHT).build());
		y += BUTTON_HEIGHT + GAP * 3;
		name = addRenderableWidget(new EditBox(font, x, y, WIDTH, BUTTON_HEIGHT,
				Component.translatable("vrcamera.gui.sets.name")));
		name.setMaxLength(32);
		name.setHint(Component.translatable("vrcamera.gui.sets.name"));
		y += BUTTON_HEIGHT + GAP;
		final int half = (WIDTH - GAP) / 2;
		addRenderableWidget(Button.builder(Component.translatable("vrcamera.gui.sets.open"), button -> {
			if (named()) {
				camera.useCameraSet(name.getValue());
				refresh("vrcamera.gui.sets.opened");
			}
		}).bounds(x, y, half, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.translatable("vrcamera.gui.sets.import"), button -> {
			if (named()) {
				refresh(camera.importCameras(name.getValue()) ? "vrcamera.gui.sets.pasted" : "vrcamera.command.set.bad");
			}
		}).bounds(x + half + GAP, y, half, BUTTON_HEIGHT).build());
		y += BUTTON_HEIGHT + GAP;
		status = addRenderableWidget(new StringWidget(x, y, WIDTH, BUTTON_HEIGHT, Component.empty(), font));
		y += BUTTON_HEIGHT;
		final MultiLineTextWidget help = addRenderableWidget(new MultiLineTextWidget(
				Component.translatable("vrcamera.gui.sets.help"), font).setMaxWidth(WIDTH).setCentered(true));
		help.setPosition(x, y);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(x, Math.min(height - BUTTON_HEIGHT - GAP, y + help.getHeight() + GAP * 2), WIDTH, BUTTON_HEIGHT)
				.build());
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	private boolean named() {
		final boolean named = SET_NAME.matcher(name.getValue()).matches();
		if (!named) {
			refresh("vrcamera.gui.sets.name.bad");
		}
		return named;
	}

	private void refresh(String said) {
		setButton.setMessage(setLabel());
		status.setMessage(Component.translatable(said));
	}

	private Component setLabel() {
		final String set = camera.cameraSet();
		return Component.translatable("vrcamera.gui.sets.current",
				set.isEmpty() ? Component.translatable("vrcamera.message.set.usual") : set);
	}
}
