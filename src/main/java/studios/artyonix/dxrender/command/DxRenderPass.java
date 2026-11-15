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
import studios.artyonix.dxrender.device.DxBuffer;
import studios.artyonix.dxrender.device.DxQueryPool;
import studios.artyonix.dxrender.device.DxSampler;
import studios.artyonix.dxrender.device.DxTextureView;
import studios.artyonix.dxrender.shader.DxPipeline;

/** DX Render render pass: records state changes and draws of one pass into the command buffer. */
public final class DxRenderPass implements RenderPassBackend {
	private final DxCommandQueue queue;
	private int indexBytes = 4;

	DxRenderPass(final DxCommandQueue queue) {
		this.queue = queue;
	}

	private DxCommandBuffer cb() {
		return this.queue.buffer();
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
		this.cb().setPipeline(dx);
		this.queue.commit();
	}

	@Override
	public void setUniform(final int index, final @Nullable Object value) {
		if (index < 0 || index >= DxDescriptorSet.MAX_BINDINGS) {
			return;
		}
		if (value instanceof GpuBufferSlice(GpuBuffer buffer, long offset, long length)) {
			this.cb().setUniformBuffer(index, (DxBuffer)buffer, offset, length);
		} else if (value instanceof TextureViewAndSampler(var view, var sampler)) {
			this.cb().setUniformTexture(index, (DxTextureView)view, (DxSampler)sampler);
		} else {
			this.cb().setUniformNone(index);
		}
		this.queue.commit();
	}

	@Override
	public void pushConstants(final ByteBuffer value) {
		this.cb().pushConstants(MemoryUtil.memAddress(value), Math.min(256, value.remaining()));
		this.queue.commit();
	}

	@Override
	public void enableScissor(final int x, final int y, final int width, final int height) {
		this.cb().setScissor(true, x, y, width, height);
		this.queue.commit();
	}

	@Override
	public void disableScissor() {
		this.cb().setScissor(false, 0, 0, 0, 0);
		this.queue.commit();
	}

	@Override
	public void setVertexBuffer(final int slot, final @Nullable GpuBufferSlice vertexBuffer) {
		if (vertexBuffer == null) {
			this.cb().setVertexBuffer(slot, null, 0L);
		} else {
			this.cb().setVertexBuffer(slot, (DxBuffer)vertexBuffer.buffer(), vertexBuffer.offset());
		}
		this.queue.commit();
	}

	@Override
	public void setIndexBuffer(final @Nullable GpuBuffer indexBuffer, final IndexType indexType) {
		this.indexBytes = indexType.bytes;
		this.cb().setIndexBuffer((DxBuffer)indexBuffer, indexType.bytes);
		this.queue.commit();
	}

	@Override
	public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
		if (indexCount > 0) {
			this.cb().drawIndexed(firstIndex, indexCount, vertexOffset, instanceCount, firstInstance);
			this.queue.commit();
		}
	}

	@Override
	public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		if (vertexCount > 0) {
			this.cb().draw(firstVertex, vertexCount, instanceCount, firstInstance);
			this.queue.commit();
		}
	}

	@Override
	public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		throw new UnsupportedOperationException("DX Render does not offer interleaved multi draw");
	}

	@Override
	public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
		int bytes = Math.max(1, this.indexBytes);
		for (int i = 0; i < drawCount; i++) {
			long byteOffset = firstIndexOffsets.get(firstIndexOffsets.position() + i);
			this.drawIndexed(indexCounts.get(indexCounts.position() + i), 1, (int)(byteOffset / bytes), vertexOffsets.get(vertexOffsets.position() + i), 0);
		}
	}

	@Override
	public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
		if (drawCount > 0) {
			this.cb().drawIndirect(true, (DxBuffer)commands.buffer(), commands.offset(), drawCount);
			this.queue.commit();
		}
	}

	@Override
	public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		throw new UnsupportedOperationException("DX Render does not offer interleaved multi draw");
	}

	@Override
	public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
		for (int i = 0; i < drawCount; i++) {
			this.draw(vertexCounts.get(vertexCounts.position() + i), 1, firstVertices.get(firstVertices.position() + i), 0);
		}
	}

	@Override
	public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
		if (drawCount > 0) {
			this.cb().drawIndirect(false, (DxBuffer)commands.buffer(), commands.offset(), drawCount);
			this.queue.commit();
		}
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		((DxQueryPool)pool).writeTimestamp(index);
	}
}
