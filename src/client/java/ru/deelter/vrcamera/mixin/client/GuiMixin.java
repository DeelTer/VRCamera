package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.desktop.ChromaKey;

@Mixin(Gui.class)
public class GuiMixin {

	// the dark corners the game puts over its picture would be dark corners on the green as well
	@Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noVignetteOverGreen(CallbackInfo ci) {
		if (ChromaKey.isOn()) {
			ci.cancel();
		}
	}
}
