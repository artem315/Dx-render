package studios.artyonix.dxrender.gpu.cpu;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.command.DxCommandBuffer;
import studios.artyonix.dxrender.command.DxDescriptorSet;
import studios.artyonix.dxrender.core.DxConfig;
import studios.artyonix.dxrender.core.DxNative;
import studios.artyonix.dxrender.device.DxBuffer;
import studios.artyonix.dxrender.device.DxFramebuffer;
import studios.artyonix.dxrender.device.DxSampler;
import studios.artyonix.dxrender.device.DxSwapchain;
import studios.artyonix.dxrender.device.DxTexture;
import studios.artyonix.dxrender.device.DxTextureView;
import studios.artyonix.dxrender.gpu.DxCaps;
import studios.artyonix.dxrender.gpu.DxDriver;
import studios.artyonix.dxrender.gpu.DxStats;
import studios.artyonix.dxrender.memory.DxMemoryAllocator;
import studios.artyonix.dxrender.memory.DxMemoryManager;
import studios.artyonix.dxrender.shader.DxPipeline;
import studios.artyonix.dxrender.shader.DxShaderException;
import studios.artyonix.dxrender.texture.DxTexturePool;

/**
 * DX Render CPU backend: the software rasterizer and SPIR-V interpreter of the native core
 * (native/src), driven through the same driver interface as the GPU backend. Commands are
 * executed as they arrive, exactly as before the driver interface existed.
 */
public final class CpuDriver implements DxDriver {
	private final long handle;
	private final DxTexturePool texturePool;
	private final DxMemoryManager.HostAllocator allocator = new DxMemoryManager.HostAllocator();
	private final DxCaps caps;
	private final long passBlock, drawBlock, pushConstants;
	private final long[] nativeStats = new long[DxNative.ST_COUNT];
	private DxPipeline pipeline;
	private long indexAddress, indexSize, frame;
	private int indexBytes;
	private boolean passActive;

	public CpuDriver(final DxConfig cfg) {
		try {
			DxNative.load();
		} catch (Exception e) {
			throw new IllegalStateException("DX Render native core is not available: " + e.getMessage());
		}
		this.handle = DxNative.nDeviceCreate(cfg.effectiveThreads(), cfg.tileSize, cfg.occlusionCulling, cfg.fastShaders, true, true);
		if (this.handle == 0L) {
			throw new IllegalStateException("native device creation failed: " + DxNative.nLastError());
		}
		this.texturePool = new DxTexturePool(this.handle);
		this.texturePool.setEnabled(cfg.textureCache);
		this.passBlock = MemoryUtil.nmemCalloc(DxNative.PS_SLOTS, 8);
		this.drawBlock = MemoryUtil.nmemCalloc(DxNative.DS_SLOTS, 8);
		this.pushConstants = MemoryUtil.nmemCalloc(256, 1);
		this.caps = new DxCaps("DX Render CPU Rasterizer (" + DxNative.nSimdName() + ", " + DxNative.nDeviceThreads(this.handle) + " threads)", "ARTYONIX STUDIOS",
			"native core " + DxNative.nVersion(), DxCaps.DeviceKind.CPU, 1, 16, 8192, 1L << 30, 8, 0, false, false, false, false, false, true, false, 1.0F, List.of());
	}

	public long handle() {
		return this.handle;
	}

	public DxTexturePool texturePool() {
		return this.texturePool;
	}

	@Override
	public String backendName() {
		return "CPU";
	}

	@Override
	public DxCaps caps() {
		return this.caps;
	}

	@Override
	public DxMemoryAllocator allocator() {
		return this.allocator;
	}

	@Override
	public boolean executesImmediately() {
		return true;
	}

	@Override
	public long createWindow(final String title, final int width, final int height, final long flags) {
		// Keep SDL from silently creating a GPU renderer behind SDL_GetWindowSurface.
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_FRAMEBUFFER_ACCELERATION, "0");
		return SDLVideo.SDL_CreateWindow(title, width, height, flags);
	}

	@Override
	public void createTexture(final DxTexture t) {
		t.handle = this.texturePool.acquire(t.componentType, t.componentCount, t.getWidth(0), t.getHeight(0), t.layers(), t.getMipLevels(), t.isCube());
		if (t.handle == 0L) {
			throw new OutOfMemoryError("Could not allocate texture of " + t.getWidth(0) + "x" + t.getHeight(0) + ": " + DxNative.nLastError());
		}
	}

	@Override
	public void destroyTexture(final DxTexture t) {
		if (t.handle != 0L) {
			this.texturePool.release(t.handle, t.componentType, t.componentCount, t.getWidth(0), t.getHeight(0), t.layers(), t.getMipLevels(), t.isCube());
			t.handle = 0L;
		}
	}

	@Override
	public void destroySampler(final DxSampler sampler) {
	}

	/** Little endian writer for the blob described in native/src/dxr_pipeline.c. */
	private static final class Blob {
		final ByteArrayOutputStream out = new ByteArrayOutputStream(8192);

		void u32(final int v) {
			this.out.write(v);
			this.out.write(v >>> 8);
			this.out.write(v >>> 16);
			this.out.write(v >>> 24);
		}

		void f32(final float v) {
			this.u32(Float.floatToRawIntBits(v));
		}

		void str(final String s) {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			this.u32(b.length);
			this.out.writeBytes(b);
		}

		void words(final int[] words) {
			this.u32(words.length);
			for (int w : words) {
				this.u32(w);
			}
		}
	}

	@Override
	public void createPipeline(final DxPipeline p) throws DxShaderException {
		Blob b = new Blob();
		b.u32(0x50525844);
		b.str(p.name);
		b.words(p.vertex.words());
		b.words(p.fragment.words());
		b.u32(p.attribCount);
		for (int i = 0; i < p.attribCount; i++) {
			b.u32(p.attribSlot[i]);
			b.u32(p.attribLocation[i]);
			b.u32(p.attribType[i]);
			b.u32(p.attribComps[i]);
			b.u32(p.attribOffset[i]);
		}
		b.u32(p.vbCount);
		for (int i = 0; i < p.vbCount; i++) {
			b.u32(p.vbSlot[i]);
			b.u32(p.vbStride[i]);
			b.u32(p.vbStepRate[i]);
		}
		b.u32(p.uniformCount);
		for (int i = 0; i < p.uniformCount; i++) {
			b.str(p.uniformName[i]);
			b.u32(p.uniformKind[i]);
			b.u32(p.uniformType[i]);
			b.u32(p.uniformComps[i]);
		}
		b.u32(p.depthTest ? 1 : 0);
		b.u32(p.depthFunc);
		b.u32(p.depthWrite ? 1 : 0);
		b.f32(p.depthBiasScale);
		b.f32(p.depthBiasConst);
		b.u32(p.cull ? 1 : 0);
		b.u32(p.topology);
		b.u32(p.wireframe ? 1 : 0);
		b.u32(p.targetCount);
		for (int i = 0; i < p.targetCount; i++) {
			b.u32(p.targetPresent[i] ? 1 : 0);
			b.u32(p.targetBlend[i] ? 1 : 0);
			b.u32(p.targetMask[i]);
			b.u32(p.srcColor[i]);
			b.u32(p.dstColor[i]);
			b.u32(p.opColor[i]);
			b.u32(p.srcAlpha[i]);
			b.u32(p.dstAlpha[i]);
			b.u32(p.opAlpha[i]);
		}
		byte[] bytes = b.out.toByteArray();
		long mem = MemoryUtil.nmemAlloc(bytes.length);
		try {
			MemoryUtil.memByteBuffer(mem, bytes.length).put(bytes);
			p.handle = DxNative.nPipelineCreate(this.handle, mem, bytes.length);
			if (p.handle == 0L) {
				throw new DxShaderException(DxNative.nLastError());
			}
		} finally {
			MemoryUtil.nmemFree(mem);
		}
	}

	@Override
	public void destroyPipeline(final DxPipeline pipeline) {
		if (pipeline.handle != 0L) {
			DxNative.nPipelineDestroy(this.handle, pipeline.handle);
			pipeline.handle = 0L;
		}
	}

	@Override
	public DxSwapchain createSwapchain(final long window) {
		return new CpuSwapchain(this.handle, window);
	}

	private void putPass(final int slot, final long value) {
		MemoryUtil.memPutLong(this.passBlock + 8L * slot, value);
	}

	private void put(final int slot, final long value) {
		MemoryUtil.memPutLong(this.drawBlock + 8L * slot, value);
	}

	private void beginPass(final DxFramebuffer fb) {
		this.endPass();
		MemoryUtil.memSet(this.passBlock, 0, DxNative.PS_SLOTS * 8L);
		this.putPass(DxNative.PS_NUM_COLOR, fb.colorCount);
		for (int i = 0; i < fb.colorCount; i++) {
			DxTextureView v = fb.color[i];
			if (v == null) {
				continue;
			}
			this.putPass(DxNative.PS_COLOR + i, v.dxTexture().handle);
			this.putPass(DxNative.PS_COLOR_MIP + i, v.baseMipLevel());
			for (int c = 0; c < 4; c++) {
				this.putPass(DxNative.PS_CLEAR_COLOR + i * 4 + c, Float.floatToRawIntBits(fb.clearColor[i * 4 + c]) & 0xFFFFFFFFL);
			}
		}
		this.putPass(DxNative.PS_CLEAR_MASK, fb.clearColorMask);
		if (fb.depth != null) {
			this.putPass(DxNative.PS_DEPTH, fb.depth.dxTexture().handle);
			this.putPass(DxNative.PS_DEPTH_MIP, fb.depth.baseMipLevel());
			if (fb.clearDepth) {
				this.putPass(DxNative.PS_CLEAR_DEPTH_ON, 1L);
				this.putPass(DxNative.PS_CLEAR_DEPTH, Float.floatToRawIntBits(fb.clearDepthValue) & 0xFFFFFFFFL);
			}
		}
		this.putPass(DxNative.PS_AREA, fb.x);
		this.putPass(DxNative.PS_AREA + 1, fb.y);
		this.putPass(DxNative.PS_AREA + 2, fb.width);
		this.putPass(DxNative.PS_AREA + 3, fb.height);
		this.passActive = DxNative.nPassBegin(this.handle, this.passBlock) != 0;
		MemoryUtil.memSet(this.drawBlock, 0, DxNative.DS_SLOTS * 8L);
		this.pipeline = null;
		this.indexAddress = 0L;
		this.indexSize = 0L;
		this.indexBytes = 0;
	}

	private void endPass() {
		if (this.passActive) {
			DxNative.nPassEnd(this.handle);
			this.passActive = false;
		}
	}

	private void issue(final boolean indexed, final int first, final int count, final int baseVertex, final int instances, final int firstInstance) {
		if (!this.passActive || this.pipeline == null || this.pipeline.handle == 0L || count <= 0 || (indexed && this.indexAddress == 0L)) {
			return;
		}
		this.put(DxNative.DS_INDEX, indexed ? this.indexAddress : 0L);
		this.put(DxNative.DS_INDEX_SIZE, indexed ? this.indexSize : 0L);
		this.put(DxNative.DS_INDEX_BYTES, indexed ? this.indexBytes : 0);
		this.put(DxNative.DS_FIRST, first);
		this.put(DxNative.DS_COUNT, count);
		this.put(DxNative.DS_BASE_VERTEX, baseVertex);
		this.put(DxNative.DS_INSTANCES, Math.max(1, instances));
		this.put(DxNative.DS_FIRST_INSTANCE, firstInstance);
		DxNative.nDraw(this.handle, this.drawBlock);
	}

	@Override
	public void execute(final DxCommandBuffer commands) {
		final long[] d = commands.data();
		final Object[] r = commands.refs();
		final int n = commands.size();
		int p = 0;
		while (p < n) {
			int op = (int)d[p++];
			switch (op) {
				case DxCommandBuffer.OP_BEGIN_PASS -> this.beginPass((DxFramebuffer)r[(int)d[p++]]);
				case DxCommandBuffer.OP_END_PASS -> this.endPass();
				case DxCommandBuffer.OP_SET_PIPELINE -> {
					DxPipeline pl = (DxPipeline)r[(int)d[p++]];
					if (pl != this.pipeline) {
						// bindings are per pipeline: start from an empty set
						MemoryUtil.memSet(this.drawBlock + 8L * DxNative.DS_UNIFORMS, 0, 8L * DxNative.DS_UNIFORM_STRIDE * DxNative.MAX_UNIFORMS);
					}
					this.pipeline = pl;
					this.put(DxNative.DS_PIPELINE, pl.handle);
				}
				case DxCommandBuffer.OP_SET_UNIFORM_BUFFER -> {
					int index = (int)d[p];
					DxBuffer buf = (DxBuffer)r[(int)d[p + 1]];
					DxPipeline pl = this.pipeline;
					if (pl != null && index >= 0 && index < pl.uniformCount && index < DxNative.MAX_UNIFORMS) {
						int base = DxNative.DS_UNIFORMS + index * DxNative.DS_UNIFORM_STRIDE;
						boolean texel = pl.uniformKind[index] == DxDescriptorSet.TEXEL_BUFFER;
						this.put(base, texel ? DxNative.UNIFORM_TEXEL_BUFFER : DxNative.UNIFORM_BUFFER);
						this.put(base + 1, buf.address() + d[p + 2]);
						this.put(base + 2, d[p + 3]);
						this.put(base + 3, (long)pl.uniformType[index] << 32 | (long)pl.uniformComps[index] << 40);
					}
					p += 4;
				}
				case DxCommandBuffer.OP_SET_UNIFORM_TEXTURE -> {
					int index = (int)d[p];
					DxTextureView v = (DxTextureView)r[(int)d[p + 1]];
					DxSampler s = (DxSampler)r[(int)d[p + 2]];
					DxPipeline pl = this.pipeline;
					if (pl != null && index >= 0 && index < pl.uniformCount && index < DxNative.MAX_UNIFORMS) {
						int base = DxNative.DS_UNIFORMS + index * DxNative.DS_UNIFORM_STRIDE;
						this.put(base, DxNative.UNIFORM_SAMPLER | s.stateBits() | (long)(v.baseMipLevel() & 0xFF) << 40 | (long)(Math.min(255, v.mipLevels()) & 0xFF) << 48);
						this.put(base + 1, v.dxTexture().handle);
						this.put(base + 2, 0L);
						this.put(base + 3, Float.floatToRawIntBits(s.maxLodValue()) & 0xFFFFFFFFL);
					}
					p += 3;
				}
				case DxCommandBuffer.OP_SET_UNIFORM_NONE -> {
					int index = (int)d[p++];
					if (index >= 0 && index < DxNative.MAX_UNIFORMS) {
						int base = DxNative.DS_UNIFORMS + index * DxNative.DS_UNIFORM_STRIDE;
						for (int k = 0; k < 4; k++) {
							this.put(base + k, 0L);
						}
					}
				}
				case DxCommandBuffer.OP_PUSH_CONSTANTS -> {
					int bytes = (int)d[p++];
					int words = (bytes + 7) / 8;
					MemoryUtil.memSet(this.pushConstants, 0, 256);
					for (int i = 0; i < words && i < 32; i++) {
						MemoryUtil.memPutLong(this.pushConstants + i * 8L, d[p + i]);
					}
					this.put(DxNative.DS_PUSH, this.pushConstants);
					this.put(DxNative.DS_PUSH_SIZE, Math.min(256, bytes));
					p += words;
				}
				case DxCommandBuffer.OP_SET_SCISSOR -> {
					this.put(DxNative.DS_SCISSOR_ON, d[p]);
					this.put(DxNative.DS_SCISSOR, d[p + 1]);
					this.put(DxNative.DS_SCISSOR + 1, d[p + 2]);
					this.put(DxNative.DS_SCISSOR + 2, d[p + 3]);
					this.put(DxNative.DS_SCISSOR + 3, d[p + 4]);
					p += 5;
				}
				case DxCommandBuffer.OP_SET_VERTEX_BUFFER -> {
					int slot = (int)d[p];
					if (slot >= 0 && slot < DxNative.MAX_VERTEX_BUFFERS) {
						if (d[p + 1] < 0) {
							this.put(DxNative.DS_VB + slot, 0L);
							this.put(DxNative.DS_VB_SIZE + slot, 0L);
						} else {
							DxBuffer b = (DxBuffer)r[(int)d[p + 1]];
							this.put(DxNative.DS_VB + slot, b.address() + d[p + 2]);
							this.put(DxNative.DS_VB_SIZE + slot, b.size() - d[p + 2]);
						}
					}
					p += 3;
				}
				case DxCommandBuffer.OP_SET_INDEX_BUFFER -> {
					if (d[p] < 0) {
						this.indexAddress = 0L;
						this.indexSize = 0L;
					} else {
						DxBuffer b = (DxBuffer)r[(int)d[p]];
						this.indexAddress = b.address();
						this.indexSize = b.size();
					}
					this.indexBytes = (int)d[p + 1];
					p += 2;
				}
				case DxCommandBuffer.OP_DRAW -> {
					this.issue(false, (int)d[p], (int)d[p + 1], 0, (int)d[p + 2], (int)d[p + 3]);
					p += 4;
				}
				case DxCommandBuffer.OP_DRAW_INDEXED -> {
					this.issue(true, (int)d[p], (int)d[p + 1], (int)d[p + 2], (int)d[p + 3], (int)d[p + 4]);
					p += 5;
				}
				case DxCommandBuffer.OP_DRAW_INDIRECT -> {
					// VkDrawIndirectCommand layout: vertexCount, instanceCount, firstVertex, firstInstance
					long addr = ((DxBuffer)r[(int)d[p]]).address() + d[p + 1];
					for (int i = 0; i < (int)d[p + 2]; i++) {
						long c = addr + 16L * i;
						this.issue(false, MemoryUtil.memGetInt(c + 8), MemoryUtil.memGetInt(c), 0, MemoryUtil.memGetInt(c + 4), MemoryUtil.memGetInt(c + 12));
					}
					p += 3;
				}
				case DxCommandBuffer.OP_DRAW_INDEXED_INDIRECT -> {
					// indexCount, instanceCount, firstIndex, vertexOffset, firstInstance
					long addr = ((DxBuffer)r[(int)d[p]]).address() + d[p + 1];
					for (int i = 0; i < (int)d[p + 2]; i++) {
						long c = addr + 20L * i;
						this.issue(true, MemoryUtil.memGetInt(c + 8), MemoryUtil.memGetInt(c), MemoryUtil.memGetInt(c + 12), MemoryUtil.memGetInt(c + 4),
							MemoryUtil.memGetInt(c + 16));
					}
					p += 3;
				}
				case DxCommandBuffer.OP_CLEAR_COLOR -> {
					this.endPass();
					DxTexture t = (DxTexture)r[(int)d[p]];
					int mip = (int)d[p + 1];
					float cr = Float.intBitsToFloat((int)d[p + 2]), cg = Float.intBitsToFloat((int)d[p + 3]), cb = Float.intBitsToFloat((int)d[p + 4]),
						ca = Float.intBitsToFloat((int)d[p + 5]);
					int x = (int)d[p + 6], y = (int)d[p + 7], w = (int)d[p + 8], h = (int)d[p + 9];
					for (int m = mip < 0 ? 0 : mip; m <= (mip < 0 ? t.getMipLevels() - 1 : mip); m++) {
						if (w <= 0) {
							DxNative.nTextureClearColor(t.handle, m, cr, cg, cb, ca, 0, 0, t.getWidth(m), t.getHeight(m));
						} else {
							DxNative.nTextureClearColor(t.handle, m, cr, cg, cb, ca, x, y, w, h);
						}
					}
					p += 10;
				}
				case DxCommandBuffer.OP_CLEAR_DEPTH -> {
					this.endPass();
					DxTexture t = (DxTexture)r[(int)d[p]];
					int mip = (int)d[p + 1];
					float depth = Float.intBitsToFloat((int)d[p + 2]);
					int x = (int)d[p + 3], y = (int)d[p + 4], w = (int)d[p + 5], h = (int)d[p + 6];
					for (int m = mip < 0 ? 0 : mip; m <= (mip < 0 ? t.getMipLevels() - 1 : mip); m++) {
						if (w <= 0) {
							DxNative.nTextureClearDepth(t.handle, m, depth, 0, 0, t.getWidth(m), t.getHeight(m));
						} else {
							DxNative.nTextureClearDepth(t.handle, m, depth, x, y, w, h);
						}
					}
					p += 7;
				}
				case DxCommandBuffer.OP_COPY_BUFFER -> {
					MemoryUtil.memCopy(((DxBuffer)r[(int)d[p]]).address() + d[p + 1], ((DxBuffer)r[(int)d[p + 2]]).address() + d[p + 3], d[p + 4]);
					p += 5;
				}
				case DxCommandBuffer.OP_COPY_BUFFER_TO_TEXTURE -> {
					this.endPass();
					DxNative.nTextureWrite(((DxTexture)r[(int)d[p + 3]]).handle, (int)d[p + 4], (int)d[p + 5], (int)d[p + 6], (int)d[p + 7], (int)d[p + 8], (int)d[p + 9],
						((DxBuffer)r[(int)d[p]]).address() + d[p + 1], (int)d[p + 2]);
					p += 10;
				}
				case DxCommandBuffer.OP_COPY_TEXTURE_TO_BUFFER -> {
					this.endPass();
					DxNative.nTextureRead(((DxTexture)r[(int)d[p]]).handle, 0, (int)d[p + 1], (int)d[p + 2], (int)d[p + 3], (int)d[p + 4], (int)d[p + 5],
						((DxBuffer)r[(int)d[p + 6]]).address() + d[p + 7]);
					p += 8;
				}
				case DxCommandBuffer.OP_COPY_TEXTURE_TO_TEXTURE -> {
					this.endPass();
					DxNative.nTextureCopy(((DxTexture)r[(int)d[p]]).handle, ((DxTexture)r[(int)d[p + 1]]).handle, (int)d[p + 2], (int)d[p + 3], (int)d[p + 4], (int)d[p + 5],
						(int)d[p + 6], (int)d[p + 7], (int)d[p + 8]);
					p += 9;
				}
				default -> throw new IllegalStateException("corrupt DX Render command stream (opcode " + op + ")");
			}
		}
	}

	@Override
	public void endFrame() {
		this.endPass();
		DxNative.nEndFrame(this.handle);
		this.texturePool.endFrame();
		this.frame++;
	}

	@Override
	public long frameIndex() {
		return this.frame;
	}

	@Override
	public boolean awaitFrame(final long frameNumber, final long timeoutNs) {
		return frameNumber < this.frame; // execution is synchronous: a submitted frame is a finished frame
	}

	@Override
	public void applyConfig(final DxConfig cfg) {
		this.endPass();
		DxNative.nDeviceConfigure(this.handle, cfg.effectiveThreads(), cfg.tileSize, cfg.occlusionCulling, cfg.fastShaders, true, true);
		this.texturePool.setEnabled(cfg.textureCache);
	}

	@Override
	public void collectStats(final long[] out, final boolean reset) {
		DxNative.nDeviceStats(this.handle, this.nativeStats, reset);
		java.util.Arrays.fill(out, 0L);
		System.arraycopy(this.nativeStats, 0, out, 0, Math.min(out.length, this.nativeStats.length));
		out[DxStats.NS_CPU] = this.nativeStats[DxNative.ST_NS_GEOMETRY] + this.nativeStats[DxNative.ST_NS_RASTER] + this.nativeStats[DxNative.ST_NS_CLEAR]
			+ this.nativeStats[DxNative.ST_NS_PRESENT];
		out[DxStats.VRAM_BYTES] = this.nativeStats[DxNative.ST_TEXTURE_BYTES] + this.allocator.usedBytes();
	}

	@Override
	public String profileText(final boolean reset) {
		return DxNative.nProfileText(this.handle, reset);
	}

	@Override
	public void close() {
		this.endPass();
		this.texturePool.clear();
		DxNative.nDeviceDestroy(this.handle);
		MemoryUtil.nmemFree(this.passBlock);
		MemoryUtil.nmemFree(this.drawBlock);
		MemoryUtil.nmemFree(this.pushConstants);
	}
}
