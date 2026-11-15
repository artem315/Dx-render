package studios.artyonix.dxrender.memory;

/**
 * DX Render memory allocator interface. Buffers are always CPU addressable (persistently
 * mapped), so the API above works on plain addresses whether the memory lives on the host
 * or in device memory.
 */
public interface DxMemoryAllocator {
	/** One buffer allocation. handle is driver specific (0 for host memory, a VkBuffer for Vulkan). */
	final class Allocation {
		public long address;
		public long size;
		public long handle;
		public Object block;
		public long blockOffset;
	}

	Allocation allocateBuffer(long size, int usage);

	void freeBuffer(Allocation allocation);

	/** Bytes handed out to live allocations. */
	long usedBytes();

	/** Bytes reserved from the system or the device, including unused space in blocks. */
	long reservedBytes();
}
