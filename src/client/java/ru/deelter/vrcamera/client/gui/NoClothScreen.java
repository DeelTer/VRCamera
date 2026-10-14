package ru.deelter.vrcamera.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.photo.Palettes;

/**
 * What the settings are without Cloth Config, which the screen of them is built with: where to get it, and the
 * file the settings are in, to change them by hand and have them read again.
 */
public class NoClothScreen extends Screen {
	private static final String CLOTH_CONFIG = "https://modrinth.com/mod/cloth-config";
	private static final int WIDTH = 220;
	private static final int BUTTON_HEIGHT = 20;
	private static final int LINE = 12;
	private static final int ROW = 24;

	private final Screen parent;
	private StringWidget status;

	public NoClothScreen(Screen parent) {
		super(Component.translatable("vrcamera.gui.settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		final int x = (width - WIDTH) / 2;
		int y = height / 2 - 80;
		line(title, y);
		line(Component.translatable("vrcamera.gui.nocloth.need"), y += LINE * 2);
		line(Component.translatable("vrcamera.gui.nocloth.file"), y += LINE);
		y += LINE * 2;
		addRenderableWidget(Button.builder(Component.translatable("vrcamera.gui.nocloth.get"),
				button -> Palettes.open(CLOTH_CONFIG)).bounds(x, y, WIDTH, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.translatable("vrcamera.gui.nocloth.open"),
						button -> Palettes.open(CameraConfig.file().toAbsolutePath().toString()))
				.bounds(x, y += ROW, WIDTH, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.translatable("vrcamera.gui.nocloth.reload"), button -> {
			CameraConfig.reload();
			final Component said = Component.translatable("vrcamera.command.reloaded");
			status.setMessage(said);
			status.setWidth(font.width(said));
			status.setX((width - font.width(said)) / 2);
		}).bounds(x, y += ROW, WIDTH, BUTTON_HEIGHT).build());
		status = line(Component.empty(), y += ROW + 4);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(x, y + LINE + 4, WIDTH, BUTTON_HEIGHT).build());
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	private StringWidget line(Component text, int y) {
		return addRenderableWidget(new StringWidget((width - font.width(text)) / 2, y, font.width(text), LINE, text,
				font));
	}
}
