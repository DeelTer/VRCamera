package ru.deelter.vrcamera.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.Pace;
import ru.deelter.vrcamera.client.config.ScreenOutput;
import ru.deelter.vrcamera.client.desktop.ChromaKey;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.OutputWindow;
import ru.deelter.vrcamera.client.gui.ConfigScreen;
import ru.deelter.vrcamera.client.gui.DebugOverlay;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * The {@code /vrcam} command. Runs on the client only, the server never sees it.
 * <p>
 * Everything the keys and the pause menu buttons do, as text. That makes it usable from the Vivecraft quick commands,
 * which can be bound to controller buttons and are listed in the pause menu.
 */
public final class VrcamCommand {
	private static final int DONE = 1;

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {

		LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("vrcam")
				.executes(context -> status(context.getSource()));

		if (Vr.INSTALLED) {
			VrCommands.register(root);
		}
		root.then(ClientCommandManager.literal("photo").executes(context -> {
			VrcameraClient.takePhoto();
			return DONE;
		}));
		root.then(ClientCommandManager.literal("load")
				.then(ClientCommandManager.argument("address", StringArgumentType.greedyString()).executes(context -> {
					FabricClientCommandSource source = context.getSource();
					PhotoAlbum.INSTANCE.loadCustom(StringArgumentType.getString(context, "address"),
							source::sendFeedback);
					return DONE;
				})));
		root.then(ClientCommandManager.literal("debug").executes(context -> {
			DebugOverlay.toggle();
			return DONE;
		}));
		LiteralArgumentBuilder<FabricClientCommandSource> pace = ClientCommandManager.literal("pace");
		for (Pace value : Pace.values()) {
			String name = value.name().toLowerCase(Locale.ROOT);
			pace.then(ClientCommandManager.literal(name).executes(context -> {
				value.apply(CameraConfig.current());
				CameraConfig.current().save();
				context.getSource().sendFeedback(Component.translatable("vrcamera.command.pace",
						Component.translatable("vrcamera.option.pace." + name)));
				return DONE;
			}));
		}
		root.then(pace);
		// the camera for a player without VR, in the game window
		LiteralArgumentBuilder<FabricClientCommandSource> screen = ClientCommandManager.literal("screen");
		for (DesktopCamera.Mode mode : DesktopCamera.Mode.values()) {
			screen.then(ClientCommandManager.literal(mode.name().toLowerCase(Locale.ROOT)).executes(context -> {
				if (mode != DesktopCamera.Mode.OFF && Vr.isRunning()) {
					context.getSource().sendError(Component.translatable("vrcamera.command.screen.vr"));
					return 0;
				}
				DesktopCamera.INSTANCE.setMode(mode);
				return DONE;
			}));
		}
		for (ScreenOutput output : ScreenOutput.values()) {
			// where it films to: "window" gives it a window of its own and leaves the view of the player alone
			screen.then(ClientCommandManager.literal(output == ScreenOutput.WINDOW ? "window" : "here").executes(context -> {
				CameraConfig.current().screenOutput = output;
				CameraConfig.current().save();
				context.getSource().sendFeedback(Component.translatable("vrcamera.command.screen." +
						output.name().toLowerCase(Locale.ROOT)));
				return DONE;
			}));
		}
		screen.then(ClientCommandManager.literal("resetwindow").executes(context -> {
			OutputWindow.resetPlace();
			return DONE;
		}));
		// how many pixels the picture has: a window can only be recorded as large as it is
		screen.then(ClientCommandManager.literal("size")
				.then(ClientCommandManager.literal("auto").executes(context -> outputSize(context.getSource(), 0, 0)))
				.then(ClientCommandManager.argument("width", IntegerArgumentType.integer(320, 7680))
						.then(ClientCommandManager.argument("height", IntegerArgumentType.integer(180, 4320))
								.executes(context -> outputSize(context.getSource(),
										IntegerArgumentType.getInteger(context, "width"),
										IntegerArgumentType.getInteger(context, "height"))))));
		screen.then(ClientCommandManager.literal("fullscreen").executes(context -> {
			OutputWindow.toggleFullscreen();
			return DONE;
		}));
		screen.then(ClientCommandManager.literal("clear").executes(context -> {
			DesktopCamera.INSTANCE.clearCameras();
			return DONE;
		}));
		screen.then(ClientCommandManager.literal("steer").executes(context -> {
			DesktopCamera.INSTANCE.toggleSteering();
			return DONE;
		}));
		root.then(screen);
		root.then(ClientCommandManager.literal("chroma").executes(context -> {
			ChromaKey.set(!ChromaKey.isOn());
			context.getSource().sendFeedback(Component.translatable(
					ChromaKey.isOn() ? "vrcamera.command.chroma.on" : "vrcamera.command.chroma.off"));
			return DONE;
		}));
		root.then(ClientCommandManager.literal("reload").executes(context -> {
			if (Vr.INSTALLED) {
				Vive.reloadConfig();
			} else {
				CameraConfig.reload();
			}
			context.getSource().sendFeedback(Component.translatable("vrcamera.command.reloaded"));
			return DONE;
		}));
		root.then(ClientCommandManager.literal("status").executes(context -> status(context.getSource())));
		root.then(ClientCommandManager.literal("settings").executes(context -> {
			if (!ConfigScreen.isAvailable()) {
				context.getSource().sendError(Component.translatable("vrcamera.command.nocloth"));
				return 0;
			}
			// the chat screen is still closing, open the settings after that
			Minecraft mc = context.getSource().getClient();
			mc.schedule(() -> mc.setScreen(ConfigScreen.create(mc.screen)));
			return DONE;
		}));

		dispatcher.register(root);
		// short, for what is typed in the middle of a recording: the free cameras
		dispatcher.register(ClientCommandManager.literal("cam")
				.then(ClientCommandManager.literal("add").executes(context -> {
					DesktopCamera.INSTANCE.addCamera();
					return DONE;
				}))
				.then(ClientCommandManager.literal("next").executes(context -> {
					DesktopCamera.INSTANCE.nextPoint();
					return DONE;
				}))
				.then(ClientCommandManager.literal("fly").executes(context -> {
					DesktopCamera.INSTANCE.flyToNext();
					return DONE;
				}))
				.then(ClientCommandManager.literal("clear").executes(context -> {
					DesktopCamera.INSTANCE.clearCameras();
					return DONE;
				}))
				.then(player("follow", DesktopCamera.INSTANCE::film))
				.then(player("with", DesktopCamera.INSTANCE::filmWith))
				.then(ClientCommandManager.argument("name", StringArgumentType.word()).suggests((context, builder) -> {
					DesktopCamera.INSTANCE.cameraNames().forEach(builder::suggest);
					return builder.buildFuture();
				}).executes(context -> {
					String name = StringArgumentType.getString(context, "name");
					if (!DesktopCamera.INSTANCE.showCamera(name)) {
						context.getSource().sendError(Component.translatable("vrcamera.command.cam.none", name));
						return 0;
					}
					return DONE;
				})));
	}

	static String name(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * a command that takes the name of a player around, and goes back to no one without a name
	 *
	 * @param pick told the name, or null for no one. False if there is no such player
	 */
	private static LiteralArgumentBuilder<FabricClientCommandSource> player(String name, Predicate<String> pick) {
		return ClientCommandManager.literal(name).executes(context -> {
			pick.test(null);
			return DONE;
		}).then(ClientCommandManager.argument("player", StringArgumentType.word()).suggests((context, builder) -> {
			DesktopCamera.INSTANCE.playersAround().forEach(builder::suggest);
			return builder.buildFuture();
		}).executes(context -> {
			String player = StringArgumentType.getString(context, "player");
			if (!pick.test(player)) {
				context.getSource().sendError(Component.translatable("vrcamera.command.noplayer", player));
				return 0;
			}
			return DONE;
		}));
	}

	private static int status(FabricClientCommandSource source) {
		boolean onScreen = !Vr.INSTALLED || DesktopCamera.INSTANCE.isOn();
		for (String line : onScreen ? DesktopCamera.INSTANCE.debugLines() : Vive.debugLines()) {
			source.sendFeedback(Component.literal(line));
		}
		return DONE;
	}

	private static int outputSize(FabricClientCommandSource source, int width, int height) {
		CameraConfig config = CameraConfig.current();
		config.outputWidth = width;
		config.outputHeight = height;
		config.save();
		if (config.hasOutputSize()) {
			OutputWindow.setSize(width, height);
			source.sendFeedback(Component.translatable("vrcamera.command.screen.size", width, height));
		} else {
			source.sendFeedback(Component.translatable("vrcamera.command.screen.size.auto"));
		}
		return DONE;
	}
}
