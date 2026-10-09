package ru.deelter.vrcamera.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import ru.deelter.vrcamera.client.CameraController;

/**
 * All camera controls as buttons, opened from the pause menu. For use in VR, where keys are out of reach.
 */
public class CameraMenuScreen extends Screen {
	private static final int BUTTON_WIDTH = 150;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 4;

	private final Screen parent;
	private final CameraController controller = CameraController.INSTANCE;
	private Button modeButton;
	private Button presetButton;

	private int count;

	public CameraMenuScreen(Screen parent) {
		super(Component.translatable("vrcamera.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		count = 0;
		modeButton = add(modeLabel(), button -> {
			controller.cycleMode();
			refresh();
		});
		add(Component.translatable("vrcamera.gui.summon"), button -> {
			controller.summon();

			minecraft.gui.setScreen(null);
		});
		add(Component.translatable("vrcamera.gui.next"), button -> controller.nextShot());
		add(Component.translatable("vrcamera.gui.hold"), button -> controller.toggleHold());
		presetButton = add(presetLabel(), button -> {
			controller.nextPreset();
			refresh();
		});
		add(Component.translatable("vrcamera.gui.preset.new"), button -> {
			controller.newPreset();
			refresh();
		});
		add(Component.translatable("vrcamera.gui.preset.delete"), button -> {
			controller.deletePreset();
			refresh();
		});
		add(Component.translatable("vrcamera.gui.debug"), button -> controller.toggleDebug());
		if (ClothConfig.isInstalled()) {
			add(Component.translatable("vrcamera.gui.settings"),
					button -> minecraft.gui.setScreen(ConfigScreen.create(this)));
		}
		add(Component.translatable("gui.done"), button -> onClose());
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/**
	 * adds a button to the next free spot, two columns in the middle of the screen
	 */
	private Button add(Component label, Button.OnPress onPress) {
		final int column = count % 2;
		final int row = count / 2;
		count++;
		final int x = width / 2 - BUTTON_WIDTH - GAP / 2 + column * (BUTTON_WIDTH + GAP);
		final int y = Math.max(8, height / 2 - 70) + row * (BUTTON_HEIGHT + GAP);
		return addRenderableWidget(
				Button.builder(label, onPress).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
	}

	private void refresh() {
		modeButton.setMessage(modeLabel());
		presetButton.setMessage(presetLabel());
	}

	private Component modeLabel() {
		return Component.translatable("vrcamera.gui.mode", controller.mode().label());
	}

	private Component presetLabel() {
		return Component.translatable("vrcamera.gui.preset", controller.presetLabel());
	}
}
