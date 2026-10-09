package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.desktop.ChromaKey;

@Mixin(Gui.class)
public class GuiMixin {
	@Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noVignetteOverGreen(CallbackInfo ci) {
		if (ChromaKey.isOn()) {
			ci.cancel();
		}
	}
}
