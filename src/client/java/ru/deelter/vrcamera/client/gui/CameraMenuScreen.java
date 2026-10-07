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
	// buttons placed so far
	private int count;

	public CameraMenuScreen(Screen parent) {
		super(Component.translatable("vrcamera.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.count = 0;
		this.modeButton = add(modeLabel(), button -> {
			this.controller.cycleMode();
			refresh();
		});
		add(Component.translatable("vrcamera.gui.summon"), button -> {
			this.controller.summon();
			// back into the game, to grab it
			this.minecraft.setScreen(null);
		});
		add(Component.translatable("vrcamera.gui.next"), button -> this.controller.nextShot());
		add(Component.translatable("vrcamera.gui.hold"), button -> this.controller.toggleHold());
		this.presetButton = add(presetLabel(), button -> {
			this.controller.nextPreset();
			refresh();
		});
		add(Component.translatable("vrcamera.gui.preset.new"), button -> {
			this.controller.newPreset();
			refresh();
		});
		add(Component.translatable("vrcamera.gui.preset.delete"), button -> {
			this.controller.deletePreset();
			refresh();
		});
		add(Component.translatable("vrcamera.gui.debug"), button -> this.controller.toggleDebug());
		if (ConfigScreen.isAvailable()) {
			add(Component.translatable("vrcamera.gui.settings"),
					button -> this.minecraft.setScreen(ConfigScreen.create(this)));
		}
		add(Component.translatable("gui.done"), button -> onClose());
	}

	/**
	 * adds a button to the next free spot, two columns in the middle of the screen
	 */
	private Button add(Component label, Button.OnPress onPress) {
		int column = this.count % 2;
		int row = this.count / 2;
		this.count++;
		int x = this.width / 2 - BUTTON_WIDTH - GAP / 2 + column * (BUTTON_WIDTH + GAP);
		int y = Math.max(8, this.height / 2 - 70) + row * (BUTTON_HEIGHT + GAP);
		return addRenderableWidget(
				Button.builder(label, onPress).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
	}

	private void refresh() {
		this.modeButton.setMessage(modeLabel());
		this.presetButton.setMessage(presetLabel());
	}

	private Component modeLabel() {
		return Component.translatable("vrcamera.gui.mode", this.controller.mode().label());
	}

	private Component presetLabel() {
		return Component.translatable("vrcamera.gui.preset", this.controller.presetLabel());
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
