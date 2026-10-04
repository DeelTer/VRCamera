package ru.deelter.vrcamera.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.vivecraft.api.client.VRClientAPI;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.gui.ConfigScreen;

import java.util.List;

public class VrcameraClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
		Identifier.fromNamespaceAndPath(Vrcamera.MOD_ID, "main"));

	// regular key mappings, Vivecraft makes those bindable to VR controllers as well
	private final KeyMapping keyMode = new KeyMapping("key.vrcamera.mode", GLFW.GLFW_KEY_F8, CATEGORY);
	private final KeyMapping keyNext = new KeyMapping("key.vrcamera.next", GLFW.GLFW_KEY_F9, CATEGORY);
	private final KeyMapping keyHold = new KeyMapping("key.vrcamera.hold", GLFW.GLFW_KEY_F10, CATEGORY);
	private final KeyMapping keyPreset = new KeyMapping("key.vrcamera.preset", GLFW.GLFW_KEY_F7, CATEGORY);
	private final KeyMapping keyNewPreset = new KeyMapping("key.vrcamera.preset.new", GLFW.GLFW_KEY_UNKNOWN,
		CATEGORY);
	private final KeyMapping keyDebug = new KeyMapping("key.vrcamera.debug", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

	@Override
	public void onInitializeClient() {
		CameraController controller = CameraController.INSTANCE;

		VRClientAPI.instance().addClientRegistrationHandler(event -> event.registerTrackers(controller));

		for (KeyMapping key : List.of(this.keyMode, this.keyNext, this.keyHold, this.keyPreset, this.keyNewPreset,
			this.keyDebug))
		{
			KeyMappingHelper.registerKeyMapping(key);
		}

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (this.keyMode.consumeClick()) {
				controller.cycleMode();
			}
			while (this.keyNext.consumeClick()) {
				controller.nextShot();
			}
			while (this.keyHold.consumeClick()) {
				controller.toggleHold();
			}
			while (this.keyPreset.consumeClick()) {
				controller.nextPreset();
			}
			while (this.keyNewPreset.consumeClick()) {
				controller.newPreset();
			}
			while (this.keyDebug.consumeClick()) {
				controller.toggleDebug();
			}
		});

		// don't leave the changed camera settings behind in the Vivecraft config
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> controller.release());

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Vrcamera.MOD_ID, "debug"),
			(graphics, deltaTracker) -> extractDebugOverlay(graphics, controller));

		// buttons in the pause menu, to be reachable from inside VR without a binding
		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			if (!(screen instanceof PauseScreen) || !CameraController.isVRRunning()) {
				return;
			}
			List<AbstractWidget> widgets = Screens.getWidgets(screen);
			int[] row = {0};

			widgets.add(button(row, modeLabel(controller), button -> {
				controller.cycleMode();
				button.setMessage(modeLabel(controller));
			}));
			widgets.add(button(row, Component.translatable("vrcamera.gui.next"), button -> controller.nextShot()));
			widgets.add(button(row, Component.translatable("vrcamera.gui.hold"), button -> controller.toggleHold()));

			Button preset = button(row, presetLabel(controller), button -> {
				controller.nextPreset();
				button.setMessage(presetLabel(controller));
			});
			widgets.add(preset);
			widgets.add(button(row, Component.translatable("vrcamera.gui.preset.new"), button -> {
				controller.newPreset();
				preset.setMessage(presetLabel(controller));
			}));
			widgets.add(button(row, Component.translatable("vrcamera.gui.preset.delete"), button -> {
				controller.deletePreset();
				preset.setMessage(presetLabel(controller));
			}));
			widgets.add(button(row, Component.translatable("vrcamera.gui.debug"),
				button -> controller.toggleDebug()));
			if (ConfigScreen.isAvailable()) {
				widgets.add(button(row, Component.translatable("vrcamera.gui.settings"),
					button -> mc.gui.setScreen(ConfigScreen.create(screen))));
			}
		});
	}

	/**
	 * creates a button for the column in the top left corner
	 *
	 * @param row counter of the buttons created so far
	 */
	private static Button button(int[] row, Component label, Button.OnPress onPress) {
		return Button.builder(label, onPress).bounds(4, 4 + 22 * row[0]++, 130, 20).build();
	}

	private static Component modeLabel(CameraController controller) {
		return Component.translatable("vrcamera.gui.mode", controller.mode().label());
	}

	private static Component presetLabel(CameraController controller) {
		return Component.translatable("vrcamera.gui.preset", controller.presetLabel());
	}

	private static void extractDebugOverlay(GuiGraphicsExtractor graphics, CameraController controller) {
		Minecraft mc = Minecraft.getInstance();
		if (!controller.debugEnabled() || controller.mode() == CameraController.Mode.OFF || mc.player == null) {
			return;
		}
		int y = 4;
		for (String line : controller.debugLines()) {
			graphics.fill(2, y - 1, 6 + mc.font.width(line), y + 9, 0x90000000);
			graphics.text(mc.font, line, 4, y, 0xFFFFFFFF, false);
			y += 10;
		}
	}
}
