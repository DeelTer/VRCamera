package ru.deelter.vrcamera.client.math;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Where something of another player was heard to be, a few times per second and not evenly. Shown a moment late,
 * between two of those places: chasing the last one moves in jerks, this moves along the path it took.
 */
public final class PoseTrail {
	private static final int CAPACITY = 8;
	private final ArrayDeque<Sample> samples = new ArrayDeque<>();
	private final long delay;
	private final long minSpacing;
	private final long maxAhead;
	private final long restAfter;
	private final long restStep;

	/**
	 * @param delay      how long ago the place is that is shown
	 * @param minSpacing what arrives in a bunch was not sent in one: at least this far apart
	 * @param maxAhead   and never more than this ahead of when it arrived
	 * @param restAfter  not heard of for this long it stood still, and moves on from where it was. 0 = it is heard
	 *                   of all the time
	 * @param restStep   how long before the next place it started to move again
	 */
	public PoseTrail(long delay, long minSpacing, long maxAhead, long restAfter, long restStep) {
		this.delay = delay;
		this.minSpacing = minSpacing;
		this.maxAhead = maxAhead;
		this.restAfter = restAfter;
		this.restStep = restStep;
	}

	public void clear() {
		samples.clear();
	}

	/**
	 * @return where it was heard to be last, null if nowhere yet
	 */
	@Nullable
	public Vec3 last() {
		final Sample last = samples.peekLast();
		return last == null ? null : last.position;
	}

	/**
	 * @param shownPosition where it is shown right now, what it moves on from after standing still
	 */
	public void add(Vec3 position, Quaternionfc rotation, Vec3 shownPosition, Quaternionfc shownRotation) {
		final long now = System.nanoTime();
		Sample last = samples.peekLast();
		if (restAfter > 0 && (last == null || now - last.nanos > restAfter)) {
			last = new Sample(now - restStep, last == null ? shownPosition : last.position,
					new Quaternionf(last == null ? shownRotation : last.rotation));
			samples.addLast(last);
		}
		final long nanos = last == null ? now : Math.min(Math.max(now, last.nanos + minSpacing), now + maxAhead);
		samples.addLast(new Sample(nanos, position, new Quaternionf(rotation)));
		if (samples.size() > CAPACITY) {
			samples.removeFirst();
		}
	}

	/**
	 * @param rotation set to how it is turned, if a place is returned
	 * @return where to show it now, null to leave it where it is
	 */
	@Nullable
	public Vec3 follow(Quaternionf rotation) {
		final long shown = System.nanoTime() - delay;
		while (samples.size() > 2 && second().nanos <= shown) {
			samples.removeFirst();
		}
		final Sample from = samples.peekFirst();
		if (from == null || shown <= from.nanos) {
			return null;
		}
		final Sample to = samples.size() > 1 ? second() : from;
		final float along = to.nanos <= from.nanos ? 1.0F :
				(float) Math.min(1.0, (shown - from.nanos) / (double) (to.nanos - from.nanos));
		from.rotation.slerp(to.rotation, along, rotation);
		return from.position.lerp(to.position, along);
	}

	private Sample second() {
		final Iterator<Sample> all = samples.iterator();
		all.next();
		return all.next();
	}

	private record Sample(long nanos, Vec3 position, Quaternionf rotation) {
	}
}
