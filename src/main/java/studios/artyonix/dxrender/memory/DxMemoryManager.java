package studios.artyonix.dxrender.memory;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.system.MemoryUtil;

/**
 * DX Render Memory Manager: delays every release until two frames have been submitted and
 * provides the host memory allocator used by the CPU backend.
 *
 * Both backends keep referring to buffers and textures after the game has closed them: the
 * rasterizer holds raw pointers until the end of a pass, the GPU executes recorded commands
 * a frame later. Two frames is what an asynchronous device needs.
 */
public final class DxMemoryManager {
	private static final int DELAY_FRAMES = 2;

	private record Pending(Runnable release, long frame) {
	}

	/** Plain host memory, 64 byte aligned. */
	public static final class HostAllocator implements DxMemoryAllocator {
		private final AtomicLong used = new AtomicLong();

		@Override
		public Allocation allocateBuffer(final long size, final int usage) {
			long address = MemoryUtil.nmemAlignedAlloc(64, Math.max(64, (size + 63) & ~63L));
			if (address == 0L) {
				throw new OutOfMemoryError("DX Render could not allocate " + size + " bytes");
			}
			Allocation a = new Allocation();
			a.address = address;
			a.size = size;
			this.used.addAndGet(size);
			return a;
		}

		@Override
		public void freeBuffer(final Allocation allocation) {
			MemoryUtil.nmemAlignedFree(allocation.address);
			this.used.addAndGet(-allocation.size);
		}

		@Override
		public long usedBytes() {
			return this.used.get();
		}

		@Override
		public long reservedBytes() {
			return this.used.get();
		}
	}

	private final ArrayDeque<Pending> pending = new ArrayDeque<>();
	private final DxMemoryAllocator allocator;
	private long frame;

	public DxMemoryManager(final DxMemoryAllocator allocator) {
		this.allocator = allocator;
	}

	public DxMemoryAllocator allocator() {
		return this.allocator;
	}

	public DxMemoryAllocator.Allocation allocate(final long size, final int usage) {
		return this.allocator.allocateBuffer(size, usage);
	}

	public void free(final DxMemoryAllocator.Allocation allocation) {
		this.defer(() -> this.allocator.freeBuffer(allocation));
	}

	public synchronized void defer(final Runnable release) {
		this.pending.addLast(new Pending(release, this.frame));
	}

	/** Called once per submitted frame, after the driver has submitted it. */
	public void endFrame() {
		Runnable[] due;
		synchronized (this) {
			this.frame++;
			int n = 0;
			for (Pending p : this.pending) {
				if (p.frame + DELAY_FRAMES > this.frame) {
					break;
				}
				n++;
			}
			due = new Runnable[n];
			for (int i = 0; i < n; i++) {
				due[i] = this.pending.pollFirst().release;
			}
		}
		for (Runnable r : due) {
			r.run();
		}
	}

	public void releaseAll() {
		for (;;) {
			Pending p;
			synchronized (this) {
				p = this.pending.pollFirst();
			}
			if (p == null) {
				return;
			}
			p.release.run();
		}
	}
}
