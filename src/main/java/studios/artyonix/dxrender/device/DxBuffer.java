package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.memory.DxMemoryAllocator;
import studios.artyonix.dxrender.memory.DxMemoryManager;

/**
 * DX Render buffer. Its memory is always addressable by the CPU (host memory for the CPU
 * backend, persistently mapped device memory for the GPU backend), so "mapping" is free.
 */
public final class DxBuffer extends BaseGpuBuffer {
	private final DxMemoryManager memory;
	private final DxMemoryAllocator.Allocation allocation;
	private boolean closed;

	public DxBuffer(final DxMemoryManager memory, final int usage, final long size) {
		super(usage, size);
		this.memory = memory;
		this.allocation = memory.allocate(size, usage);
		MemoryUtil.memSet(this.allocation.address, 0, size);
	}

	public long address() {
		return this.allocation.address;
	}

	/** Driver object behind the buffer (0 for the CPU backend). */
	public long handle() {
		return this.allocation.handle;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.memory.free(this.allocation);
		}
	}

	@Override
	public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
		if (this.closed) {
			throw new IllegalStateException("Buffer already closed");
		}
		if (offset < 0L || length < 0L || offset + length > this.size()) {
			throw new IllegalArgumentException("Cannot map " + length + " bytes at offset " + offset + " of a " + this.size() + " byte buffer");
		}
		if (length > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Mapping buffers larger than 2GB is not supported");
		}
		return new GpuBufferSlice.MappedView(this.slice(offset, length), MemoryUtil.memByteBuffer(this.allocation.address + offset, (int)length), () -> {
		});
	}
}
