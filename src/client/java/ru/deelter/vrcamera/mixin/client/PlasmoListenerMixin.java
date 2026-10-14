package ru.deelter.vrcamera.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.voice.VoiceEars;

/**
 * Plasmo Voice sets where the player hears the voices from many times a second, and has no way for another mod to
 * say otherwise: right after it, they are heard from the camera, see {@link VoiceEars}. Only while Plasmo
 * Voice is installed
 */
@Pseudo
@Mixin(targets = "su.plo.voice.client.audio.device.AlOutputDevice$AlListener", remap = false)
public class PlasmoListenerMixin {
	@Inject(method = "update", at = @At("TAIL"), require = 0)
	private void vrcamera$turnToCamera(CallbackInfo ci) {
		VoiceEars.turn();
	}
}
