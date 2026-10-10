package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.deelter.vrcamera.client.desktop.DesktopCamera;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"), require = 0)
	private void vrcamera$keysSteerCamera(CallbackInfo ci) {
		if (DesktopCamera.INSTANCE.isSteered()) {
			keyPresses = Input.EMPTY;
			moveVector = Vec2.ZERO;
		}
	}
}
