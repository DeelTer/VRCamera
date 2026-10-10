package ru.deelter.vrcamera.client.photo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.math.PoseTrail;

/**
 * A printed photo in the world. Slides out of the camera, hangs from it for a moment, then flutters to the ground.
 * It can be picked up, thrown, and pinned to a block.
 * <p>
 * The sheet is a flat rectangle hanging down from its origin, the middle of its top edge, with the picture on the
 * side of its local +Z.
 */
public final class PhotoSheet {
	public static final float WIDTH = 0.18F;

	private static final double PRINT_TIME = 0.7;
	private static final double HANG_TIME = 1.0;
	private static final Vector3f SLOT = new Vector3f(0, -0.06F, 0.06F);
	private static final double GRAVITY = 3.0;
	private static final double DRAG = 2.5;
	private static final double GROUND_GAP = 0.01;
	private static final double PIN_REACH = 0.3;
	private static final double PIN_PUSHED_IN = 0.15;
	private static final double PIN_STRAIGHTEN = Math.toRadians(7);
	private static final double HEAD_HALF_SIZE = 0.25;
	private static final double BOARD_HALF_THICKNESS = 0.07;
	private static final double PIN_MAX_SPEED = 1.2;
	private static final double SUPPORT_CHECK_TIME = 0.5;
	private static final long GHOST_DELAY_NANOS = 300_000_000L;
	private static final long GHOST_STEP_NANOS = 200_000_000L;
	private static final long GHOST_REST_NANOS = 450_000_000L;
	private static final double GHOST_JUMP = 6.0;
	private static final double HOVER_TIME = 6.0;
	private static final double DEVELOP_DELAY = 0.8;
	private static final double DEVELOP_TIME = 5.0;
	private static long pins;
	public final ResourceLocation texture;
	public final int textureSlot;
	/**
	 * height by width of the picture
	 */
	public final float aspect;
	/**
	 * name of its small picture in the cache of the world, null if that could not be written
	 */
	public final String file;
	private final Quaternionf rotation = new Quaternionf();
	private final float restYaw = (float) (Math.random() * Math.PI * 2.0);
	private final PoseTrail ghostTrail = new PoseTrail(GHOST_DELAY_NANOS, GHOST_STEP_NANOS / 2, GHOST_STEP_NANOS,
			GHOST_REST_NANOS, GHOST_STEP_NANOS);
	private final Quaternionf sharedRotation = new Quaternionf();
	private final Vector3f gripOffset = new Vector3f();
	private final Quaternionf gripRotation = new Quaternionf();
	/**
	 * the picture as it went over the network, kept to pin the sheet again without packing it once more
	 */
	public byte[] packed;
	/**
	 * a picture from somewhere else, not a photo taken in the game. Others only see those if they ask to
	 */
	public boolean custom;
	/**
	 * Stands in for a custom picture of another player that this one did not ask to see: black, without the
	 * picture, which was not even fetched
	 */
	public boolean placeholder;
	/**
	 * if this player made it: took the photo or loaded the picture. Only what is their own is theirs to
	 * throw away
	 */
	public boolean own = true;
	/**
	 * how many pinned sheets lie under it, see PhotoAlbum
	 */
	int layer;
	private State state = State.PRINTING;
	private double age;
	private Vec3 position = Vec3.ZERO;
	private Vec3 velocity = Vec3.ZERO;
	private boolean gone;
	private boolean hung = true;
	private double supportCheck = Math.random() * SUPPORT_CHECK_TIME;
	private long looseId;
	private Vec3 sharedPosition;
	private long remoteId;
	private long pinOrder;
	private boolean awaitingServer;
	private boolean removable = true;
	private BlockPos support = BlockPos.ZERO;
	private int hand = -1;
	private double hovering;

	public PhotoSheet(ResourceLocation texture, int textureSlot, float aspect, String file) {
		this.texture = texture;
		this.textureSlot = textureSlot;
		this.aspect = aspect;
		this.file = file;
	}

	/**
	 * @return what a sheet can be pinned to on the way, null if there is nothing
	 */
	private static BlockHitResult surface(Level level, Vec3 from, Vec3 to) {
		BlockHitResult outline = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (outline.getType() != HitResult.Type.MISS && holds(level, outline.getBlockPos())) {
			return outline;
		}
		BlockHitResult solid = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (solid.getType() == HitResult.Type.MISS) {
			return null;
		}
		BlockPos block = solid.getBlockPos();
		VoxelShape seen = level.getBlockState(block).getShape(level, block);
		return !seen.isEmpty() && seen.bounds().move(block).inflate(0.02).contains(solid.getLocation()) ? solid : null;
	}

	/**
	 * @return if sheets can be pinned to that block. Everything that is in the way of a player, and of what can
	 * be walked through the things made to be on a wall. Not every block with an outline, or sheets would stick
	 * to grass
	 */
	private static boolean holds(Level level, BlockPos block) {
		BlockState state = level.getBlockState(block);
		return !state.getCollisionShape(level, block).isEmpty() || state.is(BlockTags.ALL_SIGNS) ||
				state.is(BlockTags.BANNERS) || state.is(BlockTags.BUTTONS) || state.is(Blocks.LEVER) ||
				state.is(Blocks.TRIPWIRE_HOOK);
	}

	/**
	 * puts a sheet back where it was pinned the last time the world was played
	 */
	public void restore(Vec3 position, Quaternionfc rotation) {
		this.position = position;
		this.rotation.set(rotation);
		this.state = State.PINNED;
		pinOrder = ++pins;
		this.age = DEVELOP_DELAY + DEVELOP_TIME;
	}

	/**
	 * @return if this sheet was pinned or came to lie before the other one, which lies on top then. On a server by the numbers
	 * it gave them, for everyone to see the same one on top. One it has no number for yet is the newest
	 */
	public boolean pinnedBefore(PhotoSheet other) {
		if (remoteId != 0 && other.remoteId != 0) {
			return remoteId < other.remoteId;
		}
		if (remoteId != 0 || other.remoteId != 0) {
			return remoteId != 0;
		}
		return pinOrder < other.pinOrder;
	}

	public long pinOrder() {
		return pinOrder;
	}

	public long remoteId() {
		return this.remoteId;
	}

	public boolean isAwaitingServer() {
		return this.awaitingServer;
	}

	public BlockPos support() {
		return this.support;
	}

	/**
	 * @return if what happens to it is decided by a server and not here
	 */
	public boolean isServerOwned() {
		return this.remoteId != 0 || this.awaitingServer;
	}

	public void awaitServer() {
		this.awaitingServer = true;
	}

	/**
	 * @param remoteId what the server calls it, 0 if it does not know it anymore
	 */
	public void setRemote(long remoteId, boolean removable) {
		this.remoteId = remoteId;
		this.removable = removable || remoteId == 0;
		this.awaitingServer = false;
	}

	public Vec3 position() {
		return this.position;
	}

	public Quaternionf rotation() {
		return this.rotation;
	}

	public float height() {
		return WIDTH * this.aspect;
	}

	public Vec3 center() {
		Vector3f down = this.rotation.transform(new Vector3f(0, -height() / 2.0F, 0));
		return this.position.add(down.x, down.y, down.z);
	}

	/**
	 * @return part of the sheet that is out of the camera, from its lower edge
	 */
	public float printed() {
		return this.state == State.PRINTING ? (float) CamMath.smoothstep(this.age / PRINT_TIME) : 1.0F;
	}

	public boolean isGone() {
		return this.gone;
	}

	public boolean isPrinting() {
		return this.state == State.PRINTING;
	}

	public boolean isPinned() {
		return this.state == State.PINNED;
	}

	/**
	 * @return if it is on its own and nobody decided to keep it: falling or lying somewhere
	 */
	/**
	 * @return if it is pinned or lies somewhere, where another sheet can be at the very same place
	 */
	public boolean isLaid() {
		return state == State.PINNED || state == State.LYING || state == State.GHOST;
	}

	public boolean isLoose() {
		return this.state == State.FALLING || this.state == State.LYING;
	}

	/**
	 * @return if the hand can take it: from where it is, or out of the other hand
	 */
	public boolean canGrab(int hand) {
		if (isPinned()) {
			return this.removable && !this.awaitingServer && !this.placeholder;
		}
		return !this.placeholder && (isLoose() || isGhost() || (this.state == State.HELD && this.hand != hand));
	}

	/**
	 * @return if there is a picture on it to show large, and nobody has it in a hand
	 */
	public boolean canPreview() {
		return !placeholder && (isPinned() || isLoose() || isGhost());
	}

	public int hand() {
		return this.state == State.HELD ? this.hand : -1;
	}

	/**
	 * the camera it is printed by holds it at its slot
	 */
	public void hangFrom(Vec3 camera, Quaternionfc cameraRotation, float worldScale) {
		if (this.state != State.PRINTING) {
			return;
		}
		Vector3f slot = cameraRotation.transform(new Vector3f(SLOT)).mul(worldScale);
		this.position = camera.add(slot.x, slot.y, slot.z);
		this.rotation.set(cameraRotation);
		this.hung = true;
	}

	/**
	 * The hand of a player at a screen takes it: by its middle, and turned the way that hand is from then on
	 */
	public void take(int hand) {
		gripOffset.set(0, height() / 2.0F, 0);
		gripRotation.identity();
		this.hand = hand;
		velocity = Vec3.ZERO;
		hovering = 0;
		state = State.HELD;
	}

	/**
	 * A sheet that was not printed by a camera: it starts in the air, in front of who took the photo, and stays
	 * there for a moment to be taken before it falls
	 */
	public void hover(Vec3 position, Quaternionfc rotation) {
		toss(position, rotation, Vec3.ZERO);
		hovering = HOVER_TIME;
	}

	/**
	 * a hand takes it, right where it is
	 */
	public void grab(int hand, Vec3 handPosition, Quaternionfc handRotation) {
		Quaternionf toHand = new Quaternionf(handRotation).conjugate();
		toHand.transform(this.gripOffset.set((float) (this.position.x - handPosition.x),
				(float) (this.position.y - handPosition.y), (float) (this.position.z - handPosition.z)));
		toHand.mul(this.rotation, this.gripRotation);
		this.hand = hand;
		velocity = Vec3.ZERO;
		hovering = 0;
		state = State.HELD;
	}

	public void carry(Vec3 handPosition, Quaternionfc handRotation, double dt) {
		Vector3f offset = handRotation.transform(new Vector3f(this.gripOffset));
		Vec3 next = handPosition.add(offset.x, offset.y, offset.z);
		if (dt > 0) {
			this.velocity = this.velocity.lerp(next.subtract(this.position).scale(1.0 / dt), 0.35);
		}
		this.position = next;
		handRotation.mul(this.gripRotation, this.rotation);
	}

	/**
	 * The hand lets go. Next to a block the sheet is pinned to it, anywhere else it is thrown.
	 */
	public void release(Level level) {
		this.hand = -1;
		if (!pin(level)) {
			this.state = State.FALLING;
		}
	}

	private boolean pin(Level level) {
		if (this.velocity.length() > PIN_MAX_SPEED) {
			return false;
		}
		Vec3 center = center();
		Vector3f front = this.rotation.transform(new Vector3f(0, 0, 1));
		Vec3 behind = new Vec3(-front.x, -front.y, -front.z);
		BlockHitResult nearest = surface(level, center.subtract(behind.scale(PIN_PUSHED_IN)), center.add(
				behind.scale(PIN_REACH)));
		if (nearest == null) {
			double nearestDistance = Double.MAX_VALUE;
			for (Direction direction : Direction.values()) {
				Vec3 reach = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ())
						.scale(PIN_REACH);
				BlockHitResult hit = surface(level, center, center.add(reach));
				if (hit != null && hit.getLocation().distanceToSqr(center) < nearestDistance) {
					nearest = hit;
					nearestDistance = hit.getLocation().distanceToSqr(center);
				}
			}
		}
		if (nearest == null) {
			return false;
		}
		Direction face = nearest.getDirection();
		Vector3f out = new Vector3f(face.getStepX(), face.getStepY(), face.getStepZ());
		Vec3 touch = nearest.getLocation();

		BlockState state = level.getBlockState(nearest.getBlockPos());
		if (!face.getAxis().isVertical() && state.hasProperty(BlockStateProperties.ROTATION_16)) {
			boolean box = state.getBlock() instanceof AbstractSkullBlock;
			double step = box ? Math.PI / 2.0 : Math.PI;
			double turned = Math.toRadians(state.getValue(BlockStateProperties.ROTATION_16) * 22.5);
			double facing = Math.atan2(-front.x, front.z);
			double yaw = turned + Math.round((facing - turned) / step) * step;
			out.set((float) -Math.sin(yaw), 0, (float) Math.cos(yaw));
			Vec3 normal = new Vec3(out.x, 0, out.z);
			Vec3 onSide = Vec3.atBottomCenterOf(nearest.getBlockPos()).add(normal.scale(box ? HEAD_HALF_SIZE :
					BOARD_HALF_THICKNESS));
			touch = center.subtract(normal.scale(center.subtract(onSide).dot(normal)));
		}

		Vector3f up = this.rotation.transform(new Vector3f(0, 1, 0));
		up.sub(new Vector3f(out).mul(up.dot(out)));
		if (up.lengthSquared() < 1.0E-4F) {
			up.set(0, 0, 1);
		}
		up.normalize();
		Vector3f level0 = face.getAxis().isVertical() ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
		level0.sub(new Vector3f(out).mul(level0.dot(out)));
		if (level0.lengthSquared() > 1.0E-4F) {
			level0.normalize();
			Vector3f side = out.cross(level0, new Vector3f());
			double angle = Math.atan2(up.dot(side), up.dot(level0));
			double straight = Math.round(angle / (Math.PI / 2.0)) * (Math.PI / 2.0);
			if (Math.abs(angle - straight) < PIN_STRAIGHTEN) {
				up.set(level0).mul((float) Math.cos(straight)).add(side.mul((float) Math.sin(straight)));
				up.normalize();
			}
		}
		Vector3f right = up.cross(out, new Vector3f());
		this.rotation.setFromNormalized(new Matrix3f(right, up, out));

		this.support = nearest.getBlockPos();
		Vec3 flat = touch.add(out.x * GROUND_GAP, out.y * GROUND_GAP, out.z * GROUND_GAP);
		float half = height() / 2.0F;
		this.position = flat.add(up.x * half, up.y * half, up.z * half);
		this.velocity = Vec3.ZERO;
		this.state = State.PINNED;
		pinOrder = ++pins;
		return true;
	}

	/**
	 * @return if what the sheet is pinned to is still there
	 */
	private boolean supported(Level level) {
		Vector3f front = this.rotation.transform(new Vector3f(0, 0, 1));
		Vec3 out = new Vec3(front.x, front.y, front.z);
		Vec3 center = center();
		return surface(level, center.add(out.scale(0.05)), center.subtract(out.scale(0.15))) != null;
	}

	/**
	 * comes off what it is pinned to, or is blown away from where it lies
	 */
	public long looseId() {
		return this.looseId;
	}

	public void setLooseId(long looseId) {
		this.looseId = looseId;
	}

	public boolean isGhost() {
		return this.state == State.GHOST;
	}

	/**
	 * @param looseId what the server calls the sheet
	 */
	public void makeGhost(long looseId, Vec3 position, Quaternionfc rotation) {
		this.looseId = looseId;
		this.position = position;
		this.rotation.set(rotation);
		this.ghostTrail.clear();
		this.state = State.GHOST;
		pinOrder = ++pins;
		this.age = DEVELOP_DELAY + DEVELOP_TIME;
	}

	public void ghostTo(Vec3 position, Quaternionfc rotation) {
		Vec3 last = this.ghostTrail.last();
		if ((last == null ? this.position : last).distanceToSqr(position) > GHOST_JUMP * GHOST_JUMP) {
			this.ghostTrail.clear();
			this.position = position;
			this.rotation.set(rotation);
			return;
		}
		this.ghostTrail.add(position, rotation, this.position, this.rotation);
	}

	private void followGhost() {
		Vec3 shown = this.ghostTrail.follow(this.rotation);
		if (shown != null) {
			this.position = shown;
		}
	}

	/**
	 * a sheet that was not printed by a camera: it starts in the air and falls
	 */
	public void toss(Vec3 position, Quaternionfc rotation, Vec3 velocity) {
		this.position = position;
		this.rotation.set(rotation);
		this.velocity = velocity;
		this.state = State.FALLING;
	}

	/**
	 * @return if it is somewhere else than when the others were last told. Tells them from here on that it is
	 * where it is now
	 */
	public boolean movedSinceShared() {
		boolean moved = this.sharedPosition == null || this.sharedPosition.distanceToSqr(this.position) > 1.0E-4 ||
				Math.abs(this.sharedRotation.dot(this.rotation)) < 0.9995F;
		if (moved) {
			this.sharedPosition = this.position;
			this.sharedRotation.set(this.rotation);
		}
		return moved;
	}

	public void blowOff(Vec3 velocity) {
		if (state == State.PINNED || state == State.LYING || state == State.FALLING) {
			hovering = 0;
			this.velocity = velocity;
			this.state = State.FALLING;
		}
	}

	/**
	 * @return how much the picture is still hidden, a photo takes a moment to show after it is printed
	 */
	public float veil() {
		return 1.0F - (float) CamMath.smoothstep((this.age - DEVELOP_DELAY) / DEVELOP_TIME);
	}

	public void update(Level level, double dt) {
		this.age += dt;
		if (this.state == State.PRINTING) {
			if (!this.hung || this.age > PRINT_TIME + HANG_TIME) {
				this.state = State.FALLING;
			}
			this.hung = false;
		} else if (state == State.FALLING && hovering > 0) {
			hovering -= dt;
		} else if (state == State.FALLING) {
			fall(level, dt);
		} else if (this.state == State.GHOST) {
			followGhost();
		} else if (this.state == State.PINNED) {
			this.supportCheck -= dt;
			if (this.remoteId == 0 && !this.awaitingServer && this.supportCheck <= 0 &&
					level.isLoaded(BlockPos.containing(center()))) {
				this.supportCheck = SUPPORT_CHECK_TIME;
				if (!supported(level)) {
					this.state = State.FALLING;
				}
			}
		}
	}

	private void fall(Level level, double dt) {
		Vec3 drift = new Vec3(Math.sin(this.age * 3.1), 0, Math.cos(this.age * 2.3)).scale(0.6);
		this.velocity = this.velocity.add(drift.x * dt, -GRAVITY * dt, drift.z * dt).scale(Math.exp(-DRAG * dt));

		Quaternionf flat = new Quaternionf().rotationY(this.restYaw).rotateX((float) -Math.PI / 2.0F);
		this.rotation.slerp(flat, (float) (1.0 - Math.exp(-dt / 0.8)))
				.rotateZ((float) (Math.sin(this.age * 6.0) * 0.6 * dt));

		BlockHitResult hit = clip(level, this.velocity.scale(dt));
		if (hit.getType() != HitResult.Type.MISS && hit.getDirection() != Direction.UP) {
			this.velocity = new Vec3(0, Math.min(this.velocity.y, 0), 0);
			hit = clip(level, this.velocity.scale(dt));
		}
		if (hit.getType() == HitResult.Type.MISS) {
			this.position = this.position.add(this.velocity.scale(dt));
			this.gone = this.position.y < level.getMinY() - 16;
		} else if (hit.getDirection() == Direction.UP) {
			this.position = hit.getLocation().add(0, GROUND_GAP, 0);
			this.rotation.set(flat);
			this.velocity = Vec3.ZERO;
			this.state = State.LYING;
			pinOrder = ++pins;
		}
	}

	private BlockHitResult clip(Level level, Vec3 step) {
		return level.clip(new ClipContext(this.position, this.position.add(step).add(0, -GROUND_GAP, 0),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
	}

	private enum State {
		PRINTING, HELD, FALLING, LYING, PINNED,
		/**
		 * someone else's loose sheet: shown where their client says it is, not moved here
		 */
		GHOST
	}
}
