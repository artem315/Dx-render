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
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.command.DxCommandEncoder;
import studios.artyonix.dxrender.command.DxCommandQueue;
import studios.artyonix.dxrender.core.DxConfig;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.gpu.DxCaps;
import studios.artyonix.dxrender.gpu.DxDriver;
import studios.artyonix.dxrender.memory.DxMemoryManager;
import studios.artyonix.dxrender.profiler.DxProfiler;
import studios.artyonix.dxrender.shader.DxPipeline;

/**
 * DX Render Device: the one object the game creates resources through. It is independent
 * of the backend; everything backend specific happens behind {@link DxDriver}.
 */
public final class DxDevice implements GpuDeviceBackend {
	private final DxDriver driver;
	private final DxMemoryManager memory;
	private final DxCommandQueue queue;
	private final DxProfiler profiler = new DxProfiler();
	private final DxCommandEncoder encoder;
	private final DeviceInfo info;
	private final boolean debug;
	private volatile @Nullable DxSurface surface;
	private boolean closed;

	public DxDevice(final DxDriver driver, final GpuDebugOptions debugOptions) {
		this.driver = driver;
		this.debug = debugOptions.useLabels();
		this.memory = new DxMemoryManager(driver.allocator());
		this.queue = new DxCommandQueue(driver);
		this.encoder = new DxCommandEncoder(this);
		DxCaps c = driver.caps();
		DeviceLimits limits = new DeviceLimits(c.maxAnisotropy(), c.uniformOffsetAlignment(), c.maxTextureSize(), c.maxAllocationSize(), 0, c.maxColorAttachments(),
			c.maxDrawIndirectCount());
		DeviceFeatures features = new DeviceFeatures(c.wireframe(), c.instanceIndexIncludesBase(), false, false, c.multiDrawIndirect(), c.drawIndirect(),
			c.nonZeroFirstInstance(), true);
		HintsAndWorkarounds hints = new HintsAndWorkarounds(false, false, false, !c.multiDrawIndirect());
		DeviceType type = switch (c.kind()) {
			case CPU -> DeviceType.CPU;
			case INTEGRATED -> DeviceType.INTEGRATED;
			case DISCRETE -> DeviceType.DISCRETE;
			case VIRTUAL -> DeviceType.VIRTUAL;
			default -> DeviceType.OTHER;
		};
		// The F3 screen prints "<backendName> <driverInfo>" where the game shows "OpenGL ..." or "Vulkan ...".
		this.info = new DeviceInfo(c.deviceName(), c.vendorName(), DxRender.version() + " (" + c.driverInfo() + ")", true,
			DxRender.BACKEND_NAME + " " + driver.backendName(), c.timestampPeriod(), limits, features, new HashSet<>(c.extensions()), hints, type);
	}

	public DxDriver driver() {
		return this.driver;
	}

	public DxMemoryManager memory() {
		return this.memory;
	}

	public DxCommandQueue queue() {
		return this.queue;
	}

	public DxProfiler profiler() {
		return this.profiler;
	}

	public @Nullable DxSurface surface() {
		return this.surface;
	}

	/** Pushes the current settings into the driver. Called from the render thread between frames. */
	public void applyConfig() {
		this.queue.flush();
		this.driver.applyConfig(DxConfig.get());
	}

	@Override
	public GpuSurfaceBackend createSurface(final long windowHandle, final BooleanSupplier isIconified) {
		DxSurface s = new DxSurface(this, this.driver.createSwapchain(windowHandle), windowHandle, isIconified);
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
		return new DxSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(final @Nullable String label, final int usage, final GpuFormat format, final int width, final int height,
		final int depthOrLayers, final int mipLevels) {
		DxTexture texture = new DxTexture(this, usage, label == null ? "dxr-texture" : label, format, width, height, depthOrLayers, mipLevels);
		try {
			this.driver.createTexture(texture);
		} catch (OutOfMemoryError e) {
			throw new GpuOutOfMemoryException("Could not allocate texture of " + width + "x" + height + " for " + label + ": " + e.getMessage());
		}
		return texture;
	}

	void releaseTexture(final DxTexture texture) {
		// Pending or in-flight commands may still use it: destroy two frames later.
		this.memory.defer(() -> this.driver.destroyTexture(texture));
	}

	void releaseSampler(final DxSampler sampler) {
		this.memory.defer(() -> this.driver.destroySampler(sampler));
	}

	public void releasePipeline(final DxPipeline pipeline) {
		this.memory.defer(() -> this.driver.destroyPipeline(pipeline));
	}

	@Override
	public GpuTextureView createTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
		return new DxTextureView((DxTexture)texture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final int usage, final long size) {
		try {
			return new DxBuffer(this.memory, usage, size);
		} catch (OutOfMemoryError e) {
			throw new GpuOutOfMemoryException("Could not allocate buffer of " + size + " bytes: " + e.getMessage());
		}
	}

	@Override
	public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final int usage, final ByteBuffer data) {
		DxBuffer buffer = (DxBuffer)this.createBuffer(label, usage, data.remaining());
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
		// Runs on the game's compile executor: validation and driver compilation happen off the render thread.
		DxPipeline pipeline = DxPipeline.create(this, createInfo);
		return pipeline == null ? BackendRenderPipeline.Pending.NULL : () -> pipeline;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		try {
			this.queue.flush();
		} catch (RuntimeException ignored) {
		}
		this.encoder.close();
		DxSurface s = this.surface;
		if (s != null) {
			s.close();
		}
		this.memory.releaseAll();
		this.driver.close();
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
