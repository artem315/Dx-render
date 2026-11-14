package studios.artyonix.dxrender.command;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.core.DxNative;
import studios.artyonix.dxrender.device.DxBuffer;
import studios.artyonix.dxrender.device.DxDevice;
import studios.artyonix.dxrender.device.DxQueryPool;
import studios.artyonix.dxrender.device.DxSampler;
import studios.artyonix.dxrender.device.DxTextureView;
import studios.artyonix.dxrender.shader.DxPipeline;

/** Records the state of one render pass into the native draw block and issues draws. */
public final class DxRenderPass implements RenderPassBackend {
	private static final int PUSH_CAPACITY = 256;

	private final DxDevice device;
	private final long block;
	private final long pushConstants;
	private @Nullable DxPipeline pipeline;
	private long indexAddress, indexSize;
	private int indexBytes;
	private boolean active;

	DxRenderPass(final DxDevice device, final long drawBlock) {
		this.device = device;
		this.block = drawBlock;
		this.pushConstants = MemoryUtil.nmemCalloc(PUSH_CAPACITY, 1);
	}

	void close() {
		MemoryUtil.nmemFree(this.pushConstants);
	}

	void begin(final boolean active) {
		MemoryUtil.memSet(this.block, 0, DxNative.DS_SLOTS * 8L);
		this.pipeline = null;
		this.indexAddress = 0L;
		this.indexSize = 0L;
		this.indexBytes = 0;
		this.active = active;
	}

	private void put(final int slot, final long value) {
		MemoryUtil.memPutLong(this.block + 8L * slot, value);
	}

	@Override
	public void pushDebugGroup(final Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(final BackendRenderPipeline pipeline) {
		if (!(pipeline instanceof DxPipeline dx)) {
			throw new IllegalArgumentException("Pipeline must be a DX Render pipeline");
		}
		if (this.pipeline != dx) {
			// bindings are per pipeline: start from an empty set
			MemoryUtil.memSet(this.block + 8L * DxNative.DS_UNIFORMS, 0, 8L * DxNative.DS_UNIFORM_STRIDE * DxNative.MAX_UNIFORMS);
		}
		this.pipeline = dx;
		this.put(DxNative.DS_PIPELINE, dx.handle());
	}

	@Override
	public void setUniform(final int index, final @Nullable Object value) {
		DxPipeline p = this.pipeline;
		if (p == null || index < 0 || index >= p.uniformCount()) {
			return;
		}
		int base = DxNative.DS_UNIFORMS + index * DxNative.DS_UNIFORM_STRIDE;
		if (value instanceof GpuBufferSlice(GpuBuffer buffer, long offset, long length)) {
			int kind = p.uniformKind(index) == DxNative.UNIFORM_TEXEL_BUFFER ? DxNative.UNIFORM_TEXEL_BUFFER : DxNative.UNIFORM_BUFFER;
			this.put(base, kind);
			this.put(base + 1, ((DxBuffer)buffer).address() + offset);
			this.put(base + 2, length);
			this.put(base + 3, p.uniformFormatBits(index));
		} else if (value instanceof TextureViewAndSampler(var view, var sampler)) {
			DxTextureView v = (DxTextureView)view;
			DxSampler s = (DxSampler)sampler;
			long bits = DxNative.UNIFORM_SAMPLER | s.stateBits() | (long)(v.baseMipLevel() & 0xFF) << 40 | (long)(Math.min(255, v.mipLevels()) & 0xFF) << 48;
			this.put(base, bits);
			this.put(base + 1, v.dxTexture().handle());
			this.put(base + 2, 0L);
			this.put(base + 3, Float.floatToRawIntBits(s.maxLodValue()) & 0xFFFFFFFFL);
		} else {
			this.put(base, DxNative.UNIFORM_NONE);
			this.put(base + 1, 0L);
			this.put(base + 2, 0L);
			this.put(base + 3, 0L);
		}
	}

	@Override
	public void pushConstants(final ByteBuffer value) {
		int n = Math.min(PUSH_CAPACITY, value.remaining());
		MemoryUtil.memSet(this.pushConstants, 0, PUSH_CAPACITY);
		MemoryUtil.memCopy(MemoryUtil.memAddress(value), this.pushConstants, n);
		this.put(DxNative.DS_PUSH, this.pushConstants);
		this.put(DxNative.DS_PUSH_SIZE, n);
	}

	@Override
	public void enableScissor(final int x, final int y, final int width, final int height) {
		this.put(DxNative.DS_SCISSOR_ON, 1L);
		this.put(DxNative.DS_SCISSOR, x);
		this.put(DxNative.DS_SCISSOR + 1, y);
		this.put(DxNative.DS_SCISSOR + 2, width);
		this.put(DxNative.DS_SCISSOR + 3, height);
	}

	@Override
	public void disableScissor() {
		this.put(DxNative.DS_SCISSOR_ON, 0L);
	}

	@Override
	public void setVertexBuffer(final int slot, final @Nullable GpuBufferSlice vertexBuffer) {
		if (slot < 0 || slot >= DxNative.MAX_VERTEX_BUFFERS) {
			return;
		}
		if (vertexBuffer == null) {
			this.put(DxNative.DS_VB + slot, 0L);
			this.put(DxNative.DS_VB_SIZE + slot, 0L);
		} else {
			DxBuffer b = (DxBuffer)vertexBuffer.buffer();
			this.put(DxNative.DS_VB + slot, b.address() + vertexBuffer.offset());
			this.put(DxNative.DS_VB_SIZE + slot, b.size() - vertexBuffer.offset());
		}
	}

	@Override
	public void setIndexBuffer(final @Nullable GpuBuffer indexBuffer, final IndexType indexType) {
		if (indexBuffer == null) {
			this.indexAddress = 0L;
			this.indexSize = 0L;
		} else {
			this.indexAddress = ((DxBuffer)indexBuffer).address();
			this.indexSize = indexBuffer.size();
		}
		this.indexBytes = indexType.bytes;
	}

	private void bindIndices(final boolean indexed) {
		this.put(DxNative.DS_INDEX, indexed ? this.indexAddress : 0L);
		this.put(DxNative.DS_INDEX_SIZE, indexed ? this.indexSize : 0L);
		this.put(DxNative.DS_INDEX_BYTES, indexed ? this.indexBytes : 0);
	}

	private void issue(final boolean indexed, final int first, final int count, final int baseVertex, final int instanceCount, final int firstInstance) {
		if (!this.active || this.pipeline == null || count <= 0) {
			return;
		}
		if (indexed && this.indexAddress == 0L) {
			return;
		}
		this.bindIndices(indexed);
		this.put(DxNative.DS_FIRST, first);
		this.put(DxNative.DS_COUNT, count);
		this.put(DxNative.DS_BASE_VERTEX, baseVertex);
		this.put(DxNative.DS_INSTANCES, Math.max(1, instanceCount));
		this.put(DxNative.DS_FIRST_INSTANCE, firstInstance);
		DxNative.nDraw(this.device.handle(), this.block);
	}

	@Override
	public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
		this.issue(true, firstIndex, indexCount, vertexOffset, instanceCount, firstInstance);
	}

	@Override
	public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		this.issue(false, firstVertex, vertexCount, 0, instanceCount, firstInstance);
	}

	@Override
	public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		int p = drawParameters.position();
		for (int i = 0; i < drawCount; i++) {
			// (indexCount, firstIndex, vertexOffset) triples
			this.issue(true, drawParameters.get(p + 3 * i + 1), drawParameters.get(p + 3 * i), drawParameters.get(p + 3 * i + 2), instanceCount, firstInstance);
		}
	}

	@Override
	public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
		int bytes = Math.max(1, this.indexBytes);
		for (int i = 0; i < drawCount; i++) {
			long byteOffset = firstIndexOffsets.get(firstIndexOffsets.position() + i);
			this.issue(true, (int)(byteOffset / bytes), indexCounts.get(indexCounts.position() + i), vertexOffsets.get(vertexOffsets.position() + i), 1, 0);
		}
	}

	@Override
	public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
		// VkDrawIndexedIndirectCommand: indexCount, instanceCount, firstIndex, vertexOffset, firstInstance
		long addr = ((DxBuffer)commands.buffer()).address() + commands.offset();
		for (int i = 0; i < drawCount; i++) {
			long c = addr + 20L * i;
			this.issue(true, MemoryUtil.memGetInt(c + 8), MemoryUtil.memGetInt(c), MemoryUtil.memGetInt(c + 12), MemoryUtil.memGetInt(c + 4), MemoryUtil.memGetInt(c + 16));
		}
	}

	@Override
	public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		int p = drawParameters.position();
		for (int i = 0; i < drawCount; i++) {
			this.issue(false, drawParameters.get(p + 2 * i + 1), drawParameters.get(p + 2 * i), 0, instanceCount, firstInstance);
		}
	}

	@Override
	public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
		for (int i = 0; i < drawCount; i++) {
			this.issue(false, firstVertices.get(firstVertices.position() + i), vertexCounts.get(vertexCounts.position() + i), 0, 1, 0);
		}
	}

	@Override
	public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
		// VkDrawIndirectCommand: vertexCount, instanceCount, firstVertex, firstInstance
		long addr = ((DxBuffer)commands.buffer()).address() + commands.offset();
		for (int i = 0; i < drawCount; i++) {
			long c = addr + 16L * i;
			this.issue(false, MemoryUtil.memGetInt(c + 8), MemoryUtil.memGetInt(c), 0, MemoryUtil.memGetInt(c + 4), MemoryUtil.memGetInt(c + 12));
		}
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		((DxQueryPool)pool).writeTimestamp(index);
	}
}
