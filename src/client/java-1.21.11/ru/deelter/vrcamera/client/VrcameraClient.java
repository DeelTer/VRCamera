package ru.deelter.vrcamera.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.ScreenOutput;
import ru.deelter.vrcamera.client.desktop.ChromaKey;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.DesktopGui;
import ru.deelter.vrcamera.client.desktop.OutputWindow;
import ru.deelter.vrcamera.client.gui.CameraMenuScreen;
import ru.deelter.vrcamera.client.gui.CameraSetsScreen;
import ru.deelter.vrcamera.client.gui.ClothConfig;
import ru.deelter.vrcamera.client.gui.ConfigScreen;
import ru.deelter.vrcamera.client.gui.DebugOverlay;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.photo.PhotoStore;
import ru.deelter.vrcamera.client.shot.ShotType;
import ru.deelter.vrcamera.client.sync.PhotoSync;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class VrcameraClient implements ClientModInitializer {
	private static final Runnable NO_ACTION = () -> {
	};
	private static final int PAUSE_BUTTON = 150;
	private static final int PAUSE_BUTTON_MIN = 90;
	private static final int BUTTON_HEIGHT = 20;
	private static final int VR_BUTTON_WIDTH = 120;
	private static final int EDGE_MARGIN = 4;
	private static final int HORIZONTAL_GAP = 8;
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath(Vrcamera.MOD_ID, "main"));

	private static final int UNBOUND = InputConstants.UNKNOWN.getValue();
	private static final List<ShotType> DIRECT_SHOTS = List.of(
			ShotType.SHOULDER, ShotType.FRONT, ShotType.ORBIT, ShotType.FLYBY);

	private final Map<KeyMapping, Runnable> keys = new LinkedHashMap<>();
	private final Set<KeyMapping> heldInWindow = new HashSet<>();
	/**
	 * Keys that are held in the game window. The game counts a key that is held as pressed again and again, the
	 * way it is typed with: one press of a key of the mod is one thing done
	 */
	private final Set<KeyMapping> heldInGame = new HashSet<>();

	private KeyMapping gameOnly;

	/**
	 * the photo key: with the camera of Vivecraft where there is one, of what the screen shows where there is not
	 */
	static void takePhoto() {
		if (Vr.INSTALLED) {
			CameraController.INSTANCE.takePhoto();
			return;
		}
		final LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && PhotoAlbum.INSTANCE.takeWithoutCamera(player, CameraConfig.current().photoSheet)) {
			CameraEffects.ownShutter(player);
		}
	}

	private static Component screenModeLabel() {
		return Component.translatable("vrcamera.gui.mode", Component.translatable(
				"vrcamera.mode." + DesktopCamera.INSTANCE.mode().name().toLowerCase(Locale.ROOT)));
	}

	/**
	 * Where a button of the camera goes in the pause menu without VR: in a column to the left of the menu while
	 * there is room for one, and into the four corners of the screen on a large gui scale.
	 *
	 * @return x, y and width
	 */
	private static int @NotNull [] pauseSlot(@NotNull Screen pauseMenu, int index) {

		final int beside = pauseMenu.width / 2 - 102 - HORIZONTAL_GAP;
		if (beside >= PAUSE_BUTTON_MIN) {
			return new int[]{EDGE_MARGIN, EDGE_MARGIN + 22 * index, Math.min(PAUSE_BUTTON, beside)};
		}

		final int width = Math.min(PAUSE_BUTTON, (pauseMenu.width - HORIZONTAL_GAP - 60) / 2);
		return new int[]{index % 2 == 0 ? EDGE_MARGIN : pauseMenu.width - EDGE_MARGIN - width,
				index < 2 ? EDGE_MARGIN : pauseMenu.height - 24 - 22 * ((index - 2) / 2),
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

		final DesktopCamera desktop = DesktopCamera.INSTANCE;
		key("mode", InputConstants.KEY_F8, desktop::cycleMode,
				Vr.INSTALLED ? CameraController.INSTANCE::cycleMode : NO_ACTION);
		key("next", InputConstants.KEY_F9, desktop::nextShot,
				Vr.INSTALLED ? CameraController.INSTANCE::nextShot : NO_ACTION);
		key("hold", InputConstants.KEY_F10, desktop::toggleHold,
				Vr.INSTALLED ? CameraController.INSTANCE::toggleHold : NO_ACTION);

		key("toggle", InputConstants.KEY_F4, desktop::toggle, NO_ACTION);

		for (final ShotType shot : DIRECT_SHOTS) {
			key("shot." + shot.name().toLowerCase(Locale.ROOT), UNBOUND, () -> desktop.showShot(shot),
					() -> CameraController.INSTANCE.showShot(shot));
		}
		key("preset", InputConstants.KEY_F7, desktop::nextPoint,
				Vr.INSTALLED ? CameraController.INSTANCE::nextPreset : NO_ACTION);
		key("photo", InputConstants.KEY_F6, VrcameraClient::takePhoto);
		key("preset.new", InputConstants.KEY_N, desktop::addCamera,
				Vr.INSTALLED ? CameraController.INSTANCE::newPreset : NO_ACTION);
		key("preset.remove", InputConstants.KEY_DELETE, desktop::removeCamera, NO_ACTION);
		key("preset.remove.other", InputConstants.KEY_BACKSPACE, desktop::removeCamera, NO_ACTION);
		key("preset.fly", UNBOUND, desktop::flyToNext);
		key("set.next", UNBOUND, desktop::nextCameraSet, NO_ACTION);
		key("sets", UNBOUND, () -> {
			final Minecraft mc = Minecraft.getInstance();
			if (mc.screen == null && mc.level != null) {
				mc.setScreen(new CameraSetsScreen(null));
			}
		}, NO_ACTION);
		key("summon", UNBOUND, desktop::summon,
				Vr.INSTALLED ? CameraController.INSTANCE::summon : NO_ACTION);
		key("debug", UNBOUND, DebugOverlay::toggle);

		gameOnly = key("steer", InputConstants.KEY_G, desktop::toggleSteering);

		key("menu", UNBOUND, () -> {
			final Minecraft mc = Minecraft.getInstance();
			if (mc.screen == null && Vr.INSTALLED) {
				mc.setScreen(new CameraMenuScreen(null));
			}
		});
		key("settings", UNBOUND, () -> {
			final Minecraft mc = Minecraft.getInstance();
			if (ClothConfig.isInstalled()) {
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

			final boolean inWindow = OutputWindow.isFocused();
			keys.forEach((key, action) -> handleKey(key, action, inWindow));
		});

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (Vr.INSTALLED && level.isClientSide() && player == Minecraft.getInstance().player) {
				CameraController.INSTANCE.onAttack(entity);
			}
			return InteractionResult.PASS;
		});

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

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Vrcamera.MOD_ID, "debug"),
				(graphics, deltaTracker) -> DebugOverlay.extract(graphics));

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
	private void key(@NotNull String name, int keyCode, @NotNull Runnable onScreen, @NotNull Runnable inVR) {
		key(name, keyCode, () -> (onScreen() ? onScreen : inVR).run());
	}

	@NotNull
	private KeyMapping key(@NotNull String name, int keyCode, @NotNull Runnable action) {
		final KeyMapping key = new KeyMapping("key.vrcamera." + name, keyCode, CATEGORY);
		KeyBindingHelper.registerKeyBinding(key);
		keys.put(key, action);
		return key;
	}

	private void handleKey(@NotNull KeyMapping key, @NotNull Runnable action, boolean inWindow) {
		boolean pressed = false;
		while (key.consumeClick()) {
			pressed = true;
		}
		if (pressed && !heldInGame.contains(key)) {
			action.run();
		}
		if (key.isDown()) {
			heldInGame.add(key);
		} else {
			heldInGame.remove(key);
		}
		final boolean down = inWindow && key != gameOnly &&
				OutputWindow.isKeyDown(KeyBindingHelper.getBoundKeyOf(key).getValue());
		if (!down) {
			heldInWindow.remove(key);
		} else if (heldInWindow.add(key)) {
			action.run();
		}
	}

	/**
	 * buttons in the pause menu, to be reachable from inside VR without a binding
	 */
	private void addPauseMenuButtons(@NotNull Screen pauseMenu) {
		final List<AbstractWidget> widgets = Screens.getButtons(pauseMenu);

		if (onScreen()) {
			final List<Button> buttons = new ArrayList<>();
			buttons.add(Button.builder(screenModeLabel(), button -> {
				DesktopCamera.INSTANCE.cycleMode();
				button.setMessage(screenModeLabel());
			}).build());
			buttons.add(Button.builder(screenOutputLabel(), button -> {
				final CameraConfig config = CameraConfig.current();
				config.screenOutput = config.screenOutput == ScreenOutput.WINDOW ? ScreenOutput.SCREEN :
						ScreenOutput.WINDOW;
				config.save();
				button.setMessage(screenOutputLabel());
			}).build());
			if (ClothConfig.isInstalled()) {
				buttons.add(Button.builder(Component.translatable("vrcamera.gui.settings"),
						button -> Minecraft.getInstance().setScreen(ConfigScreen.create(pauseMenu))).build());
			}
			buttons.add(chromaButton());
			buttons.add(Button.builder(Component.translatable("vrcamera.gui.sets"),
					button -> Minecraft.getInstance().setScreen(new CameraSetsScreen(pauseMenu))).build());
			for (int slot = 0; slot < buttons.size(); slot++) {
				final Button button = buttons.get(slot);
				final int[] at = pauseSlot(pauseMenu, slot);
				button.setRectangle(at[2], BUTTON_HEIGHT, at[0], at[1]);

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
				})
				.bounds(EDGE_MARGIN, EDGE_MARGIN, VR_BUTTON_WIDTH, BUTTON_HEIGHT)
				.build());
		widgets.add(Button.builder(Component.translatable("vrcamera.gui.menu"),
						button -> Minecraft.getInstance().setScreen(new CameraMenuScreen(pauseMenu)))
				.bounds(EDGE_MARGIN, 26, VR_BUTTON_WIDTH, BUTTON_HEIGHT)
				.build());
		final Button chroma = chromaButton();
		chroma.setRectangle(VR_BUTTON_WIDTH, BUTTON_HEIGHT, EDGE_MARGIN, 48);
		widgets.add(chroma);
	}

	@Contract(" -> new")
	private @NonNull Component modeLabel() {
		return Component.translatable("vrcamera.gui.mode", CameraController.INSTANCE.mode().label());
	}
}
