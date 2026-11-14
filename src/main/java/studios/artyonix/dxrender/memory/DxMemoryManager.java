package studios.artyonix.dxrender.memory;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.system.MemoryUtil;

/**
 * DX Render Memory Manager (Java side): owns the off-heap memory behind buffers and delays
 * every release until two frames have been submitted. The rasterizer keeps raw pointers to
 * vertex, index and uniform memory until the end of a pass, so freeing immediately would be
 * unsafe; two frames matches what the game already expects from an asynchronous GPU.
 */
public final class DxMemoryManager {
	private static final int DELAY_FRAMES = 2;

	private record Pending(Runnable release, long frame) {
	}

	private final ArrayDeque<Pending> pending = new ArrayDeque<>();
	private final AtomicLong liveBytes = new AtomicLong();
	private final AtomicLong liveBlocks = new AtomicLong();
	private long frame;

	public long allocate(final long size) {
		long address = MemoryUtil.nmemAlignedAlloc(64, Math.max(64, (size + 63) & ~63L));
		if (address == 0L) {
			throw new OutOfMemoryError("DX Render could not allocate " + size + " bytes");
		}
		liveBytes.addAndGet(size);
		liveBlocks.incrementAndGet();
		return address;
	}

	public void free(final long address, final long size) {
		defer(() -> {
			MemoryUtil.nmemAlignedFree(address);
			liveBytes.addAndGet(-size);
			liveBlocks.decrementAndGet();
		});
	}

	public synchronized void defer(final Runnable release) {
		pending.addLast(new Pending(release, frame));
	}

	/** Called once per submitted frame. */
	public synchronized void endFrame() {
		frame++;
		while (!pending.isEmpty() && pending.peekFirst().frame + DELAY_FRAMES <= frame) {
			pending.pollFirst().release.run();
		}
	}

	public synchronized void releaseAll() {
		while (!pending.isEmpty()) {
			pending.pollFirst().release.run();
		}
	}

	public long liveBytes() {
		return liveBytes.get();
	}

	public long liveBlocks() {
		return liveBlocks.get();
	}
}
