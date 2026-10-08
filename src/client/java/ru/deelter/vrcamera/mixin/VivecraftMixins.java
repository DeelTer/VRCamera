package ru.deelter.vrcamera.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * the mixins into Vivecraft are left out while there is no Vivecraft to mix into
 */
public final class VivecraftMixins implements IMixinConfigPlugin {
	private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("vivecraft");

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return INSTALLED;
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Nullable
	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Nullable
	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
