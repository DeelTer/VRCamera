package ru.deelter.vrcamera.client.figura;

import net.minecraft.world.phys.Vec3;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.entries.FiguraAPI;
import org.figuramc.figura.lua.LuaWhitelist;
import org.figuramc.figura.math.vector.FiguraVec2;
import org.figuramc.figura.math.vector.FiguraVec3;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;

import java.util.Collection;
import java.util.List;

/**
 * What the script of a Figura avatar can ask the mod, as the global {@code vrcamera}: where the camera is and when
 * to look into it. Only loaded by Figura, the mod itself never touches it.
 * <p>
 * Answers the avatar of the player who is filmed. Any other avatar is told that nothing films it.
 */
@LuaWhitelist
public class FiguraEyes implements FiguraAPI {
	private final boolean filmed;

	public FiguraEyes() {
		this(false);
	}

	private FiguraEyes(boolean filmed) {
		this.filmed = filmed;
	}

	@Override
	public FiguraAPI build(Avatar avatar) {
		return new FiguraEyes(avatar.isHost);
	}

	@Override
	public String getName() {
		return "vrcamera";
	}

	@Override
	public Collection<Class<?>> getWhitelistedClasses() {
		return List.of(FiguraEyes.class);
	}

	@Override
	public Collection<Class<?>> getDocsClasses() {
		return List.of();
	}

	@LuaWhitelist
	public boolean isFilming() {
		return this.filmed && DesktopCamera.INSTANCE.filmsSelf() && DesktopCamera.INSTANCE.lens() != null;
	}

	@LuaWhitelist
	public FiguraVec3 getCameraPos() {
		DesktopCamera.Pose lens = isFilming() ? DesktopCamera.INSTANCE.lens() : null;
		return lens == null ? null : FiguraVec3.fromVec3(lens.position());
	}

	@LuaWhitelist
	public FiguraVec3 getLookTarget() {
		Vec3 target = this.filmed ? EyeContact.INSTANCE.target() : null;
		return target == null ? null : FiguraVec3.fromVec3(target);
	}

	@LuaWhitelist
	public FiguraVec2 getLookOffset() {
		double[] offset = this.filmed ? EyeContact.INSTANCE.offset() : null;
		return offset == null ? null : FiguraVec2.of(offset[0], offset[1]);
	}

	@Override
	public String toString() {
		return "VRCameraAPI";
	}
}
