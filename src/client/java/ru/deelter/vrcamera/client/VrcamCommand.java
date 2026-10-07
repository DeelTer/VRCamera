package ru.deelter.vrcamera.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import ru.deelter.vrcamera.client.config.Pace;
import ru.deelter.vrcamera.client.gui.ConfigScreen;
import ru.deelter.vrcamera.client.gui.DebugOverlay;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.shot.ShotType;

import java.util.Locale;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.ChromaKey;
import ru.deelter.vrcamera.client.config.ScreenOutput;

/**
 * The {@code /vrcam} command. Runs on the client only, the server never sees it.
 * <p>
 * Everything the keys and the pause menu buttons do, as text. That makes it usable from the Vivecraft quick commands,
 * which can be bound to controller buttons and are listed in the pause menu.
 */
public final class VrcamCommand {
	private static final int DONE = 1;

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		CameraController controller = CameraController.INSTANCE;

		LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("vrcam")
				.executes(context -> status(context.getSource(), controller));

		for (CameraController.Mode mode : CameraController.Mode.values()) {
			root.then(ClientCommands.literal(name(mode)).executes(context -> {
				controller.setMode(mode);
				return DONE;
			}));
		}
		root.then(ClientCommands.literal("mode").executes(context -> {
			controller.cycleMode();
			return DONE;
		}));
		root.then(ClientCommands.literal("next").executes(context -> {
			controller.nextShot();
			return DONE;
		}));
		root.then(ClientCommands.literal("hold").executes(context -> {
			controller.toggleHold();
			return DONE;
		}));
		root.then(ClientCommands.literal("summon").executes(context -> {
			controller.summon();
			return DONE;
		}));
		root.then(ClientCommands.literal("photo").executes(context -> {
			controller.takePhoto();
			return DONE;
		}));
		root.then(ClientCommands.literal("load")
				.then(ClientCommands.argument("address", StringArgumentType.greedyString()).executes(context -> {
					FabricClientCommandSource source = context.getSource();
					PhotoAlbum.INSTANCE.loadCustom(StringArgumentType.getString(context, "address"),
							source::sendFeedback);
					return DONE;
				})));
		root.then(ClientCommands.literal("debug").executes(context -> {
			controller.toggleDebug();
			return DONE;
		}));
		LiteralArgumentBuilder<FabricClientCommandSource> pace = ClientCommands.literal("pace");
		for (Pace value : Pace.values()) {
			String name = value.name().toLowerCase(Locale.ROOT);
			pace.then(ClientCommands.literal(name).executes(context -> {
				value.apply(controller.config());
				controller.config().save();
				context.getSource().sendFeedback(Component.translatable("vrcamera.command.pace",
						Component.translatable("vrcamera.option.pace." + name)));
				return DONE;
			}));
		}
		root.then(pace);
		// the camera for a player without VR, in the game window
		LiteralArgumentBuilder<FabricClientCommandSource> screen = ClientCommands.literal("screen");
		for (DesktopCamera.Mode mode : DesktopCamera.Mode.values()) {
			screen.then(ClientCommands.literal(mode.name().toLowerCase(Locale.ROOT)).executes(context -> {
				if (mode != DesktopCamera.Mode.OFF && CameraController.isVRRunning()) {
					context.getSource().sendError(Component.translatable("vrcamera.command.screen.vr"));
					return 0;
				}
				DesktopCamera.INSTANCE.setMode(mode);
				return DONE;
			}));
		}
		for (ScreenOutput output : ScreenOutput.values()) {
			// where it films to: "window" gives it a window of its own and leaves the view of the player alone
			screen.then(ClientCommands.literal(output == ScreenOutput.WINDOW ? "window" : "here").executes(context -> {
				controller.config().screenOutput = output;
				controller.config().save();
				context.getSource().sendFeedback(Component.translatable("vrcamera.command.screen." +
						output.name().toLowerCase(Locale.ROOT)));
				return DONE;
			}));
		}
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
			controller.reloadConfig();
			context.getSource().sendFeedback(Component.translatable("vrcamera.command.reloaded"));
			return DONE;
		}));
		root.then(ClientCommands.literal("status").executes(context -> status(context.getSource(), controller)));
		root.then(ClientCommands.literal("settings").executes(context -> {
			if (!ConfigScreen.isAvailable()) {
				context.getSource().sendError(Component.translatable("vrcamera.command.nocloth"));
				return 0;
			}
			// the chat screen is still closing, open the settings after that
			Minecraft mc = context.getSource().getClient();
			mc.schedule(() -> mc.gui.setScreen(ConfigScreen.create(mc.gui.screen())));
			return DONE;
		}));

		// one literal per shot, so they are all offered by tab completion
		LiteralArgumentBuilder<FabricClientCommandSource> shot = ClientCommands.literal("shot");
		for (ShotType type : ShotType.values()) {
			shot.then(ClientCommands.literal(name(type)).executes(context -> {
				if (!controller.showShot(type)) {
					context.getSource().sendError(Component.translatable("vrcamera.message.novr"));
					return 0;
				}
				return DONE;
			}));
		}
		root.then(shot);

		root.then(ClientCommands.literal("preset")
				.then(ClientCommands.literal("next").executes(context -> {
					controller.nextPreset();
					return DONE;
				}))
				.then(ClientCommands.literal("new").executes(context -> {
					controller.newPreset();
					return DONE;
				}))
				.then(ClientCommands.literal("delete").executes(context -> {
					controller.deletePreset();
					return DONE;
				}))
				.then(ClientCommands.argument("number", IntegerArgumentType.integer(1)).executes(context -> {
					int number = IntegerArgumentType.getInteger(context, "number");
					if (!controller.selectPreset(number - 1)) {
						context.getSource().sendError(
								Component.translatable("vrcamera.command.nopreset", controller.presetLabel()));
						return 0;
					}
					return DONE;
				})));

		dispatcher.register(root);
		// short, for the one thing that is typed in the middle of a recording: which free camera films
		dispatcher.register(ClientCommands.literal("cam")
				.then(ClientCommands.argument("name", StringArgumentType.word()).suggests((context, builder) -> {
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

	private static String name(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}

	private static int status(FabricClientCommandSource source, CameraController controller) {
		for (String line : DebugOverlay.lines(controller)) {
			source.sendFeedback(Component.literal(line));
		}
		return DONE;
	}
}
