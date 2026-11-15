package studios.artyonix.dxrender.gpu.vulkan;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.device.DxSwapchain;
import studios.artyonix.dxrender.device.DxTexture;

/**
 * Vulkan swapchain of the GPU backend. Handles the three ways a surface stops being usable:
 * it changes size (recreate), it becomes out of date or suboptimal (recreate on the next
 * configure), or it is lost altogether, which is routine on Android whenever the app goes to
 * the background (destroy and create the surface again when the window is back).
 */
final class VkSwapchain implements DxSwapchain {
	private static final int VK_ERROR_SURFACE_LOST = -1000000000;
	private static final int VK_ERROR_OUT_OF_DATE = -1000001004;
	private static final int VK_SUBOPTIMAL = 1000001003;

	private final VkDriver driver;
	private final long window;
	private long surface, swapchain;
	private long[] images = new long[0];
	private int format, width, height, presentMode;
	private final long[] imageAvailable = new long[VkDriver.FRAMES], renderFinished = new long[VkDriver.FRAMES];
	private int imageIndex = -1, submittedSlot = -1;
	private boolean acquired, suboptimal, vsync, immediateSupported = true;
	private String description = "Vulkan swapchain (not configured)";

	VkSwapchain(final VkDriver driver, final long window) {
		this.driver = driver;
		this.window = window;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer pl = stack.mallocLong(1);
			for (int i = 0; i < VkDriver.FRAMES; i++) {
				VkFormats.check(vkCreateSemaphore(driver.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, pl), "vkCreateSemaphore");
				this.imageAvailable[i] = pl.get(0);
				VkFormats.check(vkCreateSemaphore(driver.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, pl), "vkCreateSemaphore");
				this.renderFinished[i] = pl.get(0);
			}
		}
	}

	long imageAvailable(final int slot) {
		return this.imageAvailable[slot];
	}

	long renderFinished(final int slot) {
		return this.renderFinished[slot];
	}

	void submitted(final int slot) {
		this.submittedSlot = slot;
	}

	private void destroySwapchain() {
		if (this.swapchain != 0L) {
			this.driver.waitIdle();
			vkDestroySwapchainKHR(this.driver.device, this.swapchain, null);
			this.swapchain = 0L;
		}
		this.images = new long[0];
		this.acquired = false;
	}

	private void destroySurface() {
		this.destroySwapchain();
		if (this.surface != 0L) {
			vkDestroySurfaceKHR(this.driver.instance, this.surface, null);
			this.surface = 0L;
		}
	}

	private void ensureSurface() throws SurfaceUnavailable {
		if (this.surface != 0L) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer pl = stack.mallocLong(1);
			if (!SDLVulkan.SDL_Vulkan_CreateSurface(this.window, this.driver.instance, null, pl)) {
				throw new SurfaceUnavailable("the window has no Vulkan surface right now: " + SDLError.SDL_GetError());
			}
			this.surface = pl.get(0);
			IntBuffer supported = stack.mallocInt(1);
			int rc = vkGetPhysicalDeviceSurfaceSupportKHR(this.driver.physicalDevice, this.driver.queueFamily, this.surface, supported);
			if (rc != VK_SUCCESS || supported.get(0) == 0) {
				this.destroySurface();
				throw new SurfaceUnavailable("the GPU queue cannot present to this window (" + VkFormats.result(rc) + ")");
			}
		}
	}

	@Override
	public void configure(final int requestedWidth, final int requestedHeight, final boolean wantVsync) throws SurfaceUnavailable {
		this.vsync = wantVsync;
		this.suboptimal = false;
		for (int attempt = 0; attempt < 2; attempt++) {
			this.ensureSurface();
			int rc = this.create(requestedWidth, requestedHeight);
			if (rc == VK_SUCCESS) {
				return;
			}
			if (rc == VK_ERROR_SURFACE_LOST && attempt == 0) {
				this.destroySurface(); // build a fresh surface and try once more
				continue;
			}
			throw new SurfaceUnavailable("could not create the swapchain: " + VkFormats.result(rc));
		}
	}

	private int create(final int requestedWidth, final int requestedHeight) throws SurfaceUnavailable {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
			int rc = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(this.driver.physicalDevice, this.surface, caps);
			if (rc != VK_SUCCESS) {
				return rc;
			}
			int w = caps.currentExtent().width(), h = caps.currentExtent().height();
			if (w == -1 || h == -1) {
				w = Math.max(caps.minImageExtent().width(), Math.min(caps.maxImageExtent().width(), requestedWidth));
				h = Math.max(caps.minImageExtent().height(), Math.min(caps.maxImageExtent().height(), requestedHeight));
			}
			if (w <= 0 || h <= 0) {
				throw new SurfaceUnavailable("the window has no area (minimised)");
			}
			if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
				throw new SurfaceUnavailable("the surface does not allow copying into its images (TRANSFER_DST), which the DX Render GPU backend needs");
			}
			IntBuffer pCount = stack.mallocInt(1);
			rc = vkGetPhysicalDeviceSurfaceFormatsKHR(this.driver.physicalDevice, this.surface, pCount, null);
			if (rc != VK_SUCCESS || pCount.get(0) == 0) {
				return rc != VK_SUCCESS ? rc : VK_ERROR_FORMAT_NOT_SUPPORTED;
			}
			VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(pCount.get(0), stack);
			vkGetPhysicalDeviceSurfaceFormatsKHR(this.driver.physicalDevice, this.surface, pCount, formats);
			// The game's output is already display referred: an UNORM target shows it unchanged.
			int chosen = -1, colorSpace = 0;
			for (int pass = 0; pass < 2 && chosen < 0; pass++) {
				for (int i = 0; i < formats.capacity(); i++) {
					int f = formats.get(i).format();
					boolean unorm = f == VK_FORMAT_R8G8B8A8_UNORM || f == VK_FORMAT_B8G8R8A8_UNORM || f == VK_FORMAT_A8B8G8R8_UNORM_PACK32;
					if ((pass == 1 || unorm) && this.driver.canBlitTo(f)) {
						chosen = f;
						colorSpace = formats.get(i).colorSpace();
						break;
					}
				}
			}
			if (chosen < 0) {
				throw new SurfaceUnavailable("no surface format the GPU can blit to");
			}
			if (chosen != VK_FORMAT_R8G8B8A8_UNORM && chosen != VK_FORMAT_B8G8R8A8_UNORM && chosen != VK_FORMAT_A8B8G8R8_UNORM_PACK32) {
				DxRender.LOGGER.warn("Surface offers no 8 bit UNORM format; using Vulkan format {}, colors may look different", chosen);
			}
			vkGetPhysicalDeviceSurfacePresentModesKHR(this.driver.physicalDevice, this.surface, pCount, null);
			IntBuffer modes = stack.mallocInt(Math.max(1, pCount.get(0)));
			vkGetPhysicalDeviceSurfacePresentModesKHR(this.driver.physicalDevice, this.surface, pCount, modes);
			boolean hasImmediate = false, hasMailbox = false;
			for (int i = 0; i < pCount.get(0); i++) {
				hasImmediate |= modes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR;
				hasMailbox |= modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR;
			}
			this.immediateSupported = hasImmediate || hasMailbox;
			// FIFO is the one mode every implementation must offer.
			int mode = this.vsync ? VK_PRESENT_MODE_FIFO_KHR : hasImmediate ? VK_PRESENT_MODE_IMMEDIATE_KHR : hasMailbox ? VK_PRESENT_MODE_MAILBOX_KHR : VK_PRESENT_MODE_FIFO_KHR;
			int count = caps.minImageCount() + 1;
			if (caps.maxImageCount() > 0 && count > caps.maxImageCount()) {
				count = caps.maxImageCount();
			}
			int transform = (caps.supportedTransforms() & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0 ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform();
			int alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
			if ((caps.supportedCompositeAlpha() & alpha) == 0) {
				alpha = (caps.supportedCompositeAlpha() & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR) != 0 ? VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR
					: Integer.lowestOneBit(caps.supportedCompositeAlpha());
			}
			this.driver.waitIdle();
			long old = this.swapchain;
			VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default().surface(this.surface).minImageCount(count).imageFormat(chosen)
				.imageColorSpace(colorSpace).imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
				.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(transform).compositeAlpha(alpha).presentMode(mode).clipped(true).oldSwapchain(old);
			ci.imageExtent().set(w, h);
			LongBuffer pl = stack.mallocLong(1);
			rc = vkCreateSwapchainKHR(this.driver.device, ci, null, pl);
			if (old != 0L) {
				vkDestroySwapchainKHR(this.driver.device, old, null);
				this.swapchain = 0L;
			}
			if (rc != VK_SUCCESS) {
				return rc;
			}
			this.swapchain = pl.get(0);
			vkGetSwapchainImagesKHR(this.driver.device, this.swapchain, pCount, null);
			LongBuffer imgs = stack.mallocLong(pCount.get(0));
			vkGetSwapchainImagesKHR(this.driver.device, this.swapchain, pCount, imgs);
			this.images = new long[pCount.get(0)];
			imgs.get(this.images);
			this.format = chosen;
			this.width = w;
			this.height = h;
			this.presentMode = mode;
			this.acquired = false;
			this.description = "Vulkan swapchain " + w + "x" + h + ", " + this.images.length + " images, "
				+ (mode == VK_PRESENT_MODE_FIFO_KHR ? "FIFO (vsync)" : mode == VK_PRESENT_MODE_MAILBOX_KHR ? "MAILBOX" : "IMMEDIATE");
			return VK_SUCCESS;
		}
	}

	@Override
	public void acquire() throws SurfaceUnavailable {
		if (this.swapchain == 0L) {
			throw new SurfaceUnavailable("no swapchain");
		}
		if (this.acquired) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer pIndex = stack.mallocInt(1);
			int rc = vkAcquireNextImageKHR(this.driver.device, this.swapchain, -1L, this.imageAvailable[this.driver.currentSlot()], VK_NULL_HANDLE, pIndex);
			if (rc == VK_SUBOPTIMAL) {
				this.suboptimal = true;
			} else if (rc == VK_ERROR_OUT_OF_DATE) {
				this.suboptimal = true;
				throw new SurfaceUnavailable("swapchain is out of date (the window changed)");
			} else if (rc == VK_ERROR_SURFACE_LOST) {
				this.destroySurface();
				throw new SurfaceUnavailable("the surface was lost");
			} else if (rc != VK_SUCCESS) {
				throw new SurfaceUnavailable("vkAcquireNextImageKHR: " + VkFormats.result(rc));
			}
			this.imageIndex = pIndex.get(0);
			this.acquired = true;
		}
	}

	@Override
	public void blit(final DxTexture texture, final int mipLevel) {
		if (!this.acquired || this.imageIndex < 0 || this.imageIndex >= this.images.length) {
			return;
		}
		this.driver.recordPresent(this, this.images[this.imageIndex], this.width, this.height, texture, mipLevel);
	}

	@Override
	public void present() {
		if (!this.acquired || this.submittedSlot < 0) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPresentInfoKHR pi = VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(this.renderFinished[this.submittedSlot])).swapchainCount(1)
				.pSwapchains(stack.longs(this.swapchain)).pImageIndices(stack.ints(this.imageIndex));
			int rc = vkQueuePresentKHR(this.driver.queue, pi);
			if (rc == VK_SUBOPTIMAL || rc == VK_ERROR_OUT_OF_DATE) {
				this.suboptimal = true;
			} else if (rc == VK_ERROR_SURFACE_LOST) {
				this.destroySurface();
				this.suboptimal = true;
			} else if (rc != VK_SUCCESS) {
				DxRender.LOGGER.error("vkQueuePresentKHR failed: {}", VkFormats.result(rc));
				this.suboptimal = true;
			}
		}
		this.acquired = false;
		this.submittedSlot = -1;
	}

	@Override
	public boolean suboptimal() {
		return this.suboptimal;
	}

	@Override
	public boolean paced() {
		return this.presentMode == VK_PRESENT_MODE_FIFO_KHR;
	}

	@Override
	public boolean supportsImmediate() {
		return this.immediateSupported;
	}

	@Override
	public String describe() {
		return this.description;
	}

	@Override
	public void close() {
		this.destroySurface();
		for (int i = 0; i < VkDriver.FRAMES; i++) {
			vkDestroySemaphore(this.driver.device, this.imageAvailable[i], null);
			vkDestroySemaphore(this.driver.device, this.renderFinished[i], null);
		}
	}
}
