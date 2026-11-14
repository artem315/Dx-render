package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.memory.DxMemoryManager;

/** A buffer is plain host memory; "mapping" it is free. */
public final class DxBuffer extends BaseGpuBuffer {
	private final DxMemoryManager memory;
	private final long address;
	private boolean closed;

	public DxBuffer(final DxMemoryManager memory, final int usage, final long size) {
		super(usage, size);
		this.memory = memory;
		this.address = memory.allocate(size);
		MemoryUtil.memSet(this.address, 0, size);
	}

	public long address() {
		return this.address;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.memory.free(this.address, this.size());
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
		return new GpuBufferSlice.MappedView(this.slice(offset, length), MemoryUtil.memByteBuffer(this.address + offset, (int)length), () -> {
		});
	}
}
