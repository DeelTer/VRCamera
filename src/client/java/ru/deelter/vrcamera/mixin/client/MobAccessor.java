package ru.deelter.vrcamera.mixin.client;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Mob.class)
public interface MobAccessor {

	/**
	 * @return the sound that mob makes now and then, null if it has none
	 */
	@Invoker("getAmbientSound")
	SoundEvent vrcamera$getAmbientSound();
}
