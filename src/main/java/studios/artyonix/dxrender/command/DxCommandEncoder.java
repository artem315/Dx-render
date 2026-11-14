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
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.core.DxNative;
import studios.artyonix.dxrender.device.DxBuffer;
import studios.artyonix.dxrender.device.DxDevice;
import studios.artyonix.dxrender.device.DxFence;
import studios.artyonix.dxrender.device.DxQueryPool;
import studios.artyonix.dxrender.device.DxTexture;
import studios.artyonix.dxrender.device.DxTextureView;
import studios.artyonix.dxrender.memory.DxTransientMemory;

/**
 * DX Render Command Processor: receives the game's command stream and turns it into calls
 * on the native core. Draws are recorded per render pass and rasterized, tile by tile,
 * when the pass ends.
 */
public final class DxCommandEncoder implements CommandEncoderBackend {
	private final DxDevice device;
	private final DxTransientMemory transientMemory;
	private final long passBlock;
	private final long drawBlock;
	private final DxRenderPass renderPass;
	private boolean passOpen;

	public DxCommandEncoder(final DxDevice device) {
		this.device = device;
		this.transientMemory = new DxTransientMemory(device.memory());
		this.passBlock = MemoryUtil.nmemCalloc(DxNative.PS_SLOTS, 8);
		this.drawBlock = MemoryUtil.nmemCalloc(DxNative.DS_SLOTS, 8);
		this.renderPass = new DxRenderPass(device, this.drawBlock);
	}

	public void close() {
		this.flush();
		this.transientMemory.close();
		this.renderPass.close();
		MemoryUtil.nmemFree(this.passBlock);
		MemoryUtil.nmemFree(this.drawBlock);
	}

	/** Finishes any pass that is still being recorded. */
	public void flush() {
		if (this.passOpen) {
			DxNative.nPassEnd(this.device.handle());
			this.passOpen = false;
		}
	}

	@Override
	public void submit() {
		this.flush();
		long dev = this.device.handle();
		DxNative.nEndFrame(dev);
		this.transientMemory.rotate();
		this.device.memory().endFrame();
		this.device.texturePool().endFrame();
		this.device.profiler().frame(dev);
	}

	@Override
	public TransientMemory transientMemory() {
		return this.transientMemory;
	}

	private void put(final int slot, final long value) {
		MemoryUtil.memPutLong(this.passBlock + 8L * slot, value);
	}

	@Override
	public RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
		this.flush();
		MemoryUtil.memSet(this.passBlock, 0, DxNative.PS_SLOTS * 8L);
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		int n = Math.min(8, colors.size());
		long clearMask = 0L;
		this.put(DxNative.PS_NUM_COLOR, n);
		for (int i = 0; i < n; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> a = colors.get(i);
			if (a == null) {
				continue;
			}
			GpuTextureView view = a.textureView();
			this.put(DxNative.PS_COLOR + i, ((DxTextureView)view).dxTexture().handle());
			this.put(DxNative.PS_COLOR_MIP + i, view.baseMipLevel());
			if (a.clearValue().isPresent()) {
				Vector4fc c = a.clearValue().get();
				clearMask |= 1L << i;
				this.put(DxNative.PS_CLEAR_COLOR + i * 4, Float.floatToRawIntBits(c.x()) & 0xFFFFFFFFL);
				this.put(DxNative.PS_CLEAR_COLOR + i * 4 + 1, Float.floatToRawIntBits(c.y()) & 0xFFFFFFFFL);
				this.put(DxNative.PS_CLEAR_COLOR + i * 4 + 2, Float.floatToRawIntBits(c.z()) & 0xFFFFFFFFL);
				this.put(DxNative.PS_CLEAR_COLOR + i * 4 + 3, Float.floatToRawIntBits(c.w()) & 0xFFFFFFFFL);
			}
		}
		this.put(DxNative.PS_CLEAR_MASK, clearMask);
		RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
		if (depth != null) {
			this.put(DxNative.PS_DEPTH, ((DxTextureView)depth.textureView()).dxTexture().handle());
			this.put(DxNative.PS_DEPTH_MIP, depth.textureView().baseMipLevel());
			if (depth.clearValue().isPresent()) {
				this.put(DxNative.PS_CLEAR_DEPTH_ON, 1L);
				this.put(DxNative.PS_CLEAR_DEPTH, Float.floatToRawIntBits((float)depth.clearValue().getAsDouble()) & 0xFFFFFFFFL);
			}
		}
		RenderPass.RenderArea area = descriptor.renderArea();
		this.put(DxNative.PS_AREA, area.x());
		this.put(DxNative.PS_AREA + 1, area.y());
		this.put(DxNative.PS_AREA + 2, area.width());
		this.put(DxNative.PS_AREA + 3, area.height());
		this.passOpen = DxNative.nPassBegin(this.device.handle(), this.passBlock) != 0;
		this.renderPass.begin(this.passOpen);
		return this.renderPass;
	}

	@Override
	public void submitRenderPass() {
		this.flush();
	}

	@Override
	public void clearColorTexture(final GpuTexture colorTexture, final Vector4fc c) {
		this.flush();
		long h = ((DxTexture)colorTexture).handle();
		for (int m = 0; m < colorTexture.getMipLevels(); m++) {
			DxNative.nTextureClearColor(h, m, c.x(), c.y(), c.z(), c.w(), 0, 0, colorTexture.getWidth(m), colorTexture.getHeight(m));
		}
	}

	@Override
	public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture, final double clearDepth) {
		this.clearColorTexture(colorTexture, clearColor);
		this.clearDepthTexture(depthTexture, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc c, final GpuTexture depthTexture, final double clearDepth,
		final int regionX, final int regionY, final int regionWidth, final int regionHeight, final int mipLevel) {
		this.flush();
		DxNative.nTextureClearColor(((DxTexture)colorTexture).handle(), mipLevel, c.x(), c.y(), c.z(), c.w(), regionX, regionY, regionWidth, regionHeight);
		DxNative.nTextureClearDepth(((DxTexture)depthTexture).handle(), mipLevel, (float)clearDepth, regionX, regionY, regionWidth, regionHeight);
	}

	@Override
	public void clearDepthTexture(final GpuTexture depthTexture, final double clearDepth) {
		this.flush();
		long h = ((DxTexture)depthTexture).handle();
		for (int m = 0; m < depthTexture.getMipLevels(); m++) {
			DxNative.nTextureClearDepth(h, m, (float)clearDepth, 0, 0, depthTexture.getWidth(m), depthTexture.getHeight(m));
		}
	}

	@Override
	public void writeToBuffer(final GpuBufferSlice slice, final ByteBuffer data) {
		int n = (int)Math.min(data.remaining(), slice.length());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), ((DxBuffer)slice.buffer()).address() + slice.offset(), n);
	}

	@Override
	public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
		MemoryUtil.memCopy(((DxBuffer)source.buffer()).address() + source.offset(), ((DxBuffer)target.buffer()).address() + target.offset(),
			Math.min(source.length(), target.length()));
	}

	@Override
	public void writeToTexture(final GpuTexture destination, final ByteBuffer source, final int mipLevel, final int depthOrLayer, final int destX, final int destY,
		final int width, final int height) {
		this.flush();
		long need = (long)width * height * destination.getFormat().blockSize();
		if (source.remaining() < need) {
			throw new IllegalArgumentException("writeToTexture: " + source.remaining() + " bytes given, " + need + " needed");
		}
		DxNative.nTextureWrite(((DxTexture)destination).handle(), depthOrLayer, mipLevel, destX, destY, width, height, MemoryUtil.memAddress(source), width);
	}

	@Override
	public void copyBufferToTexture(final GpuBufferSlice source, final int sourceX, final int sourceY, final int sourceWidth, final int sourceHeight,
		final GpuTexture destination, final int destinationX, final int destinationY, final int copyWidth, final int copyHeight, final int mipLevel,
		final int arrayLayer) {
		this.flush();
		long texel = destination.getFormat().blockSize();
		long skip = (sourceX + (long)sourceY * sourceWidth) * texel;
		long addr = ((DxBuffer)source.buffer()).address() + source.offset() + skip;
		DxNative.nTextureWrite(((DxTexture)destination).handle(), arrayLayer, mipLevel, destinationX, destinationY, copyWidth, copyHeight, addr, sourceWidth);
	}

	@Override
	public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel, final int x,
		final int y, final int width, final int height) {
		this.flush();
		long need = (long)width * height * source.getFormat().blockSize();
		if (offset + need > destination.size()) {
			throw new IllegalArgumentException("copyTextureToBuffer: destination too small");
		}
		DxNative.nTextureRead(((DxTexture)source).handle(), 0, mipLevel, x, y, width, height, ((DxBuffer)destination).address() + offset);
		RenderSystem.queueFencedTask(callback);
	}

	@Override
	public void copyTextureToTexture(final GpuTexture source, final GpuTexture destination, final int mipLevel, final int destX, final int destY, final int sourceX,
		final int sourceY, final int width, final int height) {
		this.flush();
		DxNative.nTextureCopy(((DxTexture)source).handle(), ((DxTexture)destination).handle(), mipLevel, destX, destY, sourceX, sourceY, width, height);
	}

	@Override
	public GpuFence createFence() {
		return new DxFence();
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		((DxQueryPool)pool).writeTimestamp(index);
	}
}
