package ru.deelter.vrcamera.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.compat.WorldDrawing;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.ScreenOutput;
import ru.deelter.vrcamera.client.desktop.ChromaKey;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.DesktopGui;
import ru.deelter.vrcamera.client.desktop.OutputWindow;
import ru.deelter.vrcamera.client.gui.CameraMenuScreen;
import ru.deelter.vrcamera.client.gui.ConfigScreen;
import ru.deelter.vrcamera.client.gui.DebugOverlay;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.photo.PhotoStore;
import ru.deelter.vrcamera.client.shot.ShotType;
import ru.deelter.vrcamera.client.sync.PhotoSync;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class VrcameraClient implements ClientModInitializer {
	private static final int PAUSE_BUTTON = 150;
	private static final int PAUSE_BUTTON_MIN = 90;
	private static final String CATEGORY = "key.category.vrcamera.main";

	// key codes are taken from the game, they are not the same in every Minecraft version
	private static final int UNBOUND = InputConstants.UNKNOWN.getValue();

	// what each key does. Regular key mappings, Vivecraft makes those bindable to VR controllers as well
	private final Map<KeyMapping, Runnable> keys = new LinkedHashMap<>();
	private final Set<KeyMapping> heldInWindow = new HashSet<>();
	private KeyMapping gameOnly;

	private static Component screenModeLabel() {
		return Component.translatable("vrcamera.gui.mode", Component.translatable(
				"vrcamera.mode." + DesktopCamera.INSTANCE.mode().name().toLowerCase(Locale.ROOT)));
	}

	/**
	 * the photo key: with the camera of Vivecraft where there is one, of what the screen shows where there is not
	 */
	public static void takePhoto() {
		if (Vr.INSTALLED) {
			CameraController.INSTANCE.takePhoto();
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && PhotoAlbum.INSTANCE.takeWithoutCamera(player, CameraConfig.current().photoSheet)) {
			CameraEffects.ownShutter(player);
		}
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
	private static Button chromaButton() {
		return Button.builder(chromaLabel(), button -> {
			ChromaKey.cycle();
			button.setMessage(chromaLabel());
		}).build();
	}

	private static Component chromaLabel() {
		return Component.translatable("vrcamera.gui.chroma", Component.translatable(!ChromaKey.isOn() ?
				"vrcamera.mode.off" : "vrcamera.gui.chroma." + ChromaKey.preset().name().toLowerCase(Locale.ROOT)));
	}

	@Override
	public void onInitializeClient() {
		if (Vr.INSTALLED) {
			Vive.register(CameraController.INSTANCE);
		}

		PhotoSync.INSTANCE.init();
		WorldDrawing.register();

		// Without VR the same keys work the camera on the screen. Not while the camera of VR is still on though:
		// VR can go away at any time, and that one has to be turned off first
		DesktopCamera desktop = DesktopCamera.INSTANCE;
		key("mode", InputConstants.KEY_F8, desktop::cycleMode, () -> CameraController.INSTANCE.cycleMode());
		key("next", InputConstants.KEY_F9, desktop::nextShot, () -> CameraController.INSTANCE.nextShot());
		key("hold", InputConstants.KEY_F10, desktop::toggleHold, () -> CameraController.INSTANCE.toggleHold());
		// the one key between the view of the player and the picture of the camera. Nothing in VR, where the eyes
		// of the player are their view and the camera is in their hand
		key("toggle", InputConstants.KEY_F4, desktop::toggle, () -> {
		});
		// straight to a shot, for a moment that will not wait for the right one to come around
		for (ShotType shot : List.of(ShotType.SHOULDER, ShotType.FRONT, ShotType.ORBIT, ShotType.FLYBY)) {
			key("shot." + shot.name().toLowerCase(Locale.ROOT), UNBOUND, () -> desktop.showShot(shot),
					() -> CameraController.INSTANCE.showShot(shot));
		}
		key("preset", InputConstants.KEY_F7, desktop::nextPoint, () -> CameraController.INSTANCE.nextPreset());
		key("photo", InputConstants.KEY_F6, VrcameraClient::takePhoto);
		key("preset.new", InputConstants.KEY_N, desktop::addCamera, () -> CameraController.INSTANCE.newPreset());
		key("preset.fly", UNBOUND, desktop::flyToNext);
		key("summon", UNBOUND, desktop::summon, () -> CameraController.INSTANCE.summon());
		key("debug", UNBOUND, DebugOverlay::toggle);
		// not in the window of the camera: going there takes the camera over, and leaving it gives it back
		this.gameOnly = key("steer", InputConstants.KEY_G, desktop::toggleSteering);
		// the screen with everything on it: one place in the radial menu of Vivecraft is enough for the whole mod
		key("menu", UNBOUND, () -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.screen == null && Vr.INSTALLED) {
				mc.setScreen(new CameraMenuScreen(null));
			}
		});
		key("settings", UNBOUND, () -> {
			Minecraft mc = Minecraft.getInstance();
			if (ConfigScreen.isAvailable()) {
				mc.setScreen(ConfigScreen.create(mc.screen));
			}
		});

		ClientCommandRegistrationCallback.EVENT.register(
				(dispatcher, buildContext) -> VrcamCommand.register(dispatcher));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (Vr.INSTALLED) {
				CameraController.INSTANCE.tick();
			}
			DesktopCamera.INSTANCE.tick();
			PhotoSync.INSTANCE.tick();
			// The game does not hear keys in the window of the camera. They work there all the same, to not have
			// to go back to the game for them
			boolean inWindow = OutputWindow.isFocused();
			this.keys.forEach((key, action) -> {
				while (key.consumeClick()) {
					action.run();
				}
				boolean down = inWindow && key != this.gameOnly &&
						OutputWindow.isKeyDown(KeyBindingHelper.getBoundKeyOf(key).getValue());
				if (!down) {
					this.heldInWindow.remove(key);
				} else if (this.heldInWindow.add(key)) {
					action.run();
				}
			});
		});

		// tells exactly what was hit, also for hits by swinging a controller, which don't go through the crosshair
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (Vr.INSTALLED && level.isClientSide() && player == Minecraft.getInstance().player) {
				CameraController.INSTANCE.onAttack(entity);
			}
			return InteractionResult.PASS;
		});

		// don't leave the changed camera settings behind in the Vivecraft config
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			if (Vr.INSTALLED) {
				CameraController.INSTANCE.release();
			}
			OutputWindow.close();
		});

		ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> mc.execute(PhotoAlbum.INSTANCE::clear));
		ClientLifecycleEvents.CLIENT_STARTED.register(
				mc -> CompletableFuture.runAsync(() -> {
					PhotoStore.removeDeletedWorlds();
					PhotoStore.trimRemote();
				}));

		HudLayerRegistrationCallback.EVENT.register(layers -> layers.addLayer(IdentifiedLayer.of(
				ResourceLocation.fromNamespaceAndPath(Vrcamera.MOD_ID, "debug"),
				(graphics, deltaTracker) -> DebugOverlay.extract(graphics))));

		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			if (screen instanceof PauseScreen) {
				addPauseMenuButtons(screen);
			}
		});
	}

	private Component screenOutputLabel() {
		return Component.translatable("vrcamera.gui.output", Component.translatable(
				"vrcamera.option.screenOutput." + CameraConfig.current().screenOutput.name().toLowerCase(Locale.ROOT)));
	}

	private boolean onScreen() {
		return !Vr.isRunning() && (!Vr.INSTALLED || CameraController.INSTANCE.mode() == CameraController.Mode.OFF);
	}

	/**
	 * a key that works the camera on the screen without VR, and the one of Vivecraft with it
	 */
	private void key(String name, int keyCode, Runnable onScreen, Runnable inVR) {
		key(name, keyCode, () -> (onScreen() ? onScreen : inVR).run());
	}

	private KeyMapping key(String name, int keyCode, Runnable action) {
		KeyMapping key = new KeyMapping("key.vrcamera." + name, keyCode, CATEGORY);
		KeyBindingHelper.registerKeyBinding(key);
		this.keys.put(key, action);
		return key;
	}

	/**
	 * buttons in the pause menu, to be reachable from inside VR without a binding
	 */
	private void addPauseMenuButtons(Screen pauseMenu) {
		List<AbstractWidget> widgets = Screens.getButtons(pauseMenu);
		// Without VR the buttons work the camera on the screen. Not while the camera of VR is still on though,
		// to be able to turn that one off after VR went away
		if (onScreen()) {
			List<Button> buttons = new ArrayList<>();
			buttons.add(Button.builder(screenModeLabel(), button -> {
				DesktopCamera.INSTANCE.cycleMode();
				button.setMessage(screenModeLabel());
			}).build());
			buttons.add(Button.builder(screenOutputLabel(), button -> {
				CameraConfig config = CameraConfig.current();
				config.screenOutput = config.screenOutput == ScreenOutput.WINDOW ? ScreenOutput.SCREEN :
						ScreenOutput.WINDOW;
				config.save();
				button.setMessage(screenOutputLabel());
			}).build());
			if (ConfigScreen.isAvailable()) {
				buttons.add(Button.builder(Component.translatable("vrcamera.gui.settings"),
						button -> Minecraft.getInstance().setScreen(ConfigScreen.create(pauseMenu))).build());
			}
			buttons.add(chromaButton());
			for (int slot = 0; slot < buttons.size(); slot++) {
				Button button = buttons.get(slot);
				int[] at = pauseSlot(pauseMenu, slot);
				button.setRectangle(at[2], 20, at[0], at[1]);
				// the menu on the screen in the world is the pause menu, not these
				DesktopGui.leaveOut(button);
				widgets.add(button);
			}
			return;
		}
		if (!Vr.isRunning() && CameraController.INSTANCE.mode() == CameraController.Mode.OFF) {
			return;
		}
		widgets.add(Button.builder(modeLabel(), button -> {
			CameraController.INSTANCE.cycleMode();
			button.setMessage(modeLabel());
		}).bounds(4, 4, 120, 20).build());
		widgets.add(Button.builder(Component.translatable("vrcamera.gui.menu"),
						button -> Minecraft.getInstance().setScreen(new CameraMenuScreen(pauseMenu)))
				.bounds(4, 26, 120, 20).build());
		Button chroma = chromaButton();
		chroma.setRectangle(120, 20, 4, 48);
		widgets.add(chroma);
	}

	private Component modeLabel() {
		return Component.translatable("vrcamera.gui.mode", CameraController.INSTANCE.mode().label());
	}
}
