package ru.deelter.vrcamera.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import ru.deelter.vrcamera.client.gui.ConfigScreen;
import ru.deelter.vrcamera.client.gui.DebugOverlay;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.shot.ShotType;

import java.util.Locale;

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
