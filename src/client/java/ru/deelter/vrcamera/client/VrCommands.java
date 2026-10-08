package ru.deelter.vrcamera.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import ru.deelter.vrcamera.client.shot.ShotType;

/**
 * The part of /vrcam that works the camera of Vivecraft. Left out without Vivecraft, a camera on the screen has
 * /vrcam screen and /cam.
 */
final class VrCommands {
	private static final int DONE = 1;

	private VrCommands() {
	}

	static void register(LiteralArgumentBuilder<FabricClientCommandSource> root) {
		final CameraController controller = CameraController.INSTANCE;
		for (final CameraController.Mode mode : CameraController.Mode.values()) {
			root.then(ClientCommands.literal(VrcamCommand.name(mode)).executes(context -> {
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

		final LiteralArgumentBuilder<FabricClientCommandSource> shot = ClientCommands.literal("shot");
		for (final ShotType type : ShotType.values()) {
			shot.then(ClientCommands.literal(VrcamCommand.name(type)).executes(context -> {
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
					final int number = IntegerArgumentType.getInteger(context, "number");
					if (!controller.selectPreset(number - 1)) {
						context.getSource().sendError(
								Component.translatable("vrcamera.command.nopreset", controller.presetLabel()));
						return 0;
					}
					return DONE;
				})));

	}
}
