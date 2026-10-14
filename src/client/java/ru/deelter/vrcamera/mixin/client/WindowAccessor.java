package ru.deelter.vrcamera.mixin.client;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Window.class)
public interface WindowAccessor {
	@Accessor("framebufferWidth")
	int vrcamera$framebufferWidth();

	@Accessor("framebufferWidth")
	void vrcamera$setFramebufferWidth(int width);

	@Accessor("framebufferHeight")
	int vrcamera$framebufferHeight();

	@Accessor("framebufferHeight")
	void vrcamera$setFramebufferHeight(int height);
}
