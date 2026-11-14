package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.command.DxCommandEncoder;
import studios.artyonix.dxrender.core.DxConfig;
import studios.artyonix.dxrender.core.DxNative;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.memory.DxMemoryManager;
import studios.artyonix.dxrender.profiler.DxProfiler;
import studios.artyonix.dxrender.shader.DxPipeline;
import studios.artyonix.dxrender.texture.DxTexturePool;

/** DX Render Device: owns the native core instance and creates every resource. */
public final class DxDevice implements GpuDeviceBackend {
	private final long handle;
	private final DxMemoryManager memory = new DxMemoryManager();
	private final DxTexturePool texturePool;
	private final DxProfiler profiler = new DxProfiler();
	private final DxCommandEncoder encoder;
	private final DeviceInfo info;
	private final boolean debug;
	private volatile @Nullable DxSurface surface;
	private boolean closed;

	public DxDevice(final GpuDebugOptions debugOptions) {
		DxConfig cfg = DxConfig.get();
		this.handle = DxNative.nDeviceCreate(cfg.effectiveThreads(), cfg.tileSize, cfg.occlusionCulling, cfg.fastShaders, true, true);
		if (this.handle == 0L) {
			throw new IllegalStateException("native device creation failed: " + DxNative.nLastError());
		}
		this.debug = debugOptions.useLabels();
		this.texturePool = new DxTexturePool(this.handle);
		this.texturePool.setEnabled(cfg.textureCache);
		this.encoder = new DxCommandEncoder(this);
		DeviceLimits limits = new DeviceLimits(1, 16, 8192, 1L << 30, 0, 8, 0);
		DeviceFeatures features = new DeviceFeatures(false, false, false, false, false, false, false, true);
		HintsAndWorkarounds hints = new HintsAndWorkarounds(false, false, false, true);
		this.info = new DeviceInfo("DX Render CPU Rasterizer (" + DxNative.nSimdName() + ")", "ARTYONIX STUDIOS",
			"dxrender " + DxRender.version() + " / native " + DxNative.nVersion(), true, DxRender.BACKEND_NAME, 1.0F, limits, features, Set.of(), hints,
			DeviceType.CPU);
	}

	public long handle() {
		return this.handle;
	}

	public int threads() {
		return DxNative.nDeviceThreads(this.handle);
	}

	public DxMemoryManager memory() {
		return this.memory;
	}

	public DxProfiler profiler() {
		return this.profiler;
	}

	public DxTexturePool texturePool() {
		return this.texturePool;
	}

	public @Nullable DxSurface surface() {
		return this.surface;
	}

	/** Pushes the current settings into the native core. Called from the render thread between frames. */
	public void applyConfig() {
		DxConfig cfg = DxConfig.get();
		this.encoder.flush();
		DxNative.nDeviceConfigure(this.handle, cfg.effectiveThreads(), cfg.tileSize, cfg.occlusionCulling, cfg.fastShaders, true, true);
		this.texturePool.setEnabled(cfg.textureCache);
	}

	@Override
	public GpuSurfaceBackend createSurface(final long windowHandle, final BooleanSupplier isIconified) {
		DxSurface s = new DxSurface(this, windowHandle, isIconified);
		this.surface = s;
		return s;
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return this.encoder;
	}

	@Override
	public GpuSampler createSampler(final AddressMode addressModeU, final AddressMode addressModeV, final FilterMode minFilter, final FilterMode magFilter,
		final int maxAnisotropy, final OptionalDouble maxLod) {
		return new DxSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(final @Nullable String label, final int usage, final GpuFormat format, final int width, final int height,
		final int depthOrLayers, final int mipLevels) {
		boolean cube = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
		long tex = this.texturePool.acquire(DxFormats.componentType(format), DxFormats.componentCount(format), width, height, cube ? 6 : 1, mipLevels, cube);
		if (tex == 0L) {
			throw new GpuOutOfMemoryException("Could not allocate texture of " + width + "x" + height + " for " + label + ": " + DxNative.nLastError());
		}
		return new DxTexture(this, tex, usage, label == null ? "dxr-texture" : label, format, width, height, depthOrLayers, mipLevels);
	}

	void releaseTexture(final DxTexture texture) {
		GpuFormat format = texture.getFormat();
		boolean cube = texture.isCube();
		long tex = texture.handle();
		int ctype = DxFormats.componentType(format), ncomp = DxFormats.componentCount(format);
		int width = texture.getWidth(0), height = texture.getHeight(0), mips = texture.getMipLevels();
		// Pending draws may still sample it: hand it back two frames later.
		this.memory.defer(() -> this.texturePool.release(tex, ctype, ncomp, width, height, cube ? 6 : 1, mips, cube));
	}

	@Override
	public GpuTextureView createTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
		return new DxTextureView((DxTexture)texture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final int usage, final long size) {
		return new DxBuffer(this.memory, usage, size);
	}

	@Override
	public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final int usage, final ByteBuffer data) {
		DxBuffer buffer = new DxBuffer(this.memory, usage, data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), buffer.address(), data.remaining());
		return buffer;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return this.debug;
	}

	@Override
	public BackendRenderPipeline.Pending compilePipeline(final BackendRenderPipeline.CreateInfo createInfo) {
		// Decoding SPIR-V touches no shared state, so it runs right here on the compile executor.
		DxPipeline pipeline = DxPipeline.create(this, createInfo);
		return pipeline == null ? BackendRenderPipeline.Pending.NULL : () -> pipeline;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.encoder.close();
		this.memory.releaseAll();
		this.texturePool.clear();
		DxNative.nDeviceDestroy(this.handle);
		DxRender.setInactive("device closed");
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(final int size) {
		return new DxQueryPool(size);
	}

	@Override
	public long getTimestampCalibrationOffset() {
		return 0L;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.info;
	}
}
