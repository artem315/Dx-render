package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.EnumSet;
import java.util.function.BooleanSupplier;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;
import studios.artyonix.dxrender.core.DxConfig;

/** The game's window surface, backed by a DX Render swapchain of the active driver. */
public final class DxSurface implements GpuSurfaceBackend {
	private final DxDevice device;
	private final DxSwapchain swapchain;
	private final long window;
	private final BooleanSupplier isIconified;
	private final DxFramePacer pacer = new DxFramePacer();
	private float refreshHz;
	private long refreshQueriedAt;
	private boolean closed;

	DxSurface(final DxDevice device, final DxSwapchain swapchain, final long window, final BooleanSupplier isIconified) {
		this.device = device;
		this.swapchain = swapchain;
		this.window = window;
		this.isIconified = isIconified;
	}

	public String presentPath() {
		return this.swapchain.describe();
	}

	@Override
	public void configure(final GpuSurface.Configuration config) throws SurfaceException {
		this.device.queue().flush();
		try {
			this.swapchain.configure(config.width(), config.height(), config.presentMode() == GpuSurface.PresentMode.FIFO);
		} catch (DxSwapchain.SurfaceUnavailable e) {
			throw new SurfaceException(e.getMessage());
		}
		this.pacer.reset();
	}

	@Override
	public boolean isSuboptimal() {
		return this.swapchain.suboptimal();
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		if (this.isIconified.getAsBoolean()) {
			throw new SurfaceException("Cannot acquire minimized window");
		}
		try {
			this.swapchain.acquire();
		} catch (DxSwapchain.SurfaceUnavailable e) {
			throw new SurfaceException(e.getMessage());
		}
	}

	@Override
	public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
		this.device.queue().flush();
		this.swapchain.blit(((DxTextureView)textureView).dxTexture(), textureView.baseMipLevel());
	}

	private float refreshRate() {
		long now = System.nanoTime();
		if (this.refreshHz == 0.0F || now - this.refreshQueriedAt > 5_000_000_000L) {
			this.refreshQueriedAt = now;
			float hz = 60.0F;
			try {
				SDL_DisplayMode mode = SDLVideo.SDL_GetCurrentDisplayMode(SDLVideo.SDL_GetDisplayForWindow(this.window));
				if (mode != null && mode.refresh_rate() >= 20.0F) {
					hz = mode.refresh_rate();
				}
			} catch (Throwable ignored) {
			}
			this.refreshHz = hz;
		}
		return this.refreshHz;
	}

	@Override
	public void present() {
		// A vsynced swapchain paces itself; pacing matters where frames appear the moment they are posted.
		if (DxConfig.get().framePacing && !this.swapchain.paced()) {
			this.pacer.pace(this.refreshRate());
		}
		this.swapchain.present();
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return this.swapchain.supportsImmediate() ? EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE) : EnumSet.of(GpuSurface.PresentMode.FIFO);
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.swapchain.close();
		}
	}
}
