package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.deelter.vrcamera.client.desktop.ChromaKey;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

	// in front of the green screen only what the player could see is filmed
	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"), require = 0)
	private boolean vrcamera$onlySeenEntities(boolean visible, @Local(argsOnly = true) Entity entity) {
		return visible && (!ChromaKey.applies() || ChromaKey.shows(entity));
	}
}
