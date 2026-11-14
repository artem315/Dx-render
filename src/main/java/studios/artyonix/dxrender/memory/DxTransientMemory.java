package studios.artyonix.dxrender.memory;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.device.DxBuffer;

/**
 * Per frame scratch memory. Blocks are pooled: a block used in frame N becomes reusable in
 * frame N+2, so the steady state performs no allocation at all.
 */
public final class DxTransientMemory implements TransientMemory {
	private static final long BLOCK_SIZE = 1L << 20;
	private static final int ALL_USAGES = 0x3FF;

	private final DxMemoryManager memory;
	private final ArrayList<DxBuffer> free = new ArrayList<>();
	private final ArrayList<DxBuffer> current = new ArrayList<>();
	private final ArrayList<DxBuffer> previous = new ArrayList<>();
	private final ArrayList<DxBuffer> currentLarge = new ArrayList<>();
	private final ArrayList<DxBuffer> previousLarge = new ArrayList<>();
	private DxBuffer block;
	private long offset;

	public DxTransientMemory(final DxMemoryManager memory) {
		this.memory = memory;
	}

	private GpuBufferSlice allocate(final long size, final long alignment) {
		long align = Math.max(1L, alignment);
		if (size > BLOCK_SIZE) {
			DxBuffer large = new DxBuffer(this.memory, ALL_USAGES, size);
			this.currentLarge.add(large);
			return large.slice(0L, size);
		}
		long start = this.block == null ? 0L : (this.offset + align - 1) / align * align;
		if (this.block == null || start + size > BLOCK_SIZE) {
			this.block = this.free.isEmpty() ? new DxBuffer(this.memory, ALL_USAGES, BLOCK_SIZE) : this.free.remove(this.free.size() - 1);
			this.current.add(this.block);
			start = 0L;
		}
		this.offset = start + size;
		return this.block.slice(start, size);
	}

	/** Frame boundary. */
	public void rotate() {
		this.free.addAll(this.previous);
		this.previous.clear();
		this.previous.addAll(this.current);
		this.current.clear();
		for (DxBuffer large : this.previousLarge) {
			large.close();
		}
		this.previousLarge.clear();
		this.previousLarge.addAll(this.currentLarge);
		this.currentLarge.clear();
		this.block = null;
		this.offset = 0L;
		// keep the pool bounded after a spike
		while (this.free.size() > 64) {
			this.free.remove(this.free.size() - 1).close();
		}
	}

	public void close() {
		for (List<DxBuffer> list : List.of(this.free, this.current, this.previous, this.currentLarge, this.previousLarge)) {
			for (DxBuffer b : list) {
				b.close();
			}
			list.clear();
		}
		this.block = null;
	}

	private static long address(final GpuBufferSlice slice) {
		return ((DxBuffer)slice.buffer()).address() + slice.offset();
	}

	private static GpuBufferSlice.MappedView view(final GpuBufferSlice slice) {
		return new GpuBufferSlice.MappedView(slice, MemoryUtil.memByteBuffer(address(slice), (int)slice.length()), () -> {
		});
	}

	@Override
	public ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
		GpuBufferSlice slice = this.allocate(size, alignment);
		return MemoryUtil.memByteBuffer(address(slice), (int)size);
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(final long size, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
		return view(this.allocate(size, alignment));
	}

	@Override
	public GpuBufferSlice allocateGpu(final long size, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
		return this.allocate(size, alignment);
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(final long size, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
		return view(this.allocate(size, alignment));
	}

	private GpuBufferSlice upload(final List<ByteBuffer> data, final long alignment) {
		long total = 0L;
		for (ByteBuffer b : data) {
			total += b.remaining();
		}
		GpuBufferSlice slice = this.allocate(Math.max(1L, total), alignment);
		long dst = address(slice);
		for (ByteBuffer b : data) {
			int n = b.remaining();
			if (n > 0) {
				MemoryUtil.memCopy(MemoryUtil.memAddress(b), dst, n);
				dst += n;
			}
		}
		return slice.buffer().slice(slice.offset(), total);
	}

	@Override
	public GpuBufferSlice uploadStaging(final List<ByteBuffer> data, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
		return this.upload(data, alignment);
	}

	@Override
	public GpuBufferSlice uploadGpu(final List<ByteBuffer> data, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
		return this.upload(data, alignment);
	}

	private List<GpuBufferSlice> multiUpload(final List<ByteBuffer> data, final long alignment) {
		List<GpuBufferSlice> out = new ArrayList<>(data.size());
		for (ByteBuffer b : data) {
			out.add(this.upload(List.of(b), alignment));
		}
		return out;
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(final List<ByteBuffer> data, final long alignment, final int usage) {
		return this.multiUpload(data, alignment);
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(final List<ByteBuffer> data, final long alignment, final int usage) {
		return this.multiUpload(data, alignment);
	}
}
