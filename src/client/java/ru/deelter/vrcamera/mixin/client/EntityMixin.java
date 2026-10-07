package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;

@Mixin(Entity.class)
public class EntityMixin {

	// while a player without VR flies the free camera, the mouse turns it and not their head
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$mouseTurnsCamera(double yRot, double xRot, CallbackInfo ci) {
		if ((Object) this == Minecraft.getInstance().player && DesktopCamera.INSTANCE.turn(yRot, xRot)) {
			ci.cancel();
		}
	}
}
