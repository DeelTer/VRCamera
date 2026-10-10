package ru.deelter.vrcamera.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
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
import ru.deelter.vrcamera.client.gui.ClothConfig;
import ru.deelter.vrcamera.client.gui.ConfigScreen;
import ru.deelter.vrcamera.client.gui.DebugOverlay;
import ru.deelter.vrcamera.client.gui.PalettesScreen;
import ru.deelter.vrcamera.client.photo.Palettes;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.shot.ShotType;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
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
	private static final String MAP_PALETTE = "map";
	/**
	 * at the end of the address of /vrcam load: the picture is put in the palette of the player, as a photo is
	 */
	private static final String IN_PALETTE = " true";
	private static final Pattern SET_NAME = Pattern.compile("[A-Za-z0-9_-]{1,24}");

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {

		final LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("vrcam")
				.executes(context -> status(context.getSource()));

		if (Vr.INSTALLED) {
			VrCommands.register(root);
		}
		root.then(ClientCommandManager.literal("photo").executes(context -> {
			VrcameraClient.takePhoto();
			return DONE;
		}));
		root.then(ClientCommandManager.literal("load")
				.then(ClientCommandManager.argument("address", StringArgumentType.greedyString())
						.suggests(VrcamCommand::suggestInPalette).executes(context -> {
					final FabricClientCommandSource source = context.getSource();
					final String typed = StringArgumentType.getString(context, "address").trim();
					final boolean inPalette = typed.endsWith(IN_PALETTE);
					PhotoAlbum.INSTANCE.loadCustom(inPalette ? typed.substring(0, typed.length() - IN_PALETTE.length()) :
							typed, inPalette, source::sendFeedback);
					return DONE;
				})));
		root.then(ClientCommandManager.literal("palette")
				.executes(context -> palettes(context.getSource()))
				.then(ClientCommandManager.literal("folder").executes(context -> {
					Palettes.openFolder();
					return DONE;
				}))
				.then(ClientCommandManager.literal("add")
						.then(ClientCommandManager.argument("address", StringArgumentType.greedyString())
								.executes(context -> addPalette(context.getSource(),
										StringArgumentType.getString(context, "address")))))
				.then(ClientCommandManager.argument("name", StringArgumentType.word()).suggests((context, builder) -> {
					builder.suggest(MAP_PALETTE);
					Palettes.names().forEach(builder::suggest);
					return builder.buildFuture();
				}).executes(context -> palette(context.getSource(), StringArgumentType.getString(context, "name")))));
		root.then(ClientCommandManager.literal("debug").executes(context -> {
			DebugOverlay.toggle();
			return DONE;
		}));
		final LiteralArgumentBuilder<FabricClientCommandSource> pace = ClientCommandManager.literal("pace");
		for (final Pace value : Pace.values()) {
			final String name = value.name().toLowerCase(Locale.ROOT);
			pace.then(ClientCommandManager.literal(name).executes(context -> {
				value.apply(CameraConfig.current());
				CameraConfig.current().save();
				context.getSource().sendFeedback(Component.translatable("vrcamera.command.pace",
						Component.translatable("vrcamera.option.pace." + name)));
				return DONE;
			}));
		}
		root.then(pace);

		final LiteralArgumentBuilder<FabricClientCommandSource> screen = ClientCommandManager.literal("screen");
		for (final DesktopCamera.Mode mode : DesktopCamera.Mode.values()) {
			screen.then(ClientCommandManager.literal(mode.name().toLowerCase(Locale.ROOT)).executes(context -> {
				if (mode != DesktopCamera.Mode.OFF && Vr.isRunning()) {
					context.getSource().sendError(Component.translatable("vrcamera.command.screen.vr"));
					return 0;
				}
				DesktopCamera.INSTANCE.setMode(mode);
				return DONE;
			}));
		}
		for (final ScreenOutput output : ScreenOutput.values()) {

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
			if (!ClothConfig.isInstalled()) {
				context.getSource().sendError(Component.translatable("vrcamera.command.nocloth"));
				return 0;
			}

			final Minecraft mc = context.getSource().getClient();
			mc.schedule(() -> mc.setScreen(ConfigScreen.create(mc.screen)));
			return DONE;
		}));

		dispatcher.register(root);

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
				.then(ClientCommandManager.literal("toggle").executes(context -> {
					DesktopCamera.INSTANCE.toggle();
					return DONE;
				}))
				.then(shots())
				.then(ClientCommandManager.literal("manual").executes(context -> manual(context.getSource())))
				.then(ClientCommandManager.literal("set")
						.executes(context -> cameraSet(context.getSource(), ""))
						.then(ClientCommandManager.argument("set", StringArgumentType.word()).suggests((context, builder) -> {
							DesktopCamera.INSTANCE.cameraSets().forEach(builder::suggest);
							return builder.buildFuture();
						}).executes(context -> cameraSet(context.getSource(),
								StringArgumentType.getString(context, "set")))))
				.then(ClientCommandManager.literal("export").executes(context -> {
					if (!DesktopCamera.INSTANCE.exportCameras()) {
						context.getSource().sendError(Component.translatable("vrcamera.command.set.none"));
						return 0;
					}
					return DONE;
				}))
				.then(ClientCommandManager.literal("import")
						.then(ClientCommandManager.argument("set", StringArgumentType.word()).executes(context -> {
							final String set = StringArgumentType.getString(context, "set");
							if (!SET_NAME.matcher(set).matches() || !DesktopCamera.INSTANCE.importCameras(set)) {
								context.getSource().sendError(Component.translatable("vrcamera.command.set.bad"));
								return 0;
							}
							return DONE;
						})))
				.then(player("follow", DesktopCamera.INSTANCE::film))
				.then(player("with", DesktopCamera.INSTANCE::filmWith))
				.then(ClientCommandManager.argument("name", StringArgumentType.word()).suggests((context, builder) -> {
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
		return ClientCommandManager.literal(name).executes(context -> {
			pick.test(null);
			return DONE;
		}).then(ClientCommandManager.argument("player", StringArgumentType.word()).suggests((context, builder) -> {
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

	/**
	 * after the address of /vrcam load and a space, offers the word that puts the picture in the palette
	 */
	private static CompletableFuture<Suggestions> suggestInPalette(
			CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
		final String typed = builder.getRemaining();
		final String word = IN_PALETTE.trim();
		final int space = typed.lastIndexOf(' ');
		if (space <= 0 || typed.indexOf(' ') != space || !word.startsWith(typed.substring(space + 1))) {
			return builder.buildFuture();
		}
		return builder.createOffset(builder.getStart() + space + 1)
				.suggest(word, Component.translatable("vrcamera.command.load.palette"))
				.buildFuture();
	}

	private static int palettes(FabricClientCommandSource source) {
		final Minecraft mc = source.getClient();
		mc.schedule(() -> mc.setScreen(new PalettesScreen(null)));
		return DONE;
	}

	private static int palette(FabricClientCommandSource source, String name) {
		final String picked = name.equals(MAP_PALETTE) ? Palettes.MAP_COLORS : name;
		if (!picked.isEmpty() && !Palettes.names().contains(picked)) {
			source.sendError(Component.translatable("vrcamera.command.palette.unknown", name));
			return 0;
		}
		Palettes.select(picked);
		source.sendFeedback(Component.translatable("vrcamera.command.palette.set", paletteName(picked)));
		return DONE;
	}

	private static int addPalette(FabricClientCommandSource source, String address) {
		source.sendFeedback(Component.translatable("vrcamera.command.palette.loading"));
		Palettes.fetch(address).whenComplete((name, error) -> Minecraft.getInstance().execute(() -> {
			if (error != null) {
				source.sendError(Component.translatable("vrcamera.command.palette.failed"));
				return;
			}
			Palettes.select(name);
			source.sendFeedback(Component.translatable("vrcamera.command.palette.set", paletteName(name)));
		}));
		return DONE;
	}

	private static Component paletteName(String name) {
		return name.isEmpty() ? Component.translatable("vrcamera.option.photoPalette.map") : Component.literal(name);
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
		final LiteralArgumentBuilder<FabricClientCommandSource> shot = ClientCommandManager.literal("shot").executes(context -> {
			DesktopCamera.INSTANCE.showShot(null);
			return DONE;
		});
		for (final ShotType type : ShotType.values()) {
			shot.then(ClientCommandManager.literal(name(type)).executes(context -> {
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
