package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.jspecify.annotations.Nullable;
import studios.artyonix.dxrender.core.DxConfig;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.gpu.DxDriver;
import studios.artyonix.dxrender.gpu.cpu.CpuDriver;
import studios.artyonix.dxrender.gpu.vulkan.VkDriver;

/**
 * Entry point the game talks to. The game only ever sees DX Render; which driver executes
 * the commands is decided here from the render mode:
 * AUTO - the GPU driver if a hardware Vulkan device works, otherwise the CPU rasterizer;
 * GPU  - the GPU driver, and a clear error if it cannot start (no silent fallback);
 * CPU  - the CPU rasterizer and SPIR-V interpreter.
 */
public final class DxBackend implements GpuBackend {
	private @Nullable DxDevice device;
	private @Nullable DxDriver driver;

	@Override
	public String getName() {
		return DxRender.BACKEND_NAME;
	}

	@Override
	public void loadLibrary() {
		// Drivers load what they need when the device is created, so that AUTO can try one after the other.
	}

	@Override
	public void unloadLibrary() {
	}

	@Override
	public long createWindow(final @Nullable String title, final int width, final int height, final long flags) {
		DxDriver d = this.driver;
		if (d == null) {
			throw new IllegalStateException("DX Render device has not been created");
		}
		return d.createWindow(title == null ? "Minecraft" : title, width, height, flags);
	}

	private static DxDriver createDriver(final DxConfig cfg) throws BackendCreationException {
		DxConfig.RenderMode mode = cfg.effectiveMode();
		String gpuFailure = null;
		if (mode != DxConfig.RenderMode.CPU) {
			try {
				// An explicit GPU request accepts any Vulkan device; AUTO only takes real hardware.
				return new VkDriver(cfg, mode == DxConfig.RenderMode.GPU);
			} catch (Throwable t) {
				gpuFailure = t.getMessage() == null ? t.toString() : t.getMessage();
				if (mode == DxConfig.RenderMode.GPU) {
					DxRender.LOGGER.error("DX Render GPU backend could not start", t);
					throw new BackendCreationException("DX Render GPU mode was requested but the Vulkan GPU backend cannot start: " + gpuFailure
						+ "\nSet \"renderMode\" to AUTO or CPU in config/dxrender.json to use the software rasterizer.", BackendCreationException.Reason.OTHER);
				}
				DxRender.LOGGER.warn("DX Render AUTO: GPU backend unavailable ({}), using the CPU rasterizer", gpuFailure);
				DxRender.setGpuFallbackReason(gpuFailure);
			}
		}
		try {
			return new CpuDriver(cfg);
		} catch (Throwable t) {
			throw new BackendCreationException("DX Render CPU backend cannot start: " + t.getMessage()
				+ (gpuFailure == null ? "" : "\nGPU backend was tried first: " + gpuFailure), BackendCreationException.Reason.OTHER);
		}
	}

	@Override
	public GpuDevice createDevice(final GpuDebugOptions debugOptions) throws BackendCreationException {
		try {
			this.driver = createDriver(DxConfig.get());
			this.device = new DxDevice(this.driver, debugOptions);
		} catch (BackendCreationException e) {
			DxRender.setInactive(e.getMessage());
			throw e;
		} catch (Throwable t) {
			DxRender.setInactive(String.valueOf(t.getMessage()));
			if (this.driver != null) {
				this.driver.close();
				this.driver = null;
			}
			throw new BackendCreationException("DX Render device creation failed: " + t, BackendCreationException.Reason.OTHER);
		}
		DxRender.setActive(this.device);
		DxRender.LOGGER.info("DX Render {} active, backend {}: {} [{}]", DxRender.version(), this.driver.backendName(), this.driver.caps().deviceName(),
			this.driver.caps().driverInfo());
		return new FrontendGpuDevice(this.device);
	}
}
