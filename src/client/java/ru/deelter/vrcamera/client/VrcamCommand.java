package ru.deelter.vrcamera.client;

import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.Pace;
import ru.deelter.vrcamera.client.config.ScreenOutput;
import ru.deelter.vrcamera.client.desktop.ChromaKey;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.OutputWindow;
import ru.deelter.vrcamera.client.gui.ClothConfig;
import ru.deelter.vrcamera.client.gui.ConfigScreen;
import ru.deelter.vrcamera.client.gui.DebugOverlay;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.shot.ShotType;

import java.util.concurrent.CompletableFuture;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The {@code /vrcam} command. Runs on the client only, the server never sees it.
 * <p>
 * Everything the keys and the pause menu buttons do, as text. That makes it usable from the Vivecraft quick commands,
 * which can be bound to controller buttons and are listed in the pause menu.
 */
public final class VrcamCommand {
	private static final int DONE = 1;
	private static final Pattern SET_NAME = Pattern.compile("[A-Za-z0-9_-]{1,24}");

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		final LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("vrcam")
				.executes(context -> status(context.getSource()));

		if (Vr.INSTALLED) {
			VrCommands.register(root);
		}
		root.then(ClientCommands.literal("photo").executes(context -> {
			VrcameraClient.takePhoto();
			return DONE;
		}));
		root.then(ClientCommands.literal("load")
				.then(ClientCommands.argument("address", StringArgumentType.greedyString()).executes(context -> {
					final FabricClientCommandSource source = context.getSource();
					PhotoAlbum.INSTANCE.loadCustom(StringArgumentType.getString(context, "address"),
							source::sendFeedback);
					return DONE;
				})));
		root.then(ClientCommands.literal("debug").executes(context -> {
			DebugOverlay.toggle();
			return DONE;
		}));
		final LiteralArgumentBuilder<FabricClientCommandSource> pace = ClientCommands.literal("pace");
		for (final Pace value : Pace.values()) {
			final String name = value.name().toLowerCase(Locale.ROOT);
			pace.then(ClientCommands.literal(name).executes(context -> {
				value.apply(CameraConfig.current());
				CameraConfig.current().save();
				context.getSource().sendFeedback(Component.translatable("vrcamera.command.pace",
						Component.translatable("vrcamera.option.pace." + name)));
				return DONE;
			}));
		}
		root.then(pace);

		final LiteralArgumentBuilder<FabricClientCommandSource> screen = ClientCommands.literal("screen");
		for (final DesktopCamera.Mode mode : DesktopCamera.Mode.values()) {
			screen.then(ClientCommands.literal(mode.name().toLowerCase(Locale.ROOT)).executes(context -> {
				if (mode != DesktopCamera.Mode.OFF && Vr.isRunning()) {
					context.getSource().sendError(Component.translatable("vrcamera.command.screen.vr"));
					return 0;
				}
				DesktopCamera.INSTANCE.setMode(mode);
				return DONE;
			}));
		}
		for (final ScreenOutput output : ScreenOutput.values()) {
			screen.then(ClientCommands.literal(output == ScreenOutput.WINDOW ? "window" : "here").executes(context -> {
				CameraConfig.current().screenOutput = output;
				CameraConfig.current().save();
				context.getSource().sendFeedback(Component.translatable("vrcamera.command.screen." +
						output.name().toLowerCase(Locale.ROOT)));
				return DONE;
			}));
		}
		screen.then(ClientCommands.literal("resetwindow").executes(context -> {
			OutputWindow.resetPlace();
			return DONE;
		}));

		screen.then(ClientCommands.literal("size")
				.then(ClientCommands.literal("auto").executes(context -> outputSize(context.getSource(), 0, 0)))
				.then(ClientCommands.argument("width", IntegerArgumentType.integer(320, 7680))
						.then(ClientCommands.argument("height", IntegerArgumentType.integer(180, 4320))
								.executes(context -> outputSize(context.getSource(),
										IntegerArgumentType.getInteger(context, "width"),
										IntegerArgumentType.getInteger(context, "height"))))));
		screen.then(ClientCommands.literal("fullscreen").executes(context -> {
			OutputWindow.toggleFullscreen();
			return DONE;
		}));
		screen.then(ClientCommands.literal("clear").executes(context -> {
			DesktopCamera.INSTANCE.clearCameras();
			return DONE;
		}));
		screen.then(ClientCommands.literal("steer").executes(context -> {
			DesktopCamera.INSTANCE.toggleSteering();
			return DONE;
		}));
		root.then(screen);
		root.then(ClientCommands.literal("chroma").executes(context -> {
			ChromaKey.set(!ChromaKey.isOn());
			context.getSource().sendFeedback(Component.translatable(
					ChromaKey.isOn() ? "vrcamera.command.chroma.on" : "vrcamera.command.chroma.off"));
			return DONE;
		}));
		root.then(ClientCommands.literal("reload").executes(context -> {
			if (Vr.INSTALLED) {
				Vive.reloadConfig();
			} else {
				CameraConfig.reload();
			}
			context.getSource().sendFeedback(Component.translatable("vrcamera.command.reloaded"));
			return DONE;
		}));
		root.then(ClientCommands.literal("status").executes(context -> status(context.getSource())));
		root.then(ClientCommands.literal("settings").executes(context -> {
			if (!ClothConfig.isInstalled()) {
				context.getSource().sendError(Component.translatable("vrcamera.command.nocloth"));
				return 0;
			}

			final Minecraft mc = context.getSource().getClient();
			mc.schedule(() -> mc.gui.setScreen(ConfigScreen.create(mc.gui.screen())));
			return DONE;
		}));

		dispatcher.register(root);

		dispatcher.register(ClientCommands.literal("cam")
				.then(ClientCommands.literal("add").executes(context -> {
					DesktopCamera.INSTANCE.addCamera();
					return DONE;
				}))
				.then(ClientCommands.literal("next").executes(context -> {
					DesktopCamera.INSTANCE.nextPoint();
					return DONE;
				}))
				.then(ClientCommands.literal("fly").executes(context -> {
					DesktopCamera.INSTANCE.flyToNext();
					return DONE;
				}))
				.then(ClientCommands.literal("clear").executes(context -> {
					DesktopCamera.INSTANCE.clearCameras();
					return DONE;
				}))
				.then(ClientCommands.literal("toggle").executes(context -> {
					DesktopCamera.INSTANCE.toggle();
					return DONE;
				}))
				.then(shots())
				.then(ClientCommands.literal("manual").executes(context -> manual(context.getSource())))
				.then(ClientCommands.literal("set")
						.executes(context -> cameraSet(context.getSource(), ""))
						.then(ClientCommands.argument("set", StringArgumentType.word())
								.suggests((context, builder) -> suggestCameraSets(builder))
								.executes(context -> cameraSet(context.getSource(),
										StringArgumentType.getString(context, "set")))))
				.then(ClientCommands.literal("export").executes(context -> {
					if (!DesktopCamera.INSTANCE.exportCameras()) {
						context.getSource().sendError(Component.translatable("vrcamera.command.set.none"));
						return 0;
					}
					return DONE;
				}))
				.then(ClientCommands.literal("import")
						.then(ClientCommands.argument("set", StringArgumentType.word()).executes(context ->
								importCameraSet(context.getSource(), StringArgumentType.getString(context, "set")))))
				.then(player("follow", DesktopCamera.INSTANCE::film))
				.then(player("with", DesktopCamera.INSTANCE::filmWith))
				.then(ClientCommands.argument("name", StringArgumentType.word()).suggests((context, builder) -> {
					DesktopCamera.INSTANCE.cameraNames().forEach(builder::suggest);
					return builder.buildFuture();
				}).executes(context -> {
					final String name = StringArgumentType.getString(context, "name");
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
		return ClientCommands.literal(name).executes(context -> {
			pick.test(null);
			return DONE;
		}).then(ClientCommands.argument("player", StringArgumentType.word()).suggests((context, builder) -> {
			DesktopCamera.INSTANCE.playersAround().forEach(builder::suggest);
			return builder.buildFuture();
		}).executes(context -> {
			final String player = StringArgumentType.getString(context, "player");
			if (!pick.test(player)) {
				context.getSource().sendError(Component.translatable("vrcamera.command.noplayer", player));
				return 0;
			}
			return DONE;
		}));
	}

	private static int status(FabricClientCommandSource source) {
		final boolean onScreen = !Vr.INSTALLED || DesktopCamera.INSTANCE.isOn();
		for (final String line : onScreen ? DesktopCamera.INSTANCE.debugLines() : Vive.debugLines()) {
			source.sendFeedback(Component.literal(line));
		}
		return DONE;
	}

	/**
	 * the shots of the director by name, and the next one of its own choice without one
	 */
	private static LiteralArgumentBuilder<FabricClientCommandSource> shots() {
		final LiteralArgumentBuilder<FabricClientCommandSource> shot = ClientCommands.literal("shot");
		shot.executes(context -> {
			DesktopCamera.INSTANCE.showShot(null);
			return DONE;
		});
		for (final ShotType type : ShotType.values()) {
			shot.then(ClientCommands.literal(name(type)).executes(context -> {
				DesktopCamera.INSTANCE.showShot(type);
				return DONE;
			}));
		}
		return shot;
	}

	private static int manual(FabricClientCommandSource source) {
		final CameraConfig config = CameraConfig.current();
		config.directorManual = !config.directorManual;
		config.save();
		source.sendFeedback(Component.translatable(
				config.directorManual ? "vrcamera.command.manual.on" : "vrcamera.command.manual.off"));
		return DONE;
	}

	private static CompletableFuture<Suggestions> suggestCameraSets(SuggestionsBuilder builder) {
		DesktopCamera.INSTANCE.cameraSets().forEach(builder::suggest);
		return builder.buildFuture();
	}

	private static int importCameraSet(FabricClientCommandSource source, String set) {
		if (!SET_NAME.matcher(set).matches() || !DesktopCamera.INSTANCE.importCameras(set)) {
			source.sendError(Component.translatable("vrcamera.command.set.bad"));
			return 0;
		}
		return DONE;
	}

	private static int cameraSet(FabricClientCommandSource source, String set) {
		if (!set.isEmpty() && !SET_NAME.matcher(set).matches()) {
			source.sendError(Component.translatable("vrcamera.command.set.bad"));
			return 0;
		}
		DesktopCamera.INSTANCE.useCameraSet(set);
		return DONE;
	}

	private static int outputSize(FabricClientCommandSource source, int width, int height) {
		final CameraConfig config = CameraConfig.current();
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
