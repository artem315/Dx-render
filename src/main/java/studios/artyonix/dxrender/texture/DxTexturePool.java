package studios.artyonix.dxrender.texture;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import studios.artyonix.dxrender.core.DxNative;

/**
 * DX Render Texture System, cache part: released native textures are kept for a while and
 * handed out again for requests of the same shape. Render targets are megabytes of memory;
 * recycling them avoids page faulting fresh allocations every time the game recreates one.
 */
public final class DxTexturePool {
	private record Key(int ctype, int ncomp, int width, int height, int layers, int mips, boolean cube) {
	}

	private record Entry(long handle, long bytes, long frame) {
	}

	private static final long MAX_BYTES = 256L << 20;
	private static final int MAX_AGE_FRAMES = 600;

	private final long device;
	private final Map<Key, ArrayDeque<Entry>> pool = new HashMap<>();
	private long pooledBytes;
	private long frame;
	private long hits, misses;
	private boolean enabled = true;

	public DxTexturePool(final long device) {
		this.device = device;
	}

	public void setEnabled(final boolean enabled) {
		this.enabled = enabled;
		if (!enabled) {
			this.clear();
		}
	}

	private static long estimate(final Key k) {
		long texel = switch (k.ctype) {
			case DxNative.CT_UNORM8, DxNative.CT_SNORM8, DxNative.CT_UINT8, DxNative.CT_SINT8 -> 1L;
			case DxNative.CT_UNORM16, DxNative.CT_SNORM16, DxNative.CT_UINT16, DxNative.CT_SINT16, DxNative.CT_FLOAT16 -> 2L;
			default -> 4L;
		} * k.ncomp;
		long total = 0L;
		for (int m = 0; m < k.mips; m++) {
			total += (long)Math.max(1, k.width >> m) * Math.max(1, k.height >> m) * texel;
		}
		return total * k.layers;
	}

	public synchronized long acquire(final int ctype, final int ncomp, final int width, final int height, final int layers, final int mips, final boolean cube) {
		Key key = new Key(ctype, ncomp, width, height, layers, mips, cube);
		ArrayDeque<Entry> list = this.enabled ? this.pool.get(key) : null;
		if (list != null && !list.isEmpty()) {
			Entry e = list.pollLast();
			this.pooledBytes -= e.bytes;
			this.hits++;
			// A recycled texture must look freshly created.
			for (int m = 0; m < mips; m++) {
				DxNative.nTextureClearColor(e.handle, m, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, Math.max(1, width >> m), Math.max(1, height >> m));
			}
			return e.handle;
		}
		this.misses++;
		return DxNative.nTextureCreate(this.device, ctype, ncomp, width, height, layers, mips, cube);
	}

	public synchronized void release(final long handle, final int ctype, final int ncomp, final int width, final int height, final int layers, final int mips,
		final boolean cube) {
		Key key = new Key(ctype, ncomp, width, height, layers, mips, cube);
		long bytes = estimate(key);
		if (!this.enabled || bytes > MAX_BYTES / 4 || this.pooledBytes + bytes > MAX_BYTES) {
			DxNative.nTextureDestroy(this.device, handle);
			return;
		}
		this.pool.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(new Entry(handle, bytes, this.frame));
		this.pooledBytes += bytes;
	}

	/** Drops entries that were not reused for a while. */
	public synchronized void endFrame() {
		this.frame++;
		if ((this.frame & 63) != 0) {
			return;
		}
		for (Iterator<ArrayDeque<Entry>> it = this.pool.values().iterator(); it.hasNext();) {
			ArrayDeque<Entry> list = it.next();
			while (!list.isEmpty() && this.frame - list.peekFirst().frame > MAX_AGE_FRAMES) {
				Entry e = list.pollFirst();
				this.pooledBytes -= e.bytes;
				DxNative.nTextureDestroy(this.device, e.handle);
			}
			if (list.isEmpty()) {
				it.remove();
			}
		}
	}

	public synchronized void clear() {
		for (ArrayDeque<Entry> list : this.pool.values()) {
			for (Entry e : list) {
				DxNative.nTextureDestroy(this.device, e.handle);
			}
		}
		this.pool.clear();
		this.pooledBytes = 0L;
	}

	public synchronized long pooledBytes() {
		return this.pooledBytes;
	}

	public synchronized long hits() {
		return this.hits;
	}

	public synchronized long misses() {
		return this.misses;
	}
}
