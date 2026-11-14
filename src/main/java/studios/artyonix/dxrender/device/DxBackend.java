package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;
import studios.artyonix.dxrender.core.DxNative;
import studios.artyonix.dxrender.core.DxRender;

/**
 * Entry point the game talks to. It never asks SDL for an OpenGL or Vulkan capable window:
 * the window is a plain surface that receives finished CPU frames.
 */
public final class DxBackend implements GpuBackend {
	private @Nullable DxDevice device;

	@Override
	public String getName() {
		return DxRender.BACKEND_NAME;
	}

	@Override
	public void loadLibrary() throws BackendCreationException {
		try {
			DxNative.load();
		} catch (Exception e) {
			DxRender.setInactive(e.getMessage());
			throw new BackendCreationException("DX Render native core is not available: " + e.getMessage(), BackendCreationException.Reason.OTHER);
		}
	}

	@Override
	public void unloadLibrary() {
		// A JNI library stays mapped for the life of its class loader; nothing to release.
	}

	@Override
	public long createWindow(final @Nullable String title, final int width, final int height, final long flags) {
		// Keep SDL from silently creating a GPU renderer behind SDL_GetWindowSurface.
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_FRAMEBUFFER_ACCELERATION, "0");
		return SDLVideo.SDL_CreateWindow(title == null ? "Minecraft" : title, width, height, flags);
	}

	@Override
	public GpuDevice createDevice(final GpuDebugOptions debugOptions) throws BackendCreationException {
		try {
			this.device = new DxDevice(debugOptions);
		} catch (Throwable t) {
			DxRender.setInactive(String.valueOf(t.getMessage()));
			throw new BackendCreationException("DX Render device creation failed: " + t, BackendCreationException.Reason.OTHER);
		}
		DxRender.setActive(this.device);
		DxRender.LOGGER.info("DX Render {} active: CPU rasterizer, {} threads, {} ({})", DxRender.version(), this.device.threads(), DxNative.nSimdName(),
			DxNative.loadedFrom());
		return new FrontendGpuDevice(this.device);
	}
}
