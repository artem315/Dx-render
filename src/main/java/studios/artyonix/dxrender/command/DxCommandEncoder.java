package studios.artyonix.dxrender.command;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.device.DxBuffer;
import studios.artyonix.dxrender.device.DxDevice;
import studios.artyonix.dxrender.device.DxFence;
import studios.artyonix.dxrender.device.DxFramebuffer;
import studios.artyonix.dxrender.device.DxQueryPool;
import studios.artyonix.dxrender.device.DxTexture;
import studios.artyonix.dxrender.device.DxTextureView;
import studios.artyonix.dxrender.memory.DxTransientMemory;

/**
 * DX Render Command Processor, recording side: turns what the game asks for into DX Render
 * commands in the queue's command buffer. It knows nothing about the backend; the driver
 * replays the commands (immediately for the CPU rasterizer, per pass for the GPU).
 */
public final class DxCommandEncoder implements CommandEncoderBackend {
	private final DxDevice device;
	private final DxCommandQueue queue;
	private final DxTransientMemory transientMemory;
	private final DxRenderPass renderPass;
	private final boolean immediate;
	private boolean passOpen;

	public DxCommandEncoder(final DxDevice device) {
		this.device = device;
		this.queue = device.queue();
		this.immediate = device.driver().executesImmediately();
		this.transientMemory = new DxTransientMemory(device.memory());
		this.renderPass = new DxRenderPass(this.queue);
	}

	public void close() {
		this.transientMemory.close();
	}

	private DxCommandBuffer cb() {
		return this.queue.buffer();
	}

	@Override
	public void submit() {
		if (this.passOpen) {
			this.cb().endPass();
			this.passOpen = false;
		}
		this.queue.submitFrame();
		this.transientMemory.rotate();
		this.device.memory().endFrame();
		this.device.profiler().frame(this.device);
	}

	@Override
	public TransientMemory transientMemory() {
		return this.transientMemory;
	}

	@Override
	public RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
		if (this.passOpen) {
			this.cb().endPass();
		}
		DxFramebuffer fb = new DxFramebuffer();
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		fb.colorCount = Math.min(DxFramebuffer.MAX_COLOR, colors.size());
		for (int i = 0; i < fb.colorCount; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> a = colors.get(i);
			if (a == null) {
				continue;
			}
			fb.color[i] = (DxTextureView)a.textureView();
			if (a.clearValue().isPresent()) {
				Vector4fc c = a.clearValue().get();
				fb.clearColorMask |= 1 << i;
				fb.clearColor[i * 4] = c.x();
				fb.clearColor[i * 4 + 1] = c.y();
				fb.clearColor[i * 4 + 2] = c.z();
				fb.clearColor[i * 4 + 3] = c.w();
			}
		}
		RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
		if (depth != null) {
			fb.depth = (DxTextureView)depth.textureView();
			if (depth.clearValue().isPresent()) {
				fb.clearDepth = true;
				fb.clearDepthValue = (float)depth.clearValue().getAsDouble();
			}
		}
		RenderPass.RenderArea area = descriptor.renderArea();
		fb.x = area.x();
		fb.y = area.y();
		fb.width = area.width();
		fb.height = area.height();
		this.cb().beginPass(fb);
		this.queue.commit();
		this.passOpen = true;
		return this.renderPass;
	}

	@Override
	public void submitRenderPass() {
		if (this.passOpen) {
			this.cb().endPass();
			this.passOpen = false;
		}
		// One driver call per pass: the GPU driver translates the whole pass at once.
		this.queue.flush();
	}

	@Override
	public void clearColorTexture(final GpuTexture colorTexture, final Vector4fc c) {
		this.cb().clearColor((DxTexture)colorTexture, -1, c.x(), c.y(), c.z(), c.w(), 0, 0, 0, 0);
		this.queue.commit();
	}

	@Override
	public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture, final double clearDepth) {
		this.clearColorTexture(colorTexture, clearColor);
		this.clearDepthTexture(depthTexture, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc c, final GpuTexture depthTexture, final double clearDepth,
		final int regionX, final int regionY, final int regionWidth, final int regionHeight, final int mipLevel) {
		if (regionWidth <= 0 || regionHeight <= 0) {
			return;
		}
		this.cb().clearColor((DxTexture)colorTexture, mipLevel, c.x(), c.y(), c.z(), c.w(), regionX, regionY, regionWidth, regionHeight);
		this.cb().clearDepth((DxTexture)depthTexture, mipLevel, (float)clearDepth, regionX, regionY, regionWidth, regionHeight);
		this.queue.commit();
	}

	@Override
	public void clearDepthTexture(final GpuTexture depthTexture, final double clearDepth) {
		this.cb().clearDepth((DxTexture)depthTexture, -1, (float)clearDepth, 0, 0, 0, 0);
		this.queue.commit();
	}

	@Override
	public void writeToBuffer(final GpuBufferSlice slice, final ByteBuffer data) {
		int n = (int)Math.min(data.remaining(), slice.length());
		if (n <= 0) {
			return;
		}
		DxBuffer dst = (DxBuffer)slice.buffer();
		if (this.immediate) {
			MemoryUtil.memCopy(MemoryUtil.memAddress(data), dst.address() + slice.offset(), n);
			return;
		}
		// The GPU executes later: keep the write in command order by copying through staging memory.
		GpuBufferSlice staging = this.transientMemory.uploadStaging(data, 4L, 0);
		this.cb().copyBuffer((DxBuffer)staging.buffer(), staging.offset(), dst, slice.offset(), n);
	}

	@Override
	public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
		long n = Math.min(source.length(), target.length());
		if (n <= 0) {
			return;
		}
		this.cb().copyBuffer((DxBuffer)source.buffer(), source.offset(), (DxBuffer)target.buffer(), target.offset(), n);
		this.queue.commit();
	}

	@Override
	public void writeToTexture(final GpuTexture destination, final ByteBuffer source, final int mipLevel, final int depthOrLayer, final int destX, final int destY,
		final int width, final int height) {
		int texel = destination.getFormat().blockSize();
		long need = (long)width * height * texel;
		if (source.remaining() < need) {
			throw new IllegalArgumentException("writeToTexture: " + source.remaining() + " bytes given, " + need + " needed");
		}
		ByteBuffer view = source.duplicate();
		view.limit(view.position() + (int)need);
		GpuBufferSlice staging = this.transientMemory.uploadStaging(view, Math.max(4, texel), 0);
		this.cb().copyBufferToTexture((DxBuffer)staging.buffer(), staging.offset(), width, (DxTexture)destination, depthOrLayer, mipLevel, destX, destY, width, height);
		this.queue.commit();
	}

	@Override
	public void copyBufferToTexture(final GpuBufferSlice source, final int sourceX, final int sourceY, final int sourceWidth, final int sourceHeight,
		final GpuTexture destination, final int destinationX, final int destinationY, final int copyWidth, final int copyHeight, final int mipLevel,
		final int arrayLayer) {
		long texel = destination.getFormat().blockSize();
		long skip = (sourceX + (long)sourceY * sourceWidth) * texel;
		this.cb().copyBufferToTexture((DxBuffer)source.buffer(), source.offset() + skip, sourceWidth, (DxTexture)destination, arrayLayer, mipLevel, destinationX,
			destinationY, copyWidth, copyHeight);
		this.queue.commit();
	}

	@Override
	public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel, final int x,
		final int y, final int width, final int height) {
		long need = (long)width * height * source.getFormat().blockSize();
		if (offset + need > destination.size()) {
			throw new IllegalArgumentException("copyTextureToBuffer: destination too small");
		}
		this.cb().copyTextureToBuffer((DxTexture)source, mipLevel, x, y, width, height, (DxBuffer)destination, offset);
		this.queue.commit();
		// The data is there once the frame carrying the copy has finished executing.
		RenderSystem.queueFencedTask(callback);
	}

	@Override
	public void copyTextureToTexture(final GpuTexture source, final GpuTexture destination, final int mipLevel, final int destX, final int destY, final int sourceX,
		final int sourceY, final int width, final int height) {
		this.cb().copyTextureToTexture((DxTexture)source, (DxTexture)destination, mipLevel, destX, destY, sourceX, sourceY, width, height);
		this.queue.commit();
	}

	@Override
	public GpuFence createFence() {
		return new DxFence(this.queue);
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		((DxQueryPool)pool).writeTimestamp(index);
	}
}
