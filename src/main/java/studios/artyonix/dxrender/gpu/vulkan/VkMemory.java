package studios.artyonix.dxrender.gpu.vulkan;

import static org.lwjgl.vulkan.VK10.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import studios.artyonix.dxrender.memory.DxMemoryAllocator;

/**
 * GPU memory management of the Vulkan driver. Device memory is taken from the driver in
 * large blocks and sub-allocated, because drivers limit the number of allocations (often to
 * 4096) and every allocation is a kernel call. Buffer blocks are host visible and stay
 * mapped for their whole life; image blocks prefer device local memory.
 */
final class VkMemory implements DxMemoryAllocator {
	private static final long BUFFER_BLOCK = 32L << 20;
	private static final long IMAGE_BLOCK = 64L << 20;
	private static final int BUFFER_USAGE = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
		| VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
		| VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;

	static final class Block {
		long memory, size, mapped, used;
		int type;
		boolean dedicated, hostVisible;
		final ArrayList<long[]> free = new ArrayList<>(); // {offset, size}, sorted by offset
	}

	/** A range inside a block. */
	static final class Region {
		Block block;
		long offset, size;
	}

	private final VkDevice device;
	private final int typeCount;
	private final int[] typeFlags;
	private final ArrayList<Block> blocks = new ArrayList<>();
	private long reserved, used;

	VkMemory(final VkPhysicalDevice physicalDevice, final VkDevice device) {
		this.device = device;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
			vkGetPhysicalDeviceMemoryProperties(physicalDevice, props);
			this.typeCount = props.memoryTypeCount();
			this.typeFlags = new int[this.typeCount];
			for (int i = 0; i < this.typeCount; i++) {
				this.typeFlags[i] = props.memoryTypes(i).propertyFlags();
			}
		}
	}

	/** Picks the best memory type among those the resource accepts, or -1. */
	private int chooseType(final int allowedBits, final boolean hostVisible) {
		int best = -1, bestScore = -1;
		for (int i = 0; i < this.typeCount; i++) {
			if ((allowedBits & 1 << i) == 0) {
				continue;
			}
			int f = this.typeFlags[i];
			if ((f & (VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT | 0x20 /* PROTECTED */)) != 0) {
				continue;
			}
			int score;
			if (hostVisible) {
				if ((f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0 || (f & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
					continue;
				}
				score = ((f & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) != 0 ? 2 : 0) + ((f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0 ? 1 : 0);
			} else {
				score = ((f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0 ? 2 : 0) + ((f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0 ? 1 : 0);
			}
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	private Block newBlock(final int type, final long size, final boolean dedicated, final boolean hostVisible) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkMemoryAllocateInfo info = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(size).memoryTypeIndex(type);
			LongBuffer pMemory = stack.mallocLong(1);
			int rc = vkAllocateMemory(this.device, info, null, pMemory);
			if (rc != VK_SUCCESS) {
				return null;
			}
			Block b = new Block();
			b.memory = pMemory.get(0);
			b.size = size;
			b.type = type;
			b.dedicated = dedicated;
			b.hostVisible = hostVisible;
			if (hostVisible) {
				PointerBuffer pp = stack.mallocPointer(1);
				rc = vkMapMemory(this.device, b.memory, 0L, VK_WHOLE_SIZE, 0, pp);
				if (rc != VK_SUCCESS) {
					vkFreeMemory(this.device, b.memory, null);
					return null;
				}
				b.mapped = pp.get(0);
			}
			b.free.add(new long[]{0L, size});
			this.blocks.add(b);
			this.reserved += size;
			return b;
		}
	}

	private static long take(final Block b, final long size, final long alignment) {
		for (int i = 0; i < b.free.size(); i++) {
			long[] r = b.free.get(i);
			long start = (r[0] + alignment - 1) / alignment * alignment;
			long end = start + size;
			if (end > r[0] + r[1]) {
				continue;
			}
			long tail = r[0] + r[1] - end;
			long head = start - r[0];
			b.free.remove(i);
			if (tail > 0) {
				b.free.add(i, new long[]{end, tail});
			}
			if (head > 0) {
				b.free.add(i, new long[]{r[0], head});
			}
			return start;
		}
		return -1L;
	}

	private static void give(final Block b, final long offset, final long size) {
		int i = 0;
		while (i < b.free.size() && b.free.get(i)[0] < offset) {
			i++;
		}
		b.free.add(i, new long[]{offset, size});
		if (i + 1 < b.free.size() && b.free.get(i)[0] + b.free.get(i)[1] == b.free.get(i + 1)[0]) {
			b.free.get(i)[1] += b.free.get(i + 1)[1];
			b.free.remove(i + 1);
		}
		if (i > 0 && b.free.get(i - 1)[0] + b.free.get(i - 1)[1] == b.free.get(i)[0]) {
			b.free.get(i - 1)[1] += b.free.get(i)[1];
			b.free.remove(i);
		}
	}

	synchronized Region allocate(final long size, final long alignment, final int allowedBits, final boolean hostVisible) {
		int type = this.chooseType(allowedBits, hostVisible);
		if (type < 0) {
			throw new IllegalStateException("no suitable Vulkan memory type (bits 0x" + Integer.toHexString(allowedBits) + ", hostVisible=" + hostVisible + ")");
		}
		long blockSize = hostVisible ? BUFFER_BLOCK : IMAGE_BLOCK;
		Block block = null;
		long offset = -1L;
		if (size <= blockSize / 2) {
			for (Block b : this.blocks) {
				if (b.type == type && !b.dedicated && b.hostVisible == hostVisible) {
					offset = take(b, size, alignment);
					if (offset >= 0) {
						block = b;
						break;
					}
				}
			}
			if (block == null) {
				block = this.newBlock(type, blockSize, false, hostVisible);
				if (block != null) {
					offset = take(block, size, alignment);
				}
			}
		}
		if (block == null || offset < 0) {
			// large resource, or the device refused a full block: give it memory of its own
			block = this.newBlock(type, size, true, hostVisible);
			if (block == null) {
				throw new OutOfMemoryError("Vulkan device memory exhausted allocating " + size + " bytes (" + (this.reserved >> 20) + " MiB reserved)");
			}
			offset = take(block, size, 1L);
		}
		block.used += size;
		this.used += size;
		Region r = new Region();
		r.block = block;
		r.offset = offset;
		r.size = size;
		return r;
	}

	synchronized void release(final Region r) {
		Block b = r.block;
		b.used -= r.size;
		this.used -= r.size;
		give(b, r.offset, r.size);
		if (b.used == 0 && (b.dedicated || this.countEmpty(b.type, b.hostVisible) > 1)) {
			this.blocks.remove(b);
			this.reserved -= b.size;
			vkFreeMemory(this.device, b.memory, null); // unmaps implicitly
		}
	}

	private int countEmpty(final int type, final boolean hostVisible) {
		int n = 0;
		for (Block b : this.blocks) {
			if (b.type == type && b.hostVisible == hostVisible && !b.dedicated && b.used == 0) {
				n++;
			}
		}
		return n;
	}

	@Override
	public Allocation allocateBuffer(final long size, final int usage) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(Math.max(1L, size)).usage(BUFFER_USAGE).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer pBuffer = stack.mallocLong(1);
			VkFormats.check(vkCreateBuffer(this.device, info, null, pBuffer), "vkCreateBuffer");
			long buffer = pBuffer.get(0);
			VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
			vkGetBufferMemoryRequirements(this.device, buffer, req);
			Region region;
			try {
				region = this.allocate(req.size(), Math.max(1L, req.alignment()), req.memoryTypeBits(), true);
			} catch (RuntimeException | Error e) {
				vkDestroyBuffer(this.device, buffer, null);
				throw e;
			}
			VkFormats.check(vkBindBufferMemory(this.device, buffer, region.block.memory, region.offset), "vkBindBufferMemory");
			Allocation a = new Allocation();
			a.address = region.block.mapped + region.offset;
			a.size = size;
			a.handle = buffer;
			a.block = region;
			a.blockOffset = region.offset;
			return a;
		}
	}

	@Override
	public void freeBuffer(final Allocation allocation) {
		vkDestroyBuffer(this.device, allocation.handle, null);
		this.release((Region)allocation.block);
	}

	@Override
	public synchronized long usedBytes() {
		return this.used;
	}

	@Override
	public synchronized long reservedBytes() {
		return this.reserved;
	}

	synchronized int blockCount() {
		return this.blocks.size();
	}

	synchronized void destroy() {
		for (Block b : this.blocks) {
			vkFreeMemory(this.device, b.memory, null);
		}
		this.blocks.clear();
		this.reserved = 0L;
		this.used = 0L;
	}
}
