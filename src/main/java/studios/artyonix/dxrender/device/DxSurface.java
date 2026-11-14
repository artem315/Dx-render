package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_Surface;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.core.DxConfig;
import studios.artyonix.dxrender.core.DxNative;
import studios.artyonix.dxrender.core.DxRender;

/**
 * Puts finished frames on screen without any GPU API of the application's own:
 * <ul>
 * <li>Android: ANativeWindow_lock / unlockAndPost on the Surface owned by the launcher
 * (the compositor's CPU buffer path).</li>
 * <li>Desktop: the SDL window surface, i.e. a shared memory image handed to the window system.</li>
 * </ul>
 */
public final class DxSurface implements GpuSurfaceBackend {
	private static final Set<GpuSurface.PresentMode> MODES = EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);
	private static final int SDL_PIXELFORMAT_XRGB8888 = 0x16161804;
	private static final int SDL_PIXELFORMAT_ARGB8888 = 0x16362004;
	private static final int SDL_PIXELFORMAT_XBGR8888 = 0x16561804;
	private static final int SDL_PIXELFORMAT_ABGR8888 = 0x16762004;

	private final DxDevice device;
	private final long window;
	private final BooleanSupplier isIconified;
	private final boolean android;
	private final DxFramePacer pacer = new DxFramePacer();
	private int width, height;
	private @Nullable DxTexture pending;
	private int pendingMip;
	private boolean warned;
	private float refreshHz;
	private long refreshQueriedAt;
	private volatile String presentPath = "none";

	DxSurface(final DxDevice device, final long window, final BooleanSupplier isIconified) {
		this.device = device;
		this.window = window;
		this.isIconified = isIconified;
		this.android = DxNative.nIsAndroid();
	}

	public String presentPath() {
		return this.presentPath;
	}

	@Override
	public void configure(final GpuSurface.Configuration config) throws SurfaceException {
		this.width = config.width();
		this.height = config.height();
		this.pacer.reset();
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		if (this.isIconified.getAsBoolean()) {
			throw new SurfaceException("Cannot acquire minimized window");
		}
	}

	@Override
	public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
		this.pending = ((DxTextureView)textureView).dxTexture();
		this.pendingMip = textureView.baseMipLevel();
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
		DxTexture tex = this.pending;
		this.pending = null;
		if (tex == null || tex.isClosed()) {
			return;
		}
		if (DxConfig.get().framePacing) {
			this.pacer.pace(this.refreshRate());
		}
		int sw = tex.getWidth(this.pendingMip), sh = tex.getHeight(this.pendingMip);
		long src = DxNative.nTextureLevelAddress(tex.handle(), 0, this.pendingMip);
		if (src == 0L) {
			return;
		}
		long dev = this.device.handle();
		DxFrameDump.maybeDump(src, sw, sh);
		if (this.android) {
			long nativeWindow = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(this.window), "SDL.window.android.window", 0L);
			int rc = nativeWindow == 0L ? -1 : DxNative.nPresentAndroid(dev, nativeWindow, src, sw, sh, sw * 4);
			this.presentPath = rc == 0 ? "ANativeWindow_lock (CPU buffer)" : "failed (" + rc + ")";
			if (rc != 0 && !this.warned) {
				this.warned = true;
				DxRender.LOGGER.error("DX Render could not post a frame to the Android surface (code {}). "
					+ "-1: launcher did not expose the ANativeWindow, -2: buffer geometry refused, -3: surface is owned by another API (EGL/Vulkan).", rc);
			}
			return;
		}
		SDL_Surface surface = SDLVideo.SDL_GetWindowSurface(this.window);
		if (surface == null) {
			if (!this.warned) {
				this.warned = true;
				DxRender.LOGGER.error("SDL_GetWindowSurface failed: {}", SDLError.SDL_GetError());
			}
			return;
		}
		long pixels = MemoryUtil.memGetAddress(surface.address() + SDL_Surface.PIXELS);
		int format = surface.format();
		int dstFormat;
		if (format == SDL_PIXELFORMAT_XRGB8888 || format == SDL_PIXELFORMAT_ARGB8888) {
			dstFormat = 1;
		} else if (format == SDL_PIXELFORMAT_XBGR8888 || format == SDL_PIXELFORMAT_ABGR8888) {
			dstFormat = 0;
		} else {
			if (!this.warned) {
				this.warned = true;
				DxRender.LOGGER.error("Unsupported window surface pixel format 0x{}", Integer.toHexString(format));
			}
			return;
		}
		if (pixels != 0L) {
			DxNative.nPresentBlit(dev, src, sw, sh, sw * 4, pixels, surface.w(), surface.h(), surface.pitch(), dstFormat);
			SDLVideo.SDL_UpdateWindowSurface(this.window);
			this.presentPath = "SDL window surface (shared memory)";
		}
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return MODES;
	}

	@Override
	public void close() {
		this.pending = null;
	}
}
