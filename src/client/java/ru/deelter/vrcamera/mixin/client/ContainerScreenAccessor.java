package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * where on the screen the window of an inventory or a chest is
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
	@Accessor("leftPos")
	int vrcamera$left();

	@Accessor("topPos")
	int vrcamera$top();

	@Accessor("imageWidth")
	int vrcamera$width();

	@Accessor("imageHeight")
	int vrcamera$height();
}
