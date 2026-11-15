package studios.artyonix.dxrender.gpu.cpu;

import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Surface;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.core.DxNative;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.device.DxSwapchain;
import studios.artyonix.dxrender.device.DxTexture;

/**
 * Presentation of the CPU backend, without any GPU API of the application's own:
 * Android: ANativeWindow_lock / unlockAndPost on the launcher's Surface (the compositor's
 * CPU buffer path); desktop: the SDL window surface, a shared memory image.
 */
final class CpuSwapchain implements DxSwapchain {
	private static final int SDL_PIXELFORMAT_XRGB8888 = 0x16161804;
	private static final int SDL_PIXELFORMAT_ARGB8888 = 0x16362004;
	private static final int SDL_PIXELFORMAT_XBGR8888 = 0x16561804;
	private static final int SDL_PIXELFORMAT_ABGR8888 = 0x16762004;

	private final long device;
	private final long window;
	private final boolean android;
	private DxTexture pending;
	private int pendingMip;
	private boolean warned;
	private String path = "not presented yet";

	CpuSwapchain(final long device, final long window) {
		this.device = device;
		this.window = window;
		this.android = DxNative.nIsAndroid();
	}

	@Override
	public void configure(final int width, final int height, final boolean vsync) {
	}

	@Override
	public void acquire() {
	}

	@Override
	public void blit(final DxTexture texture, final int mipLevel) {
		this.pending = texture;
		this.pendingMip = mipLevel;
	}

	@Override
	public void present() {
		DxTexture tex = this.pending;
		this.pending = null;
		if (tex == null || tex.isClosed() || tex.handle == 0L) {
			return;
		}
		int sw = tex.getWidth(this.pendingMip), sh = tex.getHeight(this.pendingMip);
		long src = DxNative.nTextureLevelAddress(tex.handle, 0, this.pendingMip);
		if (src == 0L) {
			return;
		}
		CpuFrameDump.maybeDump(src, sw, sh);
		if (this.android) {
			long nativeWindow = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(this.window), "SDL.window.android.window", 0L);
			int rc = nativeWindow == 0L ? -1 : DxNative.nPresentAndroid(this.device, nativeWindow, src, sw, sh, sw * 4);
			this.path = rc == 0 ? "ANativeWindow_lock (CPU buffer)" : "failed (" + rc + ")";
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
			DxNative.nPresentBlit(this.device, src, sw, sh, sw * 4, pixels, surface.w(), surface.h(), surface.pitch(), dstFormat);
			SDLVideo.SDL_UpdateWindowSurface(this.window);
			this.path = "SDL window surface (shared memory)";
		}
	}

	@Override
	public boolean suboptimal() {
		return false;
	}

	@Override
	public boolean paced() {
		return false;
	}

	@Override
	public boolean supportsImmediate() {
		return true;
	}

	@Override
	public String describe() {
		return this.path;
	}

	@Override
	public void close() {
		this.pending = null;
	}
}
