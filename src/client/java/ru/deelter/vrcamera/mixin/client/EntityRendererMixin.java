package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import ru.deelter.vrcamera.client.desktop.ChromaKey;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
	@ModifyReturnValue(method = "getPackedLightCoords", at = @At("RETURN"), require = 0)
	private int vrcamera$evenLight(int light) {
		return ChromaKey.lightsEvenly() ? ChromaKey.EVEN_LIGHT : light;
	}
}
