package ru.deelter.vrcamera.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import org.vivecraft.api.client.VRClientAPI;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.gui.CameraMenuScreen;
import ru.deelter.vrcamera.client.gui.ConfigScreen;
import ru.deelter.vrcamera.client.gui.DebugOverlay;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.photo.PhotoStore;
import ru.deelter.vrcamera.client.sync.PhotoSync;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import java.util.Locale;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.ScreenOutput;
import ru.deelter.vrcamera.client.desktop.ChromaKey;
import ru.deelter.vrcamera.client.desktop.DesktopGui;

public class VrcameraClient implements ClientModInitializer {
	private static final int PAUSE_BUTTON = 150;
	private static final int PAUSE_BUTTON_MIN = 90;
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath(Vrcamera.MOD_ID, "main"));

	// key codes are taken from the game, they are not the same in every Minecraft version
	private static final int UNBOUND = InputConstants.UNKNOWN.getValue();

	private final CameraController controller = CameraController.INSTANCE;
	// what each key does. Regular key mappings, Vivecraft makes those bindable to VR controllers as well
	private final Map<KeyMapping, Runnable> keys = new LinkedHashMap<>();

	@Override
	public void onInitializeClient() {
		VRClientAPI.instance().addClientRegistrationHandler(event -> {
			event.registerTrackers(this.controller);
			event.registerInteractModules(new CameraPull(this.controller), new CameraShutter(this.controller),
					new SheetGrab());
		});

		PhotoSync.INSTANCE.init();

		// Without VR the same keys work the camera on the screen. Not while the camera of VR is still on though:
		// VR can go away at any time, and that one has to be turned off first
		key("mode", InputConstants.KEY_F8, () -> {
			if (onScreen()) {
				DesktopCamera.INSTANCE.cycleMode();
			} else {
				this.controller.cycleMode();
			}
		});
		key("next", InputConstants.KEY_F9, () -> {
			if (onScreen()) {
				DesktopCamera.INSTANCE.nextShot();
			} else {
				this.controller.nextShot();
			}
		});
		key("hold", InputConstants.KEY_F10, () -> {
			if (onScreen()) {
				DesktopCamera.INSTANCE.toggleHold();
			} else {
				this.controller.toggleHold();
			}
		});
		key("preset", InputConstants.KEY_F7, this.controller::nextPreset);
		key("photo", InputConstants.KEY_F6, this.controller::takePhoto);
		key("preset.new", UNBOUND, this.controller::newPreset);
		key("summon", UNBOUND, this.controller::summon);
		key("debug", UNBOUND, this.controller::toggleDebug);
		key("steer", InputConstants.KEY_G, DesktopCamera.INSTANCE::toggleSteering);
		// the screen with everything on it: one place in the radial menu of Vivecraft is enough for the whole mod
		key("menu", UNBOUND, () -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.gui.screen() == null) {
				mc.gui.setScreen(new CameraMenuScreen(null));
			}
		});
		key("settings", UNBOUND, () -> {
			Minecraft mc = Minecraft.getInstance();
			if (ConfigScreen.isAvailable()) {
				mc.gui.setScreen(ConfigScreen.create(mc.gui.screen()));
			}
		});

		ClientCommandRegistrationCallback.EVENT.register(
				(dispatcher, buildContext) -> VrcamCommand.register(dispatcher));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			this.controller.tick();
			PhotoSync.INSTANCE.tick();
			// In VR sheets move with every frame, from the tracker. Without VR there is no tracker, and nothing
			// to hold a sheet with either: a tick is often enough for the ones that hang and the few that fall
			if (mc.player != null && !CameraController.isVRRunning()) {
				PhotoAlbum.INSTANCE.update(mc.player.level(), null, mc.isPaused() ? 0 : 0.05);
			}
			this.keys.forEach((key, action) -> {
				while (key.consumeClick()) {
					action.run();
				}
			});
		});

		// tells exactly what was hit, also for hits by swinging a controller, which don't go through the crosshair
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (level.isClientSide() && player == Minecraft.getInstance().player) {
				this.controller.onAttack(entity);
			}
			return InteractionResult.PASS;
		});

		// don't leave the changed camera settings behind in the Vivecraft config
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> this.controller.release());

		ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> mc.execute(PhotoAlbum.INSTANCE::clear));
		ClientLifecycleEvents.CLIENT_STARTED.register(
				mc -> CompletableFuture.runAsync(() -> {
					PhotoStore.removeDeletedWorlds();
					PhotoStore.trimRemote();
				}));

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Vrcamera.MOD_ID, "debug"),
				(graphics, deltaTracker) -> DebugOverlay.extract(graphics));

		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			if (screen instanceof PauseScreen) {
				addPauseMenuButtons(screen);
			}
		});
	}

	private static Component screenModeLabel() {
		return Component.translatable("vrcamera.gui.mode", Component.translatable(
				"vrcamera.mode." + DesktopCamera.INSTANCE.mode().name().toLowerCase(Locale.ROOT)));
	}

	private Component screenOutputLabel() {
		return Component.translatable("vrcamera.gui.output", Component.translatable(
				"vrcamera.option.screenOutput." + this.controller.config().screenOutput.name().toLowerCase(Locale.ROOT)));
	}

	private boolean onScreen() {
		return !CameraController.isVRRunning() && this.controller.mode() == CameraController.Mode.OFF;
	}

	private void key(String name, int keyCode, Runnable action) {
		KeyMapping key = new KeyMapping("key.vrcamera." + name, keyCode, CATEGORY);
		KeyMappingHelper.registerKeyMapping(key);
		this.keys.put(key, action);
	}

	/**
	 * buttons in the pause menu, to be reachable from inside VR without a binding
	 */
	private void addPauseMenuButtons(Screen pauseMenu) {
		List<AbstractWidget> widgets = Screens.getWidgets(pauseMenu);
		// Without VR the buttons work the camera on the screen. Not while the camera of VR is still on though,
		// to be able to turn that one off after VR went away
		if (onScreen()) {
			int before = widgets.size();
			int slot = 0;
			int[] at = pauseSlot(pauseMenu, slot++);
			widgets.add(Button.builder(screenModeLabel(), button -> {
				DesktopCamera.INSTANCE.cycleMode();
				button.setMessage(screenModeLabel());
			}).bounds(at[0], at[1], at[2], 20).build());
			at = pauseSlot(pauseMenu, slot++);
			widgets.add(Button.builder(screenOutputLabel(), button -> {
				CameraConfig config = this.controller.config();
				config.screenOutput = config.screenOutput == ScreenOutput.WINDOW ? ScreenOutput.SCREEN :
						ScreenOutput.WINDOW;
				config.save();
				button.setMessage(screenOutputLabel());
			}).bounds(at[0], at[1], at[2], 20).build());
			if (ConfigScreen.isAvailable()) {
				at = pauseSlot(pauseMenu, slot++);
				widgets.add(Button.builder(Component.translatable("vrcamera.gui.settings"),
								button -> Minecraft.getInstance().gui.setScreen(ConfigScreen.create(pauseMenu)))
						.bounds(at[0], at[1], at[2], 20).build());
			}
			at = pauseSlot(pauseMenu, slot);
			addChromaButton(widgets, at[0], at[1], at[2]);
			// the menu on the screen in the world is the pause menu, not these
			widgets.stream().skip(before).forEach(DesktopGui::leaveOut);
			return;
		}
		if (!CameraController.isVRRunning() && this.controller.mode() == CameraController.Mode.OFF) {
			return;
		}
		widgets.add(Button.builder(modeLabel(), button -> {
			this.controller.cycleMode();
			button.setMessage(modeLabel());
		}).bounds(4, 4, 120, 20).build());
		widgets.add(Button.builder(Component.translatable("vrcamera.gui.menu"),
						button -> Minecraft.getInstance().gui.setScreen(new CameraMenuScreen(pauseMenu)))
				.bounds(4, 26, 120, 20).build());
		addChromaButton(widgets, 4, 48, 120);
	}

	/**
	 * Where a button of the camera goes in the pause menu without VR: in a column to the left of the menu while
	 * there is room for one, and into the four corners of the screen on a large gui scale.
	 *
	 * @return x, y and width
	 */
	private static int[] pauseSlot(Screen pauseMenu, int index) {
		// the buttons of the pause menu are 204 wide, in the middle
		int beside = pauseMenu.width / 2 - 102 - 8;
		if (beside >= PAUSE_BUTTON_MIN) {
			return new int[]{4, 4 + 22 * index, Math.min(PAUSE_BUTTON, beside)};
		}
		// one row at the top and one at the bottom: the menu is as wide as the screen then, and between them
		int width = Math.min(PAUSE_BUTTON, (pauseMenu.width - 8 - 60) / 2);
		return new int[]{index % 2 == 0 ? 4 : pauseMenu.width - 4 - width, index < 2 ? 4 : pauseMenu.height - 24,
				width};
	}

	/**
	 * a button that goes through the colours of the green screen, and off after the last one
	 */
	private static void addChromaButton(List<AbstractWidget> widgets, int x, int y, int width) {
		widgets.add(Button.builder(chromaLabel(), button -> {
			ChromaKey.Preset[] presets = ChromaKey.Preset.values();
			if (!ChromaKey.isOn()) {
				while (ChromaKey.preset() != presets[0]) {
					ChromaKey.nextPreset();
				}
				ChromaKey.set(true);
			} else if (ChromaKey.preset() == presets[presets.length - 1]) {
				ChromaKey.set(false);
			} else {
				ChromaKey.nextPreset();
			}
			button.setMessage(chromaLabel());
		}).bounds(x, y, width, 20).build());
	}

	private static Component chromaLabel() {
		return Component.translatable("vrcamera.gui.chroma", Component.translatable(!ChromaKey.isOn() ?
				"vrcamera.mode.off" : "vrcamera.gui.chroma." + ChromaKey.preset().name().toLowerCase(Locale.ROOT)));
	}

	private Component modeLabel() {
		return Component.translatable("vrcamera.gui.mode", this.controller.mode().label());
	}
}
