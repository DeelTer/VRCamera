package ru.deelter.vrcamera.client.photo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import ru.deelter.vrcamera.client.math.CamMath;

/**
 * A printed photo in the world. Slides out of the camera, hangs from it for a moment, then flutters to the ground.
 * It can be picked up, thrown, and pinned to a block.
 * <p>
 * The sheet is a flat rectangle hanging down from its origin, the middle of its top edge, with the picture on the
 * side of its local +Z.
 */
public final class PhotoSheet {
	// about as wide as the camera it comes out of
	public static final float WIDTH = 0.18F;

	private static final double PRINT_TIME = 0.7;
	private static final double HANG_TIME = 1.0;
	// where the sheet comes out, from the point the camera films from: below it and a bit behind
	private static final Vector3f SLOT = new Vector3f(0, -0.06F, 0.06F);
	private static final double GRAVITY = 3.0;
	// paper does not get fast
	private static final double DRAG = 2.5;
	private static final double GROUND_GAP = 0.01;
	// blocks from the middle of a sheet to a block it is pinned to when let go of
	private static final double PIN_REACH = 0.3;
	// how deep a hand may have pushed the sheet into what it is pinned to
	private static final double PIN_PUSHED_IN = 0.15;
	// closer than this to upright or to lying on its side, a pinned sheet is made exactly that
	private static final double PIN_STRAIGHTEN = Math.toRadians(7);
	// blocks from the middle of a block to the side of a head on it, and to the face of a sign or banner
	private static final double HEAD_HALF_SIZE = 0.25;
	private static final double BOARD_HALF_THICKNESS = 0.07;
	// let go of faster than this it is thrown, also next to a block
	private static final double PIN_MAX_SPEED = 1.2;
	// seconds between looks at what a pinned sheet hangs on, with a few dozen sheets every frame would add up
	private static final double SUPPORT_CHECK_TIME = 0.5;
	// seconds until the picture starts to show on a new sheet, and until it is all there
	private static final double DEVELOP_DELAY = 0.8;
	private static final double DEVELOP_TIME = 5.0;

	private enum State {
		PRINTING, HELD, FALLING, LYING, PINNED
	}

	public final Identifier texture;
	public final int textureSlot;
	/** height by width of the picture */
	public final float aspect;
	/** name of its small picture in the cache of the world, null if that could not be written */
	public final String file;

	private State state = State.PRINTING;
	private double age;
	private Vec3 position = Vec3.ZERO;
	private Vec3 velocity = Vec3.ZERO;
	private final Quaternionf rotation = new Quaternionf();
	private final float restYaw = (float) (Math.random() * Math.PI * 2.0);
	private boolean gone;
	// if the camera held on to it since the last update, it falls when the camera is gone
	private boolean hung = true;
	private double supportCheck = Math.random() * SUPPORT_CHECK_TIME;

	private int hand = -1;
	// where the hand has it, as the controller sees it
	private final Vector3f gripOffset = new Vector3f();
	private final Quaternionf gripRotation = new Quaternionf();

	public PhotoSheet(Identifier texture, int textureSlot, float aspect, String file) {
		this.texture = texture;
		this.textureSlot = textureSlot;
		this.aspect = aspect;
		this.file = file;
	}

	/**
	 * puts a sheet back where it was pinned the last time the world was played
	 */
	public void restore(Vec3 position, Quaternionfc rotation) {
		this.position = position;
		this.rotation.set(rotation);
		this.state = State.PINNED;
		// long developed
		this.age = DEVELOP_DELAY + DEVELOP_TIME;
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
	public boolean isLoose() {
		return this.state == State.FALLING || this.state == State.LYING;
	}

	/**
	 * @return if the hand can take it: from where it is, or out of the other hand
	 */
	public boolean canGrab(int hand) {
		return isLoose() || isPinned() || (this.state == State.HELD && this.hand != hand);
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
	 * a hand takes it, right where it is
	 */
	public void grab(int hand, Vec3 handPosition, Quaternionfc handRotation) {
		Quaternionf toHand = new Quaternionf(handRotation).conjugate();
		toHand.transform(this.gripOffset.set((float) (this.position.x - handPosition.x),
				(float) (this.position.y - handPosition.y), (float) (this.position.z - handPosition.z)));
		toHand.mul(this.rotation, this.gripRotation);
		this.hand = hand;
		this.velocity = Vec3.ZERO;
		this.state = State.HELD;
	}

	public void carry(Vec3 handPosition, Quaternionfc handRotation, double dt) {
		Vector3f offset = handRotation.transform(new Vector3f(this.gripOffset));
		Vec3 next = handPosition.add(offset.x, offset.y, offset.z);
		if (dt > 0) {
			// what it is thrown with, evened out over a few frames
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
		// What the back of the sheet is held against, looked for from a bit in front of it. A hand pushes a
		// sheet into a thin block like a trapdoor, and from in there the nearest face is the wrong one
		BlockHitResult nearest = surface(level, center.subtract(behind.scale(PIN_PUSHED_IN)), center.add(
				behind.scale(PIN_REACH)));
		if (nearest == null) {
			// held some other way, like flat over a floor: whatever is closest
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

		// Heads, standing signs and banners are drawn turned in sixteen steps, while what a ray hits is a box
		// along the axes. A sheet on their side goes on what is seen
		BlockState state = level.getBlockState(nearest.getBlockPos());
		if (!face.getAxis().isVertical() && state.hasProperty(BlockStateProperties.ROTATION_16)) {
			boolean box = state.getBlock() instanceof AbstractSkullBlock;
			// a head has four sides to choose from, a board two
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

		// Turned the way the hand had it: upright, on its side, at an angle. Close to straight it is made
		// straight, a hand does not hold anything level
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

		Vec3 flat = touch.add(out.x * GROUND_GAP, out.y * GROUND_GAP, out.z * GROUND_GAP);
		float half = height() / 2.0F;
		this.position = flat.add(up.x * half, up.y * half, up.z * half);
		this.velocity = Vec3.ZERO;
		this.state = State.PINNED;
		return true;
	}

	/**
	 * @return what a sheet can be pinned to on the way, null if there is nothing
	 */
	private static BlockHitResult surface(Level level, Vec3 from, Vec3 to) {
		BlockHitResult solid = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (solid.getType() != HitResult.Type.MISS) {
			return solid;
		}
		// Signs and banners can be walked through, but are made to hang things on. Not every block with an
		// outline though, or sheets would stick to grass
		BlockHitResult outline = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (outline.getType() == HitResult.Type.MISS) {
			return null;
		}
		BlockState state = level.getBlockState(outline.getBlockPos());
		return state.is(BlockTags.ALL_SIGNS) || state.is(BlockTags.BANNERS) ? outline : null;
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
	public void blowOff(Vec3 velocity) {
		if (this.state == State.PINNED || this.state == State.LYING || this.state == State.FALLING) {
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
		} else if (this.state == State.FALLING) {
			fall(level, dt);
		} else if (this.state == State.PINNED) {
			this.supportCheck -= dt;
			// Not in an unloaded chunk, there is nothing there for a moment and every sheet would come off
			if (this.supportCheck <= 0 && level.isLoaded(BlockPos.containing(center()))) {
				this.supportCheck = SUPPORT_CHECK_TIME;
				if (!supported(level)) {
					this.state = State.FALLING;
				}
			}
		}
	}

	private void fall(Level level, double dt) {
		// drifts from side to side on the way down
		Vec3 drift = new Vec3(Math.sin(this.age * 3.1), 0, Math.cos(this.age * 2.3)).scale(0.6);
		this.velocity = this.velocity.add(drift.x * dt, -GRAVITY * dt, drift.z * dt).scale(Math.exp(-DRAG * dt));

		Quaternionf flat = new Quaternionf().rotationY(this.restYaw).rotateX((float) -Math.PI / 2.0F);
		this.rotation.slerp(flat, (float) (1.0 - Math.exp(-dt / 0.8)))
				.rotateZ((float) (Math.sin(this.age * 6.0) * 0.6 * dt));

		BlockHitResult hit = clip(level, this.velocity.scale(dt));
		if (hit.getType() != HitResult.Type.MISS && hit.getDirection() != Direction.UP) {
			// against a wall, down along it
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
		}
	}

	private BlockHitResult clip(Level level, Vec3 step) {
		return level.clip(new ClipContext(this.position, this.position.add(step).add(0, -GROUND_GAP, 0),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
	}
}
