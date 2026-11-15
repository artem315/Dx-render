package studios.artyonix.dxrender.gpu.vulkan;

import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferViewCreateInfo;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import studios.artyonix.dxrender.command.DxCommandBuffer;
import studios.artyonix.dxrender.command.DxDescriptorSet;
import studios.artyonix.dxrender.core.DxConfig;
import studios.artyonix.dxrender.core.DxNative;
import studios.artyonix.dxrender.core.DxRender;
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
import studios.artyonix.dxrender.shader.DxPipeline;
import studios.artyonix.dxrender.shader.DxShader;
import studios.artyonix.dxrender.shader.DxShaderException;

/**
 * DX Render GPU backend: the Vulkan driver. Written against the Vulkan API directly; it does
 * not use or wrap the game's own renderers.
 *
 * Conventions shared with the CPU backend: framebuffer row 0 is NDC y = -1 (no viewport
 * flip), clip z runs 0..1, counter clockwise triangles in that space are front facing
 * (which is VK_FRONT_FACE_CLOCKWISE in Vulkan's y-down framebuffer space), and the image is
 * flipped once, when it is copied to the swapchain.
 *
 * Every optional capability is queried before use: Vulkan version, queue families,
 * presentation support, device features, format features, limits.
 */
public final class VkDriver implements DxDriver {
	static final int FRAMES = 2;
	private static final int RESTING = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
	private static final long SDL_WINDOW_VULKAN = 0x10000000L;
	private static final AtomicInteger VALIDATION_MESSAGES = new AtomicInteger();

	// ---- device ----
	final VkInstance instance;
	final VkPhysicalDevice physicalDevice;
	final VkDevice device;
	final VkQueue queue;
	final int queueFamily;
	private final VkMemory memory;
	private final DxCaps caps;
	private final int apiMajor, apiMinor;
	private final boolean featIndependentBlend, featMultiDrawIndirect, featFirstInstance, featWireframe, featAnisotropy, featDrawParameters;
	private final int maxDynamicUbo, maxDynamicSsbo, maxPushConstants, maxUniformRange;
	private final float maxAnisotropy, timestampPeriod;
	private final boolean timestamps;
	private long debugMessenger;
	private VkDebugUtilsMessengerCallbackEXT debugCallback;
	private long pipelineCache;
	private Path pipelineCacheFile;
	private int pipelinesSinceSave;
	private long queryPool;

	// ---- caches ----
	private final Object lock = new Object();
	private final HashMap<Long, Long> shaderModules = new HashMap<>();
	private final HashMap<Long, Long> samplers = new HashMap<>();
	private final HashMap<PassKey, PassEntry> renderPasses = new HashMap<>();
	private final HashMap<String, Integer> compatIds = new HashMap<>();
	private final HashMap<FbKey, Long> framebuffers = new HashMap<>();
	private final HashMap<Integer, Integer> formatFeatures = new HashMap<>();
	private final HashMap<Integer, Integer> bufferFormatFeatures = new HashMap<>();
	private final ArrayList<VkTextureState> needInit = new ArrayList<>();
	private final ArrayList<PendingClear> pendingClears = new ArrayList<>();
	private final DxMemoryAllocator.Allocation dummyBuffer;
	private final VkTextureState dummyTexture;
	private long dummySampler;

	// ---- frames ----
	private final Frame[] frames = new Frame[FRAMES];
	private Frame frame;
	private boolean frameBegun;
	private long frameIndex;
	private long completedThrough = -1L;
	VkSwapchain pendingPresent;

	// ---- replay state ----
	private boolean inPass;
	private PassEntry curPass;
	private int passWidth, passHeight, areaX, areaY, areaW, areaH;
	private DxPipeline curPipeline;
	private long boundPipeline, boundDsl, boundLayout, boundPushLayout, curSet;
	private DxPipeline boundVbPipeline;
	private final DxDescriptorSet bindings = new DxDescriptorSet();
	private final DxBuffer[] vb = new DxBuffer[8];
	private final long[] vbOffset = new long[8];
	private boolean vbDirty, ibDirty, scissorDirty, pushDirty, scissorEnabled;
	private DxBuffer ib;
	private int ibBytes, scissorX, scissorY, scissorW, scissorH, pushBytes;
	private VkTextureState openTransfer;
	private int openTransferMip;
	private boolean bufferWritesPending;
	private final PassKey scratchKey = new PassKey();
	private final FbKey scratchFb = new FbKey();
	private final long[] descKey = new long[DxDescriptorSet.MAX_BINDINGS * 3 + 1];
	private final LongBuffer pLong = MemoryUtil.memAllocLong(1), pLong2 = MemoryUtil.memAllocLong(1);
	private final IntBuffer dynOffsets = MemoryUtil.memAllocInt(DxDescriptorSet.MAX_BINDINGS);
	private final ByteBuffer pushData = MemoryUtil.memCalloc(256);
	private final long[] stats = new long[DxStats.COUNT];
	private final ByteBuffer entryMain = MemoryUtil.memUTF8("main");

	// ======================================================================================
	// small records
	// ======================================================================================
	private static final class Frame {
		long pool, fence, number;
		VkCommandBuffer main, setup;
		boolean submitted;
		final ArrayList<Long> descriptorPools = new ArrayList<>();
		int poolCursor;
		final DescCache descCache = new DescCache();
		final ArrayList<long[]> bufferViews = new ArrayList<>(); // {buffer, offset, range, format, view}
	}

	private static final class DescCache {
		long[] hashes = new long[512];
		long[][] keys = new long[512][];
		long[] sets = new long[512];
		int size;

		long find(final long hash, final long[] key, final int n) {
			int mask = this.hashes.length - 1;
			for (int i = (int)hash & mask;; i = (i + 1) & mask) {
				long[] k = this.keys[i];
				if (k == null) {
					return 0L;
				}
				if (this.hashes[i] == hash && k.length == n && Arrays.equals(k, 0, n, key, 0, n)) {
					return this.sets[i];
				}
			}
		}

		void put(final long hash, final long[] key, final int n, final long set) {
			if (this.size * 2 >= this.hashes.length) {
				long[] oh = this.hashes;
				long[][] ok = this.keys;
				long[] os = this.sets;
				this.hashes = new long[oh.length * 2];
				this.keys = new long[oh.length * 2][];
				this.sets = new long[oh.length * 2];
				this.size = 0;
				for (int i = 0; i < oh.length; i++) {
					if (ok[i] != null) {
						this.insert(oh[i], ok[i], os[i]);
					}
				}
			}
			this.insert(hash, Arrays.copyOf(key, n), set);
		}

		private void insert(final long hash, final long[] key, final long set) {
			int mask = this.hashes.length - 1;
			int i = (int)hash & mask;
			while (this.keys[i] != null) {
				i = (i + 1) & mask;
			}
			this.hashes[i] = hash;
			this.keys[i] = key;
			this.sets[i] = set;
			this.size++;
		}

		void clear() {
			if (this.size > 0) {
				Arrays.fill(this.keys, null);
				this.size = 0;
			}
		}
	}

	/** Shape of a render pass: formats and load operations of its attachments. */
	private static final class PassKey {
		int count;
		final int[] format = new int[8];
		final int[] load = new int[8]; // 0 unused, 1 load, 2 clear
		int depthFormat, depthLoad;

		PassKey copy() {
			PassKey k = new PassKey();
			k.count = this.count;
			System.arraycopy(this.format, 0, k.format, 0, 8);
			System.arraycopy(this.load, 0, k.load, 0, 8);
			k.depthFormat = this.depthFormat;
			k.depthLoad = this.depthLoad;
			return k;
		}

		String compat() {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < this.count; i++) {
				sb.append(this.load[i] == 0 ? 0 : this.format[i]).append(',');
			}
			return sb.append('d').append(this.depthFormat).toString();
		}

		@Override
		public int hashCode() {
			int h = this.count * 31 + this.depthFormat * 7 + this.depthLoad;
			for (int i = 0; i < this.count; i++) {
				h = h * 31 + this.format[i] * 3 + this.load[i];
			}
			return h;
		}

		@Override
		public boolean equals(final Object o) {
			if (!(o instanceof PassKey k) || k.count != this.count || k.depthFormat != this.depthFormat || k.depthLoad != this.depthLoad) {
				return false;
			}
			for (int i = 0; i < this.count; i++) {
				if (k.format[i] != this.format[i] || k.load[i] != this.load[i]) {
					return false;
				}
			}
			return true;
		}
	}

	private static final class PassEntry {
		long renderPass;
		int compatId;
		PassKey key;
	}

	private static final class FbKey {
		long renderPass;
		final long[] views = new long[9];
		int count, width, height;

		FbKey copy() {
			FbKey k = new FbKey();
			k.renderPass = this.renderPass;
			System.arraycopy(this.views, 0, k.views, 0, 9);
			k.count = this.count;
			k.width = this.width;
			k.height = this.height;
			return k;
		}

		@Override
		public int hashCode() {
			long h = this.renderPass * 31 + this.width * 17L + this.height;
			for (int i = 0; i < this.count; i++) {
				h = h * 31 + this.views[i];
			}
			return (int)(h ^ h >>> 32);
		}

		@Override
		public boolean equals(final Object o) {
			if (!(o instanceof FbKey k) || k.renderPass != this.renderPass || k.count != this.count || k.width != this.width || k.height != this.height) {
				return false;
			}
			for (int i = 0; i < this.count; i++) {
				if (k.views[i] != this.views[i]) {
					return false;
				}
			}
			return true;
		}
	}

	private static final class PendingClear {
		VkTextureState texture;
		int mip;
		float r, g, b, a;
	}

	/** GPU state of a DX Render texture. */
	static final class VkTextureState {
		long image;
		VkMemory.Region region;
		int format, aspect, width, height, mips, layers, componentType;
		boolean cube;
		int[] layout;
		final HashMap<Integer, Long> views = new HashMap<>();
	}

	/** GPU state of a DX Render pipeline. */
	private static final class VkPipelineState {
		long vsModule, fsModule, dsl, layout;
		int count, pushSize;
		int[] kind, type, attribFormat;
		boolean[] dynamic;
		boolean hasStatic;
		final HashMap<Integer, Long> variants = new HashMap<>();
	}

	// ======================================================================================
	// creation
	// ======================================================================================
	/**
	 * @param allowSoftware accept a CPU type Vulkan device (lavapipe, SwiftShader). AUTO mode
	 *                      passes false: a software Vulkan is not hardware acceleration.
	 */
	public VkDriver(final DxConfig config, final boolean allowSoftware) {
		int instanceVersion;
		try {
			instanceVersion = VK.getInstanceVersionSupported();
		} catch (Throwable t) {
			throw new IllegalStateException("no Vulkan loader available on this system (" + t + ")");
		}
		if (VK_API_VERSION_MAJOR(instanceVersion) < 1 || (VK_API_VERSION_MAJOR(instanceVersion) == 1 && VK_API_VERSION_MINOR(instanceVersion) < 1)) {
			throw new IllegalStateException("Vulkan 1.1 instance required, found " + version(instanceVersion));
		}
		String path = VK.getFunctionProvider() instanceof SharedLibrary lib ? lib.getPath() : null;
		if (!SDLVulkan.SDL_Vulkan_LoadLibrary(path)) {
			throw new IllegalStateException("the window system cannot use Vulkan: " + SDLError.SDL_GetError());
		}
		boolean wantValidation = Boolean.getBoolean("dxrender.vkValidation");
		VkInstance inst = null;
		VkDevice dev = null;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// ---- instance ----
			PointerBuffer sdlExt = SDLVulkan.SDL_Vulkan_GetInstanceExtensions();
			if (sdlExt == null) {
				throw new IllegalStateException("window system reports no Vulkan surface extensions: " + SDLError.SDL_GetError());
			}
			IntBuffer pCount = stack.mallocInt(1);
			vkEnumerateInstanceExtensionProperties((ByteBuffer)null, pCount, null);
			VkExtensionProperties.Buffer avail = VkExtensionProperties.calloc(pCount.get(0), stack);
			vkEnumerateInstanceExtensionProperties((ByteBuffer)null, pCount, avail);
			ArrayList<String> instExt = new ArrayList<>();
			for (int i = 0; i < avail.capacity(); i++) {
				instExt.add(avail.get(i).extensionNameString());
			}
			ArrayList<String> enable = new ArrayList<>();
			for (int i = 0; i < sdlExt.remaining(); i++) {
				String name = MemoryUtil.memUTF8(sdlExt.get(sdlExt.position() + i));
				if (!instExt.contains(name)) {
					throw new IllegalStateException("required Vulkan instance extension " + name + " is missing");
				}
				enable.add(name);
			}
			boolean validation = false;
			if (wantValidation) {
				vkEnumerateInstanceLayerProperties(pCount, null);
				VkLayerProperties.Buffer layers = VkLayerProperties.calloc(pCount.get(0), stack);
				vkEnumerateInstanceLayerProperties(pCount, layers);
				for (int i = 0; i < layers.capacity(); i++) {
					validation |= layers.get(i).layerNameString().equals("VK_LAYER_KHRONOS_validation");
				}
				validation &= instExt.contains(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
				if (validation) {
					enable.add(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
				} else {
					DxRender.LOGGER.warn("Vulkan validation requested but VK_LAYER_KHRONOS_validation is not installed");
				}
			}
			PointerBuffer ppExt = stack.mallocPointer(enable.size());
			for (String e : enable) {
				ppExt.put(stack.UTF8(e));
			}
			ppExt.flip();
			int apiVersion = Math.min(instanceVersion, VK_MAKE_API_VERSION(0, 1, 3, 0));
			VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default().pApplicationName(stack.UTF8("Minecraft")).pEngineName(stack.UTF8("DX Render"))
				.engineVersion(VK_MAKE_VERSION(1, 0, 0)).apiVersion(apiVersion);
			VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app).ppEnabledExtensionNames(ppExt);
			if (validation) {
				ici.ppEnabledLayerNames(stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation")));
			}
			PointerBuffer pInstance = stack.mallocPointer(1);
			VkFormats.check(vkCreateInstance(ici, null, pInstance), "vkCreateInstance");
			inst = new VkInstance(pInstance.get(0), ici);
			if (validation) {
				this.debugCallback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, pData, user) -> {
					String msg = VkDebugUtilsMessengerCallbackDataEXT.create(pData).pMessageString();
					VALIDATION_MESSAGES.incrementAndGet();
					if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
						DxRender.LOGGER.error("[vulkan validation] {}", msg);
					} else {
						DxRender.LOGGER.warn("[vulkan validation] {}", msg);
					}
					return VK_FALSE;
				});
				VkDebugUtilsMessengerCreateInfoEXT dci = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
					.messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)
					.messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT)
					.pfnUserCallback(this.debugCallback);
				LongBuffer pMessenger = stack.mallocLong(1);
				if (EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(inst, dci, null, pMessenger) == VK_SUCCESS) {
					this.debugMessenger = pMessenger.get(0);
				}
			}

			// ---- physical device ----
			VkFormats.check(vkEnumeratePhysicalDevices(inst, pCount, null), "vkEnumeratePhysicalDevices");
			int n = pCount.get(0);
			if (n == 0) {
				throw new IllegalStateException("Vulkan reports no physical devices");
			}
			PointerBuffer pDevices = stack.mallocPointer(n);
			vkEnumeratePhysicalDevices(inst, pCount, pDevices);
			String wanted = System.getProperty("dxrender.vkDevice", "").toLowerCase(Locale.ROOT);
			VkPhysicalDevice best = null;
			int bestScore = -1, bestFamily = -1;
			StringBuilder rejected = new StringBuilder();
			VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
			for (int i = 0; i < n; i++) {
				VkPhysicalDevice pd = new VkPhysicalDevice(pDevices.get(i), inst);
				vkGetPhysicalDeviceProperties(pd, props);
				String name = props.deviceNameString();
				int v = props.apiVersion();
				if (VK_API_VERSION_MAJOR(v) == 1 && VK_API_VERSION_MINOR(v) < 2) {
					rejected.append("\n  ").append(name).append(": Vulkan ").append(version(v)).append(" (1.2 needed for the game's SPIR-V 1.5 shaders)");
					continue;
				}
				if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_CPU && !allowSoftware) {
					rejected.append("\n  ").append(name).append(": software Vulkan implementation, not a GPU");
					continue;
				}
				if (!wanted.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(wanted)) {
					continue;
				}
				vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer)null, pCount, null);
				VkExtensionProperties.Buffer dext = VkExtensionProperties.calloc(pCount.get(0), stack);
				vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer)null, pCount, dext);
				boolean swapchain = false;
				for (int e = 0; e < dext.capacity(); e++) {
					swapchain |= dext.get(e).extensionNameString().equals(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);
				}
				if (!swapchain) {
					rejected.append("\n  ").append(name).append(": no VK_KHR_swapchain");
					continue;
				}
				vkGetPhysicalDeviceQueueFamilyProperties(pd, pCount, null);
				VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(pCount.get(0), stack);
				vkGetPhysicalDeviceQueueFamilyProperties(pd, pCount, qf);
				int family = -1;
				for (int q = 0; q < qf.capacity(); q++) {
					if ((qf.get(q).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0 && SDLVulkan.SDL_Vulkan_GetPresentationSupport(inst, pd, q)) {
						family = q;
						break;
					}
				}
				if (family < 0) {
					rejected.append("\n  ").append(name).append(": no queue family that can both render and present");
					continue;
				}
				int score = switch (props.deviceType()) {
					case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 4;
					case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 3;
					case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 2;
					case VK_PHYSICAL_DEVICE_TYPE_CPU -> 0;
					default -> 1;
				};
				if (score > bestScore) {
					bestScore = score;
					best = pd;
					bestFamily = family;
				}
			}
			if (best == null) {
				throw new IllegalStateException("no usable Vulkan device" + (rejected.isEmpty() ? "" : ":" + rejected));
			}
			this.physicalDevice = best;
			this.queueFamily = bestFamily;

			// ---- capabilities ----
			VkPhysicalDeviceDriverProperties driverProps = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
			VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(driverProps.address());
			VK11.vkGetPhysicalDeviceProperties2(best, props2);
			VkPhysicalDeviceProperties p = props2.properties();
			VkPhysicalDeviceLimits limits = p.limits();
			this.apiMajor = VK_API_VERSION_MAJOR(p.apiVersion());
			this.apiMinor = VK_API_VERSION_MINOR(p.apiVersion());
			VkPhysicalDeviceVulkan11Features have11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default();
			VkPhysicalDeviceFeatures2 have = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(have11.address());
			VK11.vkGetPhysicalDeviceFeatures2(best, have);
			VkPhysicalDeviceFeatures hf = have.features();
			this.featIndependentBlend = hf.independentBlend();
			this.featMultiDrawIndirect = hf.multiDrawIndirect();
			this.featFirstInstance = hf.drawIndirectFirstInstance();
			this.featWireframe = hf.fillModeNonSolid();
			this.featAnisotropy = hf.samplerAnisotropy();
			this.featDrawParameters = have11.shaderDrawParameters();
			this.maxDynamicUbo = limits.maxDescriptorSetUniformBuffersDynamic();
			this.maxDynamicSsbo = limits.maxDescriptorSetStorageBuffersDynamic();
			this.maxPushConstants = limits.maxPushConstantsSize();
			this.maxUniformRange = limits.maxUniformBufferRange();
			this.maxAnisotropy = limits.maxSamplerAnisotropy();
			this.timestampPeriod = limits.timestampPeriod();
			vkGetPhysicalDeviceQueueFamilyProperties(best, pCount, null);
			VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(pCount.get(0), stack);
			vkGetPhysicalDeviceQueueFamilyProperties(best, pCount, qf);
			this.timestamps = qf.get(bestFamily).timestampValidBits() > 0 && this.timestampPeriod > 0.0F;

			// ---- logical device: enable only what exists and is used ----
			VkPhysicalDeviceVulkan11Features want11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default().shaderDrawParameters(this.featDrawParameters);
			VkPhysicalDeviceFeatures2 want = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(want11.address());
			want.features().independentBlend(this.featIndependentBlend).multiDrawIndirect(this.featMultiDrawIndirect).drawIndirectFirstInstance(this.featFirstInstance)
				.fillModeNonSolid(this.featWireframe).samplerAnisotropy(this.featAnisotropy);
			VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack);
			qci.get(0).sType$Default().queueFamilyIndex(bestFamily).pQueuePriorities(stack.floats(1.0F));
			VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(want.address()).pQueueCreateInfos(qci)
				.ppEnabledExtensionNames(stack.pointers(stack.UTF8(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME)));
			PointerBuffer pDevice = stack.mallocPointer(1);
			VkFormats.check(vkCreateDevice(best, dci, null, pDevice), "vkCreateDevice");
			dev = new VkDevice(pDevice.get(0), best, dci);
			PointerBuffer pQueue = stack.mallocPointer(1);
			vkGetDeviceQueue(dev, bestFamily, 0, pQueue);
			this.queue = new VkQueue(pQueue.get(0), dev);

			int maxIndirect = Integer.compareUnsigned(limits.maxDrawIndirectCount(), Integer.MAX_VALUE) > 0 ? Integer.MAX_VALUE : limits.maxDrawIndirectCount();
			boolean mdi = this.featMultiDrawIndirect && this.featFirstInstance && maxIndirect > 1;
			DxCaps.DeviceKind kind = switch (p.deviceType()) {
				case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> DxCaps.DeviceKind.DISCRETE;
				case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> DxCaps.DeviceKind.INTEGRATED;
				case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> DxCaps.DeviceKind.VIRTUAL;
				case VK_PHYSICAL_DEVICE_TYPE_CPU -> DxCaps.DeviceKind.CPU;
				default -> DxCaps.DeviceKind.OTHER;
			};
			String driverName = driverProps.driverNameString();
			String driverInfo = "Vulkan " + version(p.apiVersion()) + (driverName.isEmpty() ? "" : ", " + driverName + " " + driverProps.driverInfoString());
			this.caps = new DxCaps(p.deviceNameString(), vendor(p.vendorID()), driverInfo, kind, this.featAnisotropy ? Math.max(1, (int)this.maxAnisotropy) : 1,
				Math.max(1, (int)limits.minUniformBufferOffsetAlignment()), Math.min(16384, limits.maxImageDimension2D()), 1L << 30,
				Math.min(8, limits.maxColorAttachments()), mdi ? maxIndirect : 0, this.featWireframe, true, mdi, mdi, this.featFirstInstance,
				this.featIndependentBlend, this.timestamps, this.timestampPeriod, List.of(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME));
		} catch (Throwable t) {
			if (dev != null) {
				vkDestroyDevice(dev, null);
			}
			if (inst != null) {
				if (this.debugMessenger != 0L) {
					EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(inst, this.debugMessenger, null);
				}
				vkDestroyInstance(inst, null);
			}
			throw t;
		}
		this.instance = inst;
		this.device = dev;
		this.memory = new VkMemory(this.physicalDevice, this.device);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer pl = stack.mallocLong(1);
			for (int i = 0; i < FRAMES; i++) {
				Frame f = new Frame();
				VkCommandPoolCreateInfo pci = VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(this.queueFamily);
				VkFormats.check(vkCreateCommandPool(this.device, pci, null, pl), "vkCreateCommandPool");
				f.pool = pl.get(0);
				VkCommandBufferAllocateInfo cai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(f.pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
					.commandBufferCount(2);
				PointerBuffer pcb = stack.mallocPointer(2);
				VkFormats.check(vkAllocateCommandBuffers(this.device, cai, pcb), "vkAllocateCommandBuffers");
				f.setup = new VkCommandBuffer(pcb.get(0), this.device);
				f.main = new VkCommandBuffer(pcb.get(1), this.device);
				VkFormats.check(vkCreateFence(this.device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, pl), "vkCreateFence");
				f.fence = pl.get(0);
				this.frames[i] = f;
			}
			if (this.timestamps) {
				VkQueryPoolCreateInfo qp = VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(FRAMES * 2);
				if (vkCreateQueryPool(this.device, qp, null, pl) == VK_SUCCESS) {
					this.queryPool = pl.get(0);
				}
			}
			// pipeline cache, persisted between runs
			ByteBuffer initial = null;
			try {
				this.pipelineCacheFile = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("dxrender").resolve("vulkan-pipeline-cache.bin");
				if (Files.exists(this.pipelineCacheFile)) {
					byte[] bytes = Files.readAllBytes(this.pipelineCacheFile);
					initial = MemoryUtil.memAlloc(bytes.length);
					initial.put(bytes).flip();
				}
			} catch (Throwable t) {
				this.pipelineCacheFile = null;
			}
			VkPipelineCacheCreateInfo pcc = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
			if (initial != null) {
				pcc.pInitialData(initial);
			}
			if (vkCreatePipelineCache(this.device, pcc, null, pl) != VK_SUCCESS) {
				// a stale or foreign cache file is not an error: start empty
				VkFormats.check(vkCreatePipelineCache(this.device, VkPipelineCacheCreateInfo.calloc(stack).sType$Default(), null, pl), "vkCreatePipelineCache");
			}
			this.pipelineCache = pl.get(0);
			if (initial != null) {
				MemoryUtil.memFree(initial);
			}
			// stand-ins for unbound slots
			this.dummyBuffer = this.memory.allocateBuffer(65536, 0);
			MemoryUtil.memSet(this.dummyBuffer.address, 0, 65536);
			this.dummyTexture = this.createImage(DxNative.CT_UNORM8, 4, 1, 1, 1, false, false, true);
			VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
				.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT).addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
				.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT).maxAnisotropy(1.0F).maxLod(VK_LOD_CLAMP_NONE);
			VkFormats.check(vkCreateSampler(this.device, sci, null, pl), "vkCreateSampler");
			this.dummySampler = pl.get(0);
		}
		DxRender.LOGGER.info("DX Render GPU backend: {} ({}), {}; features: multiDrawIndirect={} independentBlend={} anisotropy={} gpuTimestamps={}",
			this.caps.deviceName(), this.caps.vendorName(), this.caps.driverInfo(), this.caps.multiDrawIndirect(), this.featIndependentBlend, this.featAnisotropy,
			this.timestamps);
	}

	private static String version(final int v) {
		return VK_API_VERSION_MAJOR(v) + "." + VK_API_VERSION_MINOR(v) + "." + VK_API_VERSION_PATCH(v);
	}

	private static String vendor(final int id) {
		return switch (id) {
			case 0x13B5 -> "ARM";
			case 0x5143 -> "Qualcomm";
			case 0x10DE -> "NVIDIA";
			case 0x1002 -> "AMD";
			case 0x8086 -> "Intel";
			case 0x1010 -> "Imagination Technologies";
			case 0x144D -> "Samsung";
			case 0x106B -> "Apple";
			case 0x10005 -> "Mesa";
			default -> "vendor 0x" + Integer.toHexString(id);
		};
	}

	public static int validationMessages() {
		return VALIDATION_MESSAGES.get();
	}

	// ======================================================================================
	// DxDriver: identity and resources
	// ======================================================================================
	@Override
	public String backendName() {
		return "GPU";
	}

	@Override
	public DxCaps caps() {
		return this.caps;
	}

	@Override
	public DxMemoryAllocator allocator() {
		return this.memory;
	}

	@Override
	public boolean executesImmediately() {
		return false;
	}

	@Override
	public long createWindow(final String title, final int width, final int height, final long flags) {
		return SDLVideo.SDL_CreateWindow(title, width, height, flags | SDL_WINDOW_VULKAN);
	}

	private int formatFeatures(final int format) {
		synchronized (this.lock) {
			Integer f = this.formatFeatures.get(format);
			if (f == null) {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					VkFormatProperties fp = VkFormatProperties.calloc(stack);
					vkGetPhysicalDeviceFormatProperties(this.physicalDevice, format, fp);
					f = fp.optimalTilingFeatures();
					this.formatFeatures.put(format, f);
					this.bufferFormatFeatures.put(format, fp.bufferFeatures());
				}
			}
			return f;
		}
	}

	private int bufferFormatFeatures(final int format) {
		this.formatFeatures(format);
		synchronized (this.lock) {
			return this.bufferFormatFeatures.get(format);
		}
	}

	private VkTextureState createImage(final int componentType, final int componentCount, final int width, final int height, final int mips, final boolean cube,
		final boolean renderTarget, final boolean internal) {
		int format = VkFormats.format(componentType, componentCount);
		if (format == VK_FORMAT_UNDEFINED) {
			throw new IllegalArgumentException("no Vulkan format for component type " + componentType + " x" + componentCount);
		}
		boolean depth = VkFormats.isDepth(componentType);
		int features = this.formatFeatures(format);
		int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		if ((features & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) == 0) {
			throw new IllegalArgumentException("the GPU cannot sample textures of Vulkan format " + format);
		}
		if (depth) {
			if ((features & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) == 0) {
				throw new IllegalArgumentException("the GPU cannot use Vulkan format " + format + " as a depth buffer");
			}
			usage |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
		} else if (renderTarget) {
			if ((features & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) == 0) {
				throw new IllegalArgumentException("the GPU cannot render to Vulkan format " + format);
			}
			usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
		}
		VkTextureState t = new VkTextureState();
		t.format = format;
		t.componentType = componentType;
		t.aspect = depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
		t.width = width;
		t.height = height;
		t.mips = mips;
		t.cube = cube;
		t.layers = cube ? 6 : 1;
		t.layout = new int[mips];
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(mips).arrayLayers(t.layers)
				.samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).flags(cube ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0);
			ici.extent().set(width, height, 1);
			LongBuffer pl = stack.mallocLong(1);
			int rc = vkCreateImage(this.device, ici, null, pl);
			if (rc != VK_SUCCESS) {
				throw new IllegalStateException("vkCreateImage " + width + "x" + height + " failed: " + VkFormats.result(rc));
			}
			t.image = pl.get(0);
			VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
			vkGetImageMemoryRequirements(this.device, t.image, req);
			try {
				t.region = this.memory.allocate(req.size(), Math.max(1L, req.alignment()), req.memoryTypeBits(), false);
			} catch (RuntimeException | Error e) {
				vkDestroyImage(this.device, t.image, null);
				throw e;
			}
			VkFormats.check(vkBindImageMemory(this.device, t.image, t.region.block.memory, t.region.offset), "vkBindImageMemory");
		}
		// The first layout transition is recorded into the frame's setup command buffer, which
		// always executes before the commands that use the texture.
		Arrays.fill(t.layout, RESTING);
		synchronized (this.lock) {
			this.needInit.add(t);
		}
		return t;
	}

	@Override
	public void createTexture(final DxTexture texture) {
		texture.driverData = this.createImage(texture.componentType, texture.componentCount, texture.getWidth(0), texture.getHeight(0), texture.getMipLevels(),
			texture.isCube(), texture.isRenderTarget(), false);
	}

	@Override
	public void destroyTexture(final DxTexture texture) {
		VkTextureState t = (VkTextureState)texture.driverData;
		if (t == null) {
			return;
		}
		texture.driverData = null;
		this.destroyImage(t);
	}

	private void destroyImage(final VkTextureState t) {
		synchronized (this.lock) {
			this.needInit.remove(t);
			this.pendingClears.removeIf(pc -> pc.texture == t);
			if (this.openTransfer == t) {
				this.openTransfer = null;
			}
			// framebuffers that reference any view of this texture go with it
			this.framebuffers.entrySet().removeIf(e -> {
				FbKey k = e.getKey();
				for (int i = 0; i < k.count; i++) {
					if (t.views.containsValue(k.views[i])) {
						vkDestroyFramebuffer(this.device, e.getValue(), null);
						return true;
					}
				}
				return false;
			});
		}
		for (long view : t.views.values()) {
			vkDestroyImageView(this.device, view, null);
		}
		t.views.clear();
		vkDestroyImage(this.device, t.image, null);
		this.memory.release(t.region);
	}

	/** Image view for sampling a mip range, or for rendering to one level. */
	private long view(final VkTextureState t, final int baseMip, final int mipCount, final boolean attachment) {
		int key = baseMip | mipCount << 8 | (attachment ? 1 << 16 : 0);
		Long v = t.views.get(key);
		if (v != null) {
			return v;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			boolean cubeView = t.cube && !attachment;
			VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack).sType$Default().image(t.image).viewType(cubeView ? VK_IMAGE_VIEW_TYPE_CUBE : VK_IMAGE_VIEW_TYPE_2D)
				.format(t.format);
			ci.subresourceRange().set(t.aspect, baseMip, mipCount, 0, cubeView ? 6 : 1);
			LongBuffer pl = stack.mallocLong(1);
			VkFormats.check(vkCreateImageView(this.device, ci, null, pl), "vkCreateImageView");
			t.views.put(key, pl.get(0));
			return pl.get(0);
		}
	}

	private long sampler(final DxSampler s) {
		if (s.driverHandle != 0L) {
			return s.driverHandle;
		}
		long key = (s.minLinear() ? 1 : 0) | (s.magLinear() ? 2 : 0) | (s.clampU() ? 4 : 0) | (s.clampV() ? 8 : 0)
			| (long)Math.min(255, s.getMaxAnisotropy()) << 8 | (long)Float.floatToRawIntBits(s.maxLodValue()) << 32;
		synchronized (this.lock) {
			Long h = this.samplers.get(key);
			if (h == null) {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					boolean aniso = this.featAnisotropy && s.getMaxAnisotropy() > 1;
					VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(s.magLinear() ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
						.minFilter(s.minLinear() ? VK_FILTER_LINEAR : VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
						.addressModeU(s.clampU() ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT)
						.addressModeV(s.clampV() ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT)
						.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).anisotropyEnable(aniso)
						.maxAnisotropy(aniso ? Math.min(this.maxAnisotropy, s.getMaxAnisotropy()) : 1.0F).minLod(0.0F).maxLod(Math.min(VK_LOD_CLAMP_NONE, s.maxLodValue()));
					LongBuffer pl = stack.mallocLong(1);
					VkFormats.check(vkCreateSampler(this.device, ci, null, pl), "vkCreateSampler");
					h = pl.get(0);
					this.samplers.put(key, h);
				}
			}
			s.driverHandle = h;
			return h;
		}
	}

	@Override
	public void destroySampler(final DxSampler sampler) {
		// Sampler objects are shared by state and live until the driver closes.
	}

	// ======================================================================================
	// pipelines
	// ======================================================================================
	private long shaderModule(final DxShader shader) {
		synchronized (this.lock) {
			Long m = this.shaderModules.get(shader.hash);
			if (m != null) {
				this.stats[DxStats.SHADER_CACHE_HITS]++;
				return m;
			}
			this.stats[DxStats.SHADER_CACHE_MISSES]++;
			ByteBuffer code = MemoryUtil.memAlloc(shader.wordCount() * 4);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				shader.copyTo(code);
				VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
				LongBuffer pl = stack.mallocLong(1);
				int rc = vkCreateShaderModule(this.device, ci, null, pl);
				if (rc != VK_SUCCESS) {
					throw new IllegalStateException("vkCreateShaderModule(" + shader.name + ") failed: " + VkFormats.result(rc));
				}
				this.shaderModules.put(shader.hash, pl.get(0));
				return pl.get(0);
			} finally {
				MemoryUtil.memFree(code);
			}
		}
	}

	@Override
	public void createPipeline(final DxPipeline p) throws DxShaderException {
		p.vertex.checkVulkan(this.apiMajor, this.apiMinor, this.featDrawParameters, false, false);
		p.fragment.checkVulkan(this.apiMajor, this.apiMinor, this.featDrawParameters, false, false);
		// Make the descriptor decorations of both stages agree with the pipeline's uniform order.
		DxShader vs = p.vertex.withBindings(p.uniformName, p.uniformKind);
		DxShader fs = p.fragment.withBindings(p.uniformName, p.uniformKind);
		if (p.pushConstantSize > this.maxPushConstants) {
			throw new DxShaderException(p.name + ": needs " + p.pushConstantSize + " bytes of push constants, the device allows " + this.maxPushConstants);
		}
		if (p.wireframe && !this.featWireframe) {
			throw new DxShaderException(p.name + ": wireframe fill mode is not supported by the device");
		}
		if (!this.featIndependentBlend) {
			for (int i = 1; i < p.targetCount; i++) {
				if (p.targetPresent[i] && p.targetPresent[0] && (p.targetBlend[i] != p.targetBlend[0] || p.srcColor[i] != p.srcColor[0] || p.dstColor[i] != p.dstColor[0]
					|| p.opColor[i] != p.opColor[0] || p.srcAlpha[i] != p.srcAlpha[0] || p.dstAlpha[i] != p.dstAlpha[0] || p.opAlpha[i] != p.opAlpha[0]
					|| p.targetMask[i] != p.targetMask[0])) {
					throw new DxShaderException(p.name + ": different blend state per color target needs the independentBlend feature, which the device lacks");
				}
			}
		}
		VkPipelineState ps = new VkPipelineState();
		ps.count = p.uniformCount;
		ps.pushSize = p.pushConstantSize;
		ps.kind = new int[ps.count];
		ps.type = new int[ps.count];
		ps.dynamic = new boolean[ps.count];
		// vertex formats, checked against what the device accepts in vertex buffers
		ps.attribFormat = new int[p.attribCount];
		for (int i = 0; i < p.attribCount; i++) {
			int f = VkFormats.format(p.attribType[i], p.attribComps[i]);
			if (f != VK_FORMAT_UNDEFINED && (this.bufferFormatFeatures(f) & VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT) == 0 && p.attribComps[i] == 3) {
				// three component formats are optional; the element is padded to four bytes/words, so read the padding too
				f = VkFormats.format(p.attribType[i], 4);
			}
			if (f == VK_FORMAT_UNDEFINED || (this.bufferFormatFeatures(f) & VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT) == 0) {
				throw new DxShaderException(p.name + ": vertex attribute " + i + " uses a format the device does not accept in vertex buffers");
			}
			ps.attribFormat[i] = f;
		}
		int dynUbo = 0, dynSsbo = 0;
		for (int i = 0; i < ps.count; i++) {
			int kind = p.uniformKind[i];
			if (kind == DxDescriptorSet.UNIFORM_BUFFER) {
				// a block the shaders declare as a storage block must be bound as one
				for (DxShader s : new DxShader[]{vs, fs}) {
					for (DxShader.Binding b : s.bindings) {
						if (b.kind == DxDescriptorSet.STORAGE_BUFFER && b.name.equals(p.uniformName[i])) {
							kind = DxDescriptorSet.STORAGE_BUFFER;
						}
					}
				}
			}
			ps.kind[i] = kind;
			switch (kind) {
				case DxDescriptorSet.UNIFORM_BUFFER -> {
					ps.dynamic[i] = dynUbo < this.maxDynamicUbo;
					dynUbo += ps.dynamic[i] ? 1 : 0;
					ps.type[i] = ps.dynamic[i] ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
				}
				case DxDescriptorSet.STORAGE_BUFFER -> {
					ps.dynamic[i] = dynSsbo < this.maxDynamicSsbo;
					dynSsbo += ps.dynamic[i] ? 1 : 0;
					ps.type[i] = ps.dynamic[i] ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
				}
				case DxDescriptorSet.SAMPLER -> ps.type[i] = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
				case DxDescriptorSet.TEXEL_BUFFER -> {
					int f = VkFormats.format(p.uniformType[i], p.uniformComps[i]);
					if (f == VK_FORMAT_UNDEFINED || (this.bufferFormatFeatures(f) & VK_FORMAT_FEATURE_UNIFORM_TEXEL_BUFFER_BIT) == 0) {
						throw new DxShaderException(p.name + ": texel buffer '" + p.uniformName[i] + "' uses a format the device does not support for texel buffers");
					}
					ps.type[i] = VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
				}
				default -> throw new DxShaderException(p.name + ": uniform '" + p.uniformName[i] + "' has no kind");
			}
			ps.hasStatic |= (kind == DxDescriptorSet.UNIFORM_BUFFER || kind == DxDescriptorSet.STORAGE_BUFFER) && !ps.dynamic[i];
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ps.vsModule = this.shaderModule(vs);
			ps.fsModule = this.shaderModule(fs);
			LongBuffer pl = stack.mallocLong(1);
			VkDescriptorSetLayoutCreateInfo lci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
			if (ps.count > 0) {
				VkDescriptorSetLayoutBinding.Buffer lb = VkDescriptorSetLayoutBinding.calloc(ps.count, stack);
				for (int i = 0; i < ps.count; i++) {
					lb.get(i).binding(i).descriptorType(ps.type[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
				}
				lci.pBindings(lb);
			}
			VkFormats.check(vkCreateDescriptorSetLayout(this.device, lci, null, pl), "vkCreateDescriptorSetLayout");
			ps.dsl = pl.get(0);
			VkPipelineLayoutCreateInfo plc = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(ps.dsl));
			if (ps.pushSize > 0) {
				VkPushConstantRange.Buffer pr = VkPushConstantRange.calloc(1, stack);
				pr.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size((ps.pushSize + 3) & ~3);
				plc.pPushConstantRanges(pr);
			}
			VkFormats.check(vkCreatePipelineLayout(this.device, plc, null, pl), "vkCreatePipelineLayout");
			ps.layout = pl.get(0);
		} catch (IllegalStateException e) {
			throw new DxShaderException(p.name + ": " + e.getMessage());
		}
		p.driverData = ps;
		// Asynchronous preparation: this runs on the game's compile executor, so build the variant
		// for the most likely render target now instead of on the render thread at first use.
		PassKey guess = new PassKey();
		guess.count = Math.max(1, p.targetCount);
		for (int i = 0; i < guess.count; i++) {
			guess.format[i] = VK_FORMAT_R8G8B8A8_UNORM;
			guess.load[i] = i < p.targetCount && !p.targetPresent[i] ? 0 : 1;
		}
		if (p.depthTest) {
			guess.depthFormat = VK_FORMAT_D32_SFLOAT;
			guess.depthLoad = 1;
		}
		try {
			this.variant(p, ps, this.renderPass(guess));
		} catch (IllegalStateException e) {
			this.destroyPipelineState(ps);
			p.driverData = null;
			throw new DxShaderException(p.name + ": " + e.getMessage());
		}
	}

	private void destroyPipelineState(final VkPipelineState ps) {
		synchronized (ps) {
			for (long v : ps.variants.values()) {
				vkDestroyPipeline(this.device, v, null);
			}
			ps.variants.clear();
		}
		if (ps.layout != 0L) {
			vkDestroyPipelineLayout(this.device, ps.layout, null);
		}
		if (ps.dsl != 0L) {
			vkDestroyDescriptorSetLayout(this.device, ps.dsl, null);
		}
	}

	@Override
	public void destroyPipeline(final DxPipeline pipeline) {
		VkPipelineState ps = (VkPipelineState)pipeline.driverData;
		if (ps != null) {
			pipeline.driverData = null;
			this.destroyPipelineState(ps);
		}
	}

	private static int vkCompare(final int f) {
		return switch (f) {
			case 1 -> VK_COMPARE_OP_LESS;
			case 2 -> VK_COMPARE_OP_LESS_OR_EQUAL;
			case 3 -> VK_COMPARE_OP_EQUAL;
			case 4 -> VK_COMPARE_OP_NOT_EQUAL;
			case 5 -> VK_COMPARE_OP_GREATER_OR_EQUAL;
			case 6 -> VK_COMPARE_OP_GREATER;
			case 7 -> VK_COMPARE_OP_NEVER;
			default -> VK_COMPARE_OP_ALWAYS;
		};
	}

	private static int vkFactor(final int f) {
		return switch (f) {
			case 0 -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
			case 1 -> VK_BLEND_FACTOR_CONSTANT_COLOR;
			case 2 -> VK_BLEND_FACTOR_DST_ALPHA;
			case 3 -> VK_BLEND_FACTOR_DST_COLOR;
			case 4 -> VK_BLEND_FACTOR_ONE;
			case 5 -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
			case 6 -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
			case 7 -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
			case 8 -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
			case 9 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
			case 10 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
			case 11 -> VK_BLEND_FACTOR_SRC_ALPHA;
			case 12 -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
			case 13 -> VK_BLEND_FACTOR_SRC_COLOR;
			default -> VK_BLEND_FACTOR_ZERO;
		};
	}

	private static int vkTopology(final int t) {
		return switch (t) {
			case 1 -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
			case 2 -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
			case 3 -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
			case 4 -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
			case 5 -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
			default -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
		};
	}

	/** The VkPipeline of p for render passes shaped like pass; compiled on first use. */
	private long variant(final DxPipeline p, final VkPipelineState ps, final PassEntry pass) {
		synchronized (ps) {
			Long existing = ps.variants.get(pass.compatId);
			if (existing != null) {
				return existing;
			}
			long t0 = System.nanoTime();
			PassKey key = pass.key;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
				stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(ps.vsModule).pName(this.entryMain);
				stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(ps.fsModule).pName(this.entryMain);
				VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
				if (p.vbCount > 0) {
					VkVertexInputBindingDescription.Buffer vbd = VkVertexInputBindingDescription.calloc(p.vbCount, stack);
					for (int i = 0; i < p.vbCount; i++) {
						vbd.get(i).binding(p.vbSlot[i]).stride(p.vbStride[i]).inputRate(p.vbStepRate[i] != 0 ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);
					}
					vi.pVertexBindingDescriptions(vbd);
				}
				if (p.attribCount > 0) {
					VkVertexInputAttributeDescription.Buffer vad = VkVertexInputAttributeDescription.calloc(p.attribCount, stack);
					for (int i = 0; i < p.attribCount; i++) {
						vad.get(i).location(p.attribLocation[i]).binding(p.attribSlot[i]).format(ps.attribFormat[i]).offset(p.attribOffset[i]);
					}
					vi.pVertexAttributeDescriptions(vad);
				}
				VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(vkTopology(p.topology));
				VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
				boolean bias = p.depthBiasScale != 0.0F || p.depthBiasConst != 0.0F;
				VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
					.polygonMode(p.wireframe ? VK_POLYGON_MODE_LINE : VK_POLYGON_MODE_FILL).cullMode(p.cull ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
					.frontFace(VK_FRONT_FACE_CLOCKWISE).depthBiasEnable(bias).depthBiasConstantFactor(p.depthBiasConst).depthBiasSlopeFactor(p.depthBiasScale).lineWidth(1.0F);
				VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
				VkPipelineDepthStencilStateCreateInfo ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default().depthTestEnable(p.depthTest)
					.depthWriteEnable(p.depthWrite).depthCompareOp(vkCompare(p.depthFunc)).minDepthBounds(0.0F).maxDepthBounds(1.0F);
				VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
				if (key.count > 0) {
					VkPipelineColorBlendAttachmentState.Buffer att = VkPipelineColorBlendAttachmentState.calloc(key.count, stack);
					for (int i = 0; i < key.count; i++) {
						VkPipelineColorBlendAttachmentState a = att.get(i);
						boolean written = (p.fragment.outputLocations & 1 << i) != 0;
						if (i < p.targetCount && p.targetPresent[i] && written) {
							a.colorWriteMask(p.targetMask[i] & 15).blendEnable(p.targetBlend[i]);
							if (p.targetBlend[i]) {
								a.srcColorBlendFactor(vkFactor(p.srcColor[i])).dstColorBlendFactor(vkFactor(p.dstColor[i])).colorBlendOp(p.opColor[i])
									.srcAlphaBlendFactor(vkFactor(p.srcAlpha[i])).dstAlphaBlendFactor(vkFactor(p.dstAlpha[i])).alphaBlendOp(p.opAlpha[i]);
							}
						} else {
							// a target the fragment shader never writes keeps its contents (the CPU backend behaves the same)
							a.colorWriteMask(0);
						}
					}
					cb.attachmentCount(key.count).pAttachments(att);
					if (Boolean.getBoolean("dxrender.debugTargets") && key.count > 1) {
						StringBuilder sb = new StringBuilder();
						for (int i = 0; i < key.count; i++) {
							sb.append(i).append(":load").append(key.load[i]).append("/mask").append(att.get(i).colorWriteMask()).append(' ');
						}
						DxRender.LOGGER.info("[targets] {} targets={} outputs=0x{} {}", p.name, p.targetCount, Integer.toHexString(p.fragment.outputLocations), sb);
					}
				}
				VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
					.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
				VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
				ci.get(0).sType$Default().pStages(stages).pVertexInputState(vi).pInputAssemblyState(ia).pViewportState(vp).pRasterizationState(rs).pMultisampleState(ms)
					.pDepthStencilState(ds).pColorBlendState(cb).pDynamicState(dyn).layout(ps.layout).renderPass(pass.renderPass).subpass(0);
				LongBuffer pl = stack.mallocLong(1);
				int rc = vkCreateGraphicsPipelines(this.device, this.pipelineCache, ci, null, pl);
				if (rc != VK_SUCCESS) {
					throw new IllegalStateException("vkCreateGraphicsPipelines failed: " + VkFormats.result(rc));
				}
				ps.variants.put(pass.compatId, pl.get(0));
				synchronized (this.lock) {
					this.stats[DxStats.NS_PIPELINE_COMPILE] += System.nanoTime() - t0;
					this.stats[DxStats.PIPELINES_COMPILED]++;
					this.pipelinesSinceSave++;
				}
				return pl.get(0);
			}
		}
	}

	// ======================================================================================
	// render passes and framebuffers
	// ======================================================================================
	private PassEntry renderPass(final PassKey lookup) {
		synchronized (this.lock) {
			PassEntry e = this.renderPasses.get(lookup);
			if (e != null) {
				return e;
			}
			PassKey key = lookup.copy();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				int used = 0;
				for (int i = 0; i < key.count; i++) {
					used += key.load[i] != 0 ? 1 : 0;
				}
				boolean depth = key.depthFormat != 0;
				int total = used + (depth ? 1 : 0);
				VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(Math.max(1, total), stack);
				VkAttachmentReference.Buffer refs = VkAttachmentReference.calloc(Math.max(1, key.count), stack);
				int a = 0;
				for (int i = 0; i < key.count; i++) {
					if (key.load[i] == 0) {
						refs.get(i).attachment(VK_ATTACHMENT_UNUSED).layout(VK_IMAGE_LAYOUT_UNDEFINED);
						continue;
					}
					// Load only when the previous contents are needed; always keep the result (it is sampled or presented later).
					att.get(a).format(key.format[i]).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(key.load[i] == 2 ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
						.storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
						.initialLayout(RESTING).finalLayout(RESTING);
					refs.get(i).attachment(a).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
					a++;
				}
				VkSubpassDescription.Buffer sub = VkSubpassDescription.calloc(1, stack);
				sub.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(key.count);
				if (key.count > 0) {
					sub.get(0).pColorAttachments(refs);
				}
				if (depth) {
					att.get(a).format(key.depthFormat).samples(VK_SAMPLE_COUNT_1_BIT)
						.loadOp(key.depthLoad == 2 ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
						.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(RESTING).finalLayout(RESTING);
					VkAttachmentReference dref = VkAttachmentReference.calloc(stack).attachment(a).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
					sub.get(0).pDepthStencilAttachment(dref);
				}
				int attStages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
				int attAccess = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
					| VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
				int anyStages = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT | attStages;
				int anyAccess = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | attAccess;
				VkSubpassDependency.Buffer deps = VkSubpassDependency.calloc(2, stack);
				deps.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0).srcStageMask(anyStages).dstStageMask(attStages).srcAccessMask(anyAccess).dstAccessMask(attAccess);
				deps.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL).srcStageMask(attStages).dstStageMask(anyStages).srcAccessMask(attAccess).dstAccessMask(anyAccess);
				VkRenderPassCreateInfo ci = VkRenderPassCreateInfo.calloc(stack).sType$Default().pSubpasses(sub).pDependencies(deps);
				if (total > 0) {
					att.limit(total);
					ci.pAttachments(att);
				}
				LongBuffer pl = stack.mallocLong(1);
				VkFormats.check(vkCreateRenderPass(this.device, ci, null, pl), "vkCreateRenderPass");
				e = new PassEntry();
				e.renderPass = pl.get(0);
				e.key = key;
				String compat = key.compat();
				Integer id = this.compatIds.get(compat);
				if (id == null) {
					id = this.compatIds.size() + 1;
					this.compatIds.put(compat, id);
				}
				e.compatId = id;
				this.renderPasses.put(key, e);
				return e;
			}
		}
	}

	private long framebuffer(final FbKey lookup) {
		synchronized (this.lock) {
			Long fb = this.framebuffers.get(lookup);
			if (fb != null) {
				return fb;
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				LongBuffer views = stack.mallocLong(Math.max(1, lookup.count));
				for (int i = 0; i < lookup.count; i++) {
					views.put(i, lookup.views[i]);
				}
				views.limit(lookup.count);
				VkFramebufferCreateInfo ci = VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(lookup.renderPass).attachmentCount(lookup.count)
					.pAttachments(views).width(lookup.width).height(lookup.height).layers(1);
				LongBuffer pl = stack.mallocLong(1);
				VkFormats.check(vkCreateFramebuffer(this.device, ci, null, pl), "vkCreateFramebuffer");
				this.framebuffers.put(lookup.copy(), pl.get(0));
				return pl.get(0);
			}
		}
	}

	// ======================================================================================
	// frames and synchronisation
	// ======================================================================================
	int currentSlot() {
		return (int)(this.frameIndex % FRAMES);
	}

	private VkCommandBuffer cb() {
		if (!this.frameBegun) {
			this.beginFrame();
		}
		return this.frame.main;
	}

	private void beginFrame() {
		Frame f = this.frames[this.currentSlot()];
		if (f.submitted) {
			// CPU/GPU synchronisation point: at most FRAMES frames are ever in flight.
			VkFormats.check(vkWaitForFences(this.device, f.fence, true, -1L), "vkWaitForFences");
			this.completedThrough = Math.max(this.completedThrough, f.number);
			f.submitted = false;
			if (this.queryPool != 0L) {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					LongBuffer t = stack.mallocLong(2);
					if (vkGetQueryPoolResults(this.device, this.queryPool, this.currentSlot() * 2, 2, t, 8L, VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
						long ticks = t.get(1) - t.get(0);
						if (ticks > 0) {
							this.stats[DxStats.NS_GPU] += (long)(ticks * (double)this.timestampPeriod);
						}
					}
				}
			}
		}
		VkFormats.check(vkResetFences(this.device, f.fence), "vkResetFences");
		VkFormats.check(vkResetCommandPool(this.device, f.pool, 0), "vkResetCommandPool"); // command buffers are reused every other frame
		for (long pool : f.descriptorPools) {
			vkResetDescriptorPool(this.device, pool, 0);
		}
		f.poolCursor = 0;
		f.descCache.clear();
		for (long[] v : f.bufferViews) {
			vkDestroyBufferView(this.device, v[4], null);
		}
		f.bufferViews.clear();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			VkFormats.check(vkBeginCommandBuffer(f.setup, bi), "vkBeginCommandBuffer");
			VkFormats.check(vkBeginCommandBuffer(f.main, bi), "vkBeginCommandBuffer");
		}
		if (this.queryPool != 0L) {
			vkCmdResetQueryPool(f.main, this.queryPool, this.currentSlot() * 2, 2);
			vkCmdWriteTimestamp(f.main, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, this.queryPool, this.currentSlot() * 2);
		}
		this.frame = f;
		this.frameBegun = true;
		this.resetBoundState();
	}

	private void resetBoundState() {
		this.boundPipeline = 0L;
		this.boundDsl = 0L;
		this.boundLayout = 0L;
		this.boundPushLayout = 0L;
		this.boundVbPipeline = null;
		this.curSet = 0L;
		this.vbDirty = true;
		this.ibDirty = true;
		this.scissorDirty = true;
		this.bindings.resourcesDirty = true;
		this.bindings.offsetsDirty = true;
	}

	@Override
	public void endFrame() {
		VkCommandBuffer main = this.cb();
		this.endOpenWork();
		this.drainTextureInit();
		Frame f = this.frame;
		if (this.queryPool != 0L) {
			vkCmdWriteTimestamp(main, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, this.queryPool, this.currentSlot() * 2 + 1);
		}
		VkFormats.check(vkEndCommandBuffer(f.setup), "vkEndCommandBuffer");
		VkFormats.check(vkEndCommandBuffer(f.main), "vkEndCommandBuffer");
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(f.setup.address(), f.main.address()));
			VkSwapchain sc = this.pendingPresent;
			if (sc != null) {
				si.waitSemaphoreCount(1).pWaitSemaphores(stack.longs(sc.imageAvailable(this.currentSlot()))).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
					.pSignalSemaphores(stack.longs(sc.renderFinished(this.currentSlot())));
				sc.submitted(this.currentSlot());
				this.pendingPresent = null;
			}
			int rc = vkQueueSubmit(this.queue, si, f.fence);
			if (rc != VK_SUCCESS) {
				throw new IllegalStateException("vkQueueSubmit failed: " + VkFormats.result(rc) + (rc == VK_ERROR_DEVICE_LOST ? " (the GPU driver reset or crashed)" : ""));
			}
		}
		f.submitted = true;
		f.number = this.frameIndex;
		this.frameIndex++;
		this.frameBegun = false;
		this.stats[DxStats.FRAMES]++;
		if (this.pipelinesSinceSave > 0 && (this.frameIndex & 1023) == 0) {
			this.savePipelineCache();
		}
	}

	@Override
	public long frameIndex() {
		return this.frameIndex;
	}

	@Override
	public boolean awaitFrame(final long frameNumber, final long timeoutNs) {
		if (frameNumber <= this.completedThrough) {
			return true;
		}
		if (frameNumber >= this.frameIndex) {
			return false; // still being recorded
		}
		Frame f = this.frames[(int)(frameNumber % FRAMES)];
		if (!f.submitted || f.number != frameNumber) {
			return true;
		}
		int rc = vkWaitForFences(this.device, f.fence, true, timeoutNs < 0 ? -1L : timeoutNs);
		if (rc == VK_SUCCESS) {
			this.completedThrough = Math.max(this.completedThrough, frameNumber);
			return true;
		}
		return false;
	}

	private void savePipelineCache() {
		if (this.pipelineCacheFile == null) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer size = stack.mallocPointer(1);
			if (vkGetPipelineCacheData(this.device, this.pipelineCache, size, null) != VK_SUCCESS || size.get(0) <= 0 || size.get(0) > (64L << 20)) {
				return;
			}
			ByteBuffer data = MemoryUtil.memAlloc((int)size.get(0));
			try {
				if (vkGetPipelineCacheData(this.device, this.pipelineCache, size, data) == VK_SUCCESS) {
					byte[] bytes = new byte[(int)size.get(0)];
					data.get(bytes);
					Files.createDirectories(this.pipelineCacheFile.getParent());
					Files.write(this.pipelineCacheFile, bytes);
					this.pipelinesSinceSave = 0;
				}
			} finally {
				MemoryUtil.memFree(data);
			}
		} catch (Throwable t) {
			DxRender.LOGGER.warn("could not save the Vulkan pipeline cache", t);
			this.pipelineCacheFile = null;
		}
	}

	// ======================================================================================
	// layout tracking and helpers used while translating
	// ======================================================================================
	private static int accessFor(final int layout) {
		return switch (layout) {
			case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK_ACCESS_SHADER_READ_BIT;
			case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK_ACCESS_TRANSFER_WRITE_BIT;
			case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK_ACCESS_TRANSFER_READ_BIT;
			default -> 0;
		};
	}

	private static int stageFor(final int layout) {
		return switch (layout) {
			case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
			case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK_PIPELINE_STAGE_TRANSFER_BIT;
			default -> VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
		};
	}

	private void imageBarrier(final VkCommandBuffer cb, final long image, final int aspect, final int baseMip, final int mipCount, final int layers, final int oldLayout,
		final int newLayout, final int srcAccess, final int dstAccess, final int srcStage, final int dstStage) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
			b.get(0).sType$Default().oldLayout(oldLayout).newLayout(newLayout).srcAccessMask(srcAccess).dstAccessMask(dstAccess)
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
			b.get(0).subresourceRange().set(aspect, baseMip, mipCount, 0, layers);
			vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, b);
		}
	}

	private void transition(final VkTextureState t, final int mip, final int newLayout) {
		int old = t.layout[mip];
		if (old == newLayout) {
			return;
		}
		this.imageBarrier(this.cb(), t.image, t.aspect, mip, 1, t.layers, old, newLayout, accessFor(old), accessFor(newLayout), stageFor(old), stageFor(newLayout));
		t.layout[mip] = newLayout;
	}

	private void drainTextureInit() {
		synchronized (this.lock) {
			if (this.needInit.isEmpty()) {
				return;
			}
			if (!this.frameBegun) {
				this.beginFrame();
			}
			for (VkTextureState t : this.needInit) {
				this.imageBarrier(this.frame.setup, t.image, t.aspect, 0, t.mips, t.layers, VK_IMAGE_LAYOUT_UNDEFINED, RESTING, 0, VK_ACCESS_SHADER_READ_BIT,
					VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
			}
			this.needInit.clear();
		}
	}

	private void closeTransfer() {
		if (this.openTransfer != null) {
			this.transition(this.openTransfer, this.openTransferMip, RESTING);
			this.openTransfer = null;
		}
	}

	private void openTransferDst(final VkTextureState t, final int mip) {
		if (this.openTransfer == t && this.openTransferMip == mip) {
			return;
		}
		this.closeTransfer();
		this.transition(t, mip, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		this.openTransfer = t;
		this.openTransferMip = mip;
	}

	/** After transfers wrote buffers, later draws must see them. */
	private void bufferBarrier() {
		if (!this.bufferWritesPending) {
			return;
		}
		this.bufferWritesPending = false;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack);
			mb.get(0).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT
				| VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);
			vkCmdPipelineBarrier(this.cb(), VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
				| VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, 0, mb, null, null);
		}
	}

	private PendingClear takePending(final VkTextureState t, final int mip) {
		for (int i = 0; i < this.pendingClears.size(); i++) {
			PendingClear pc = this.pendingClears.get(i);
			if (pc.texture == t && pc.mip == mip) {
				this.pendingClears.remove(i);
				return pc;
			}
		}
		return null;
	}

	private void materialize(final PendingClear pc) {
		VkTextureState t = pc.texture;
		this.closeTransfer();
		this.transition(t, pc.mip, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack).set(t.aspect, pc.mip, 1, 0, t.layers);
			if (t.aspect == VK_IMAGE_ASPECT_DEPTH_BIT) {
				vkCmdClearDepthStencilImage(this.cb(), t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VkClearDepthStencilValue.calloc(stack).depth(pc.r), range);
			} else {
				vkCmdClearColorImage(this.cb(), t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, this.clearColor(stack, t, pc.r, pc.g, pc.b, pc.a), range);
			}
		}
		this.transition(t, pc.mip, RESTING);
	}

	private VkClearColorValue clearColor(final MemoryStack stack, final VkTextureState t, final float r, final float g, final float b, final float a) {
		VkClearColorValue v = VkClearColorValue.calloc(stack);
		if (t.componentType >= DxNative.CT_UINT8 && t.componentType <= DxNative.CT_SINT32) {
			v.int32(0, (int)r).int32(1, (int)g).int32(2, (int)b).int32(3, (int)a);
		} else {
			v.float32(0, r).float32(1, g).float32(2, b).float32(3, a);
		}
		return v;
	}

	private void materializeAll() {
		while (!this.pendingClears.isEmpty()) {
			this.materialize(this.pendingClears.remove(this.pendingClears.size() - 1));
		}
	}

	/** Brings the command stream to a state where anything may follow. */
	private void endOpenWork() {
		if (this.inPass) {
			vkCmdEndRenderPass(this.cb());
			this.inPass = false;
		}
		this.closeTransfer();
		this.materializeAll();
	}

	private static VkTextureState state(final DxTexture texture) {
		return (VkTextureState)texture.driverData;
	}

	// ======================================================================================
	// the Command Processor: DX Render commands -> Vulkan
	// ======================================================================================
	@Override
	public void execute(final DxCommandBuffer commands) {
		this.drainTextureInit();
		final long[] d = commands.data();
		final Object[] r = commands.refs();
		final int n = commands.size();
		int p = 0;
		while (p < n) {
			int op = (int)d[p++];
			switch (op) {
				case DxCommandBuffer.OP_BEGIN_PASS -> this.beginPass((DxFramebuffer)r[(int)d[p++]]);
				case DxCommandBuffer.OP_END_PASS -> {
					if (this.inPass) {
						vkCmdEndRenderPass(this.cb());
						this.inPass = false;
					}
				}
				case DxCommandBuffer.OP_SET_PIPELINE -> {
					DxPipeline pl = (DxPipeline)r[(int)d[p++]];
					if (pl != this.curPipeline) {
						this.curPipeline = pl;
						this.bindings.clear();
					}
				}
				case DxCommandBuffer.OP_SET_UNIFORM_BUFFER -> {
					this.bindings.setBuffer((int)d[p], (DxBuffer)r[(int)d[p + 1]], d[p + 2], d[p + 3]);
					p += 4;
				}
				case DxCommandBuffer.OP_SET_UNIFORM_TEXTURE -> {
					this.bindings.setTexture((int)d[p], (DxTextureView)r[(int)d[p + 1]], (DxSampler)r[(int)d[p + 2]]);
					p += 3;
				}
				case DxCommandBuffer.OP_SET_UNIFORM_NONE -> this.bindings.setNone((int)d[p++]);
				case DxCommandBuffer.OP_PUSH_CONSTANTS -> {
					int bytes = (int)d[p++];
					int words = (bytes + 7) / 8;
					this.pushBytes = Math.min(256, bytes);
					for (int i = 0; i < words && i < 32; i++) {
						this.pushData.putLong(i * 8, d[p + i]);
					}
					this.pushDirty = true;
					p += words;
				}
				case DxCommandBuffer.OP_SET_SCISSOR -> {
					this.scissorEnabled = d[p] != 0;
					this.scissorX = (int)d[p + 1];
					this.scissorY = (int)d[p + 2];
					this.scissorW = (int)d[p + 3];
					this.scissorH = (int)d[p + 4];
					this.scissorDirty = true;
					p += 5;
				}
				case DxCommandBuffer.OP_SET_VERTEX_BUFFER -> {
					int slot = (int)d[p];
					if (slot >= 0 && slot < 8) {
						this.vb[slot] = d[p + 1] < 0 ? null : (DxBuffer)r[(int)d[p + 1]];
						this.vbOffset[slot] = d[p + 2];
						this.vbDirty = true;
					}
					p += 3;
				}
				case DxCommandBuffer.OP_SET_INDEX_BUFFER -> {
					this.ib = d[p] < 0 ? null : (DxBuffer)r[(int)d[p]];
					this.ibBytes = (int)d[p + 1];
					this.ibDirty = true;
					p += 2;
				}
				case DxCommandBuffer.OP_DRAW -> {
					if (this.prepareDraw(false)) {
						int count = (int)d[p + 1], inst = Math.max(1, (int)d[p + 2]);
						vkCmdDraw(this.frame.main, count, inst, (int)d[p], (int)d[p + 3]);
						this.countDraw(count, inst);
					}
					p += 4;
				}
				case DxCommandBuffer.OP_DRAW_INDEXED -> {
					if (this.prepareDraw(true)) {
						int count = (int)d[p + 1], inst = Math.max(1, (int)d[p + 3]);
						vkCmdDrawIndexed(this.frame.main, count, inst, (int)d[p], (int)d[p + 2], (int)d[p + 4]);
						this.countDraw(count, inst);
					}
					p += 5;
				}
				case DxCommandBuffer.OP_DRAW_INDIRECT, DxCommandBuffer.OP_DRAW_INDEXED_INDIRECT -> {
					boolean indexed = op == DxCommandBuffer.OP_DRAW_INDEXED_INDIRECT;
					DxBuffer buf = (DxBuffer)r[(int)d[p]];
					long offset = d[p + 1];
					int drawCount = (int)d[p + 2];
					p += 3;
					if (drawCount > 0 && this.prepareDraw(indexed)) {
						int stride = indexed ? 20 : 16;
						if (drawCount == 1 || this.featMultiDrawIndirect) {
							if (indexed) {
								vkCmdDrawIndexedIndirect(this.frame.main, buf.handle(), offset, drawCount, stride);
							} else {
								vkCmdDrawIndirect(this.frame.main, buf.handle(), offset, drawCount, stride);
							}
						} else {
							for (int i = 0; i < drawCount; i++) {
								if (indexed) {
									vkCmdDrawIndexedIndirect(this.frame.main, buf.handle(), offset + (long)i * stride, 1, stride);
								} else {
									vkCmdDrawIndirect(this.frame.main, buf.handle(), offset + (long)i * stride, 1, stride);
								}
							}
						}
						// the commands live in mapped memory, so the statistics can read them
						long addr = buf.address() + offset;
						for (int i = 0; i < drawCount; i++) {
							this.countDraw(MemoryUtil.memGetInt(addr + (long)i * stride), Math.max(1, MemoryUtil.memGetInt(addr + (long)i * stride + 4)));
						}
						this.stats[DxStats.DRAW_CALLS] -= drawCount - 1; // one API call
					}
				}
				case DxCommandBuffer.OP_CLEAR_COLOR -> {
					this.clear((DxTexture)r[(int)d[p]], (int)d[p + 1], Float.intBitsToFloat((int)d[p + 2]), Float.intBitsToFloat((int)d[p + 3]),
						Float.intBitsToFloat((int)d[p + 4]), Float.intBitsToFloat((int)d[p + 5]), (int)d[p + 6], (int)d[p + 7], (int)d[p + 8], (int)d[p + 9]);
					p += 10;
				}
				case DxCommandBuffer.OP_CLEAR_DEPTH -> {
					this.clear((DxTexture)r[(int)d[p]], (int)d[p + 1], Float.intBitsToFloat((int)d[p + 2]), 0.0F, 0.0F, 0.0F, (int)d[p + 3], (int)d[p + 4], (int)d[p + 5],
						(int)d[p + 6]);
					p += 7;
				}
				case DxCommandBuffer.OP_COPY_BUFFER -> {
					this.copyBuffer((DxBuffer)r[(int)d[p]], d[p + 1], (DxBuffer)r[(int)d[p + 2]], d[p + 3], d[p + 4]);
					p += 5;
				}
				case DxCommandBuffer.OP_COPY_BUFFER_TO_TEXTURE -> {
					this.copyBufferToTexture((DxBuffer)r[(int)d[p]], d[p + 1], (int)d[p + 2], (DxTexture)r[(int)d[p + 3]], (int)d[p + 4], (int)d[p + 5], (int)d[p + 6],
						(int)d[p + 7], (int)d[p + 8], (int)d[p + 9]);
					p += 10;
				}
				case DxCommandBuffer.OP_COPY_TEXTURE_TO_BUFFER -> {
					this.copyTextureToBuffer((DxTexture)r[(int)d[p]], (int)d[p + 1], (int)d[p + 2], (int)d[p + 3], (int)d[p + 4], (int)d[p + 5], (DxBuffer)r[(int)d[p + 6]],
						d[p + 7]);
					p += 8;
				}
				case DxCommandBuffer.OP_COPY_TEXTURE_TO_TEXTURE -> {
					this.copyTextureToTexture((DxTexture)r[(int)d[p]], (DxTexture)r[(int)d[p + 1]], (int)d[p + 2], (int)d[p + 3], (int)d[p + 4], (int)d[p + 5], (int)d[p + 6],
						(int)d[p + 7], (int)d[p + 8]);
					p += 9;
				}
				default -> throw new IllegalStateException("corrupt DX Render command stream (opcode " + op + ")");
			}
		}
	}

	private void countDraw(final int count, final int instances) {
		this.stats[DxStats.DRAW_CALLS]++;
		this.stats[DxStats.VERTICES] += (long)count * instances;
		if (this.curPipeline != null && this.curPipeline.topology == 0) {
			this.stats[DxStats.TRIS_IN] += (long)(count / 3) * instances;
		}
	}

	private void beginPass(final DxFramebuffer fb) {
		if (this.inPass) {
			vkCmdEndRenderPass(this.cb());
			this.inPass = false;
		}
		this.closeTransfer();
		int w = fb.targetWidth(), h = fb.targetHeight();
		boolean whole = fb.x <= 0 && fb.y <= 0 && fb.x + fb.width >= w && fb.y + fb.height >= h;
		PassKey key = this.scratchKey;
		FbKey fk = this.scratchFb;
		key.count = fb.colorCount;
		key.depthFormat = 0;
		key.depthLoad = 0;
		fk.count = 0;
		float[] cv = new float[36];
		int clearCount = 0;
		for (int i = 0; i < fb.colorCount; i++) {
			DxTextureView view = fb.color[i];
			if (view == null) {
				key.format[i] = 0;
				key.load[i] = 0;
				continue;
			}
			VkTextureState t = state(view.dxTexture());
			int mip = view.baseMipLevel();
			boolean clear = (fb.clearColorMask >> i & 1) != 0;
			float r = fb.clearColor[i * 4], g = fb.clearColor[i * 4 + 1], b = fb.clearColor[i * 4 + 2], a = fb.clearColor[i * 4 + 3];
			PendingClear pc = this.takePending(t, mip);
			if (pc != null) {
				if (!whole) {
					this.materialize(pc);
				} else if (!clear) {
					// A whole-level clear issued just before the pass becomes the pass's load operation:
					// a tile based GPU then never reads the old contents back from memory.
					clear = true;
					r = pc.r; g = pc.g; b = pc.b; a = pc.a;
				}
			}
			key.format[i] = t.format;
			key.load[i] = clear ? 2 : 1;
			cv[clearCount * 4] = r; cv[clearCount * 4 + 1] = g; cv[clearCount * 4 + 2] = b; cv[clearCount * 4 + 3] = a;
			clearCount++;
			fk.views[fk.count++] = this.view(t, mip, 1, true);
		}
		VkTextureState depthState = null;
		if (fb.depth != null) {
			depthState = state(fb.depth.dxTexture());
			int mip = fb.depth.baseMipLevel();
			boolean clear = fb.clearDepth;
			float dv = fb.clearDepthValue;
			PendingClear pc = this.takePending(depthState, mip);
			if (pc != null) {
				if (!whole) {
					this.materialize(pc);
				} else if (!clear) {
					clear = true;
					dv = pc.r;
				}
			}
			key.depthFormat = depthState.format;
			key.depthLoad = clear ? 2 : 1;
			cv[clearCount * 4] = dv;
			clearCount++;
			fk.views[fk.count++] = this.view(depthState, mip, 1, true);
		}
		if (fk.count == 0) {
			return;
		}
		this.materializeAll();
		this.bufferBarrier();
		PassEntry pass = this.renderPass(key);
		fk.renderPass = pass.renderPass;
		fk.width = w;
		fk.height = h;
		long framebuffer = this.framebuffer(fk);
		int ax = Math.max(0, fb.x), ay = Math.max(0, fb.y);
		int aw = Math.max(1, Math.min(w, fb.x + fb.width) - ax), ah = Math.max(1, Math.min(h, fb.y + fb.height) - ay);
		if (fb.width <= 0 || fb.height <= 0) {
			ax = 0; ay = 0; aw = w; ah = h;
		}
		VkCommandBuffer cb = this.cb();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkClearValue.Buffer clears = VkClearValue.calloc(Math.max(1, clearCount), stack);
			int ci = 0;
			for (int i = 0; i < fb.colorCount; i++) {
				if (fb.color[i] == null) {
					continue;
				}
				VkTextureState t = state(fb.color[i].dxTexture());
				clears.get(ci).color(this.clearColor(stack, t, cv[ci * 4], cv[ci * 4 + 1], cv[ci * 4 + 2], cv[ci * 4 + 3]));
				ci++;
			}
			if (depthState != null) {
				clears.get(ci).depthStencil().depth(cv[ci * 4]).stencil(0);
			}
			VkRenderPassBeginInfo bi = VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(pass.renderPass).framebuffer(framebuffer)
				.clearValueCount(clearCount).pClearValues(clears);
			bi.renderArea().offset().set(ax, ay);
			bi.renderArea().extent().set(aw, ah);
			vkCmdBeginRenderPass(cb, bi, VK_SUBPASS_CONTENTS_INLINE);
			VkViewport.Buffer vp = VkViewport.calloc(1, stack);
			vp.get(0).x(0.0F).y(0.0F).width(w).height(h).minDepth(0.0F).maxDepth(1.0F);
			vkCmdSetViewport(cb, 0, vp);
		}
		this.inPass = true;
		this.curPass = pass;
		this.passWidth = w;
		this.passHeight = h;
		this.areaX = ax;
		this.areaY = ay;
		this.areaW = aw;
		this.areaH = ah;
		this.scissorEnabled = false;
		this.curPipeline = null;
		this.bindings.clear();
		this.resetBoundState();
		this.stats[DxStats.PASSES]++;
	}

	private boolean prepareDraw(final boolean indexed) {
		DxPipeline p = this.curPipeline;
		if (!this.inPass || p == null || p.driverData == null) {
			return false;
		}
		if (indexed && this.ib == null) {
			return false;
		}
		VkPipelineState ps = (VkPipelineState)p.driverData;
		VkCommandBuffer cb = this.frame.main;
		long pipeline = this.variant(p, ps, this.curPass);
		if (pipeline != this.boundPipeline) {
			vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
			this.boundPipeline = pipeline;
		}
		if (this.vbDirty || this.boundVbPipeline != p) {
			for (int k = 0; k < p.vbCount; k++) {
				int slot = p.vbSlot[k];
				DxBuffer b = slot < 8 ? this.vb[slot] : null;
				this.pLong.put(0, b != null ? b.handle() : this.dummyBuffer.handle);
				this.pLong2.put(0, b != null ? this.vbOffset[slot] : 0L);
				vkCmdBindVertexBuffers(cb, slot, this.pLong, this.pLong2);
			}
			this.vbDirty = false;
			this.boundVbPipeline = p;
		}
		if (indexed && this.ibDirty) {
			vkCmdBindIndexBuffer(cb, this.ib.handle(), 0L, this.ibBytes == 2 ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32);
			this.ibDirty = false;
		}
		// descriptors
		boolean needSet = this.bindings.resourcesDirty || this.boundDsl != ps.dsl || (ps.hasStatic && this.bindings.offsetsDirty);
		if (ps.count > 0) {
			if (needSet) {
				this.curSet = this.descriptorSet(p, ps);
				this.boundDsl = ps.dsl;
			}
			if (needSet || this.bindings.offsetsDirty || this.boundLayout != ps.layout) {
				this.dynOffsets.clear();
				for (int i = 0; i < ps.count; i++) {
					if (ps.dynamic[i]) {
						this.dynOffsets.put(this.bindings.buffer[i] != null ? (int)this.bindings.offset[i] : 0);
					}
				}
				this.dynOffsets.flip();
				this.pLong.put(0, this.curSet);
				vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, ps.layout, 0, this.pLong, this.dynOffsets.hasRemaining() ? this.dynOffsets : null);
				this.boundLayout = ps.layout;
			}
		}
		this.bindings.resourcesDirty = false;
		this.bindings.offsetsDirty = false;
		if (ps.pushSize > 0 && (this.pushDirty || this.boundPushLayout != ps.layout)) {
			this.pushData.clear().limit((ps.pushSize + 3) & ~3);
			vkCmdPushConstants(cb, ps.layout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, this.pushData);
			this.pushDirty = false;
			this.boundPushLayout = ps.layout;
		}
		if (this.scissorDirty) {
			int x0 = this.areaX, y0 = this.areaY, x1 = this.areaX + this.areaW, y1 = this.areaY + this.areaH;
			if (this.scissorEnabled) {
				x0 = Math.max(x0, this.scissorX);
				y0 = Math.max(y0, this.scissorY);
				x1 = Math.min(x1, this.scissorX + this.scissorW);
				y1 = Math.min(y1, this.scissorY + this.scissorH);
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
				sc.get(0).offset().set(Math.min(x0, this.passWidth), Math.min(y0, this.passHeight));
				sc.get(0).extent().set(Math.max(0, x1 - x0), Math.max(0, y1 - y0));
				vkCmdSetScissor(cb, 0, sc);
			}
			this.scissorDirty = false;
		}
		return true;
	}

	private long bufferView(final DxBuffer buffer, final long offset, final long range, final int format) {
		for (long[] v : this.frame.bufferViews) {
			if (v[0] == buffer.handle() && v[1] == offset && v[2] == range && v[3] == format) {
				return v[4];
			}
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferViewCreateInfo ci = VkBufferViewCreateInfo.calloc(stack).sType$Default().buffer(buffer.handle()).format(format).offset(offset).range(range);
			LongBuffer pl = stack.mallocLong(1);
			VkFormats.check(vkCreateBufferView(this.device, ci, null, pl), "vkCreateBufferView");
			this.frame.bufferViews.add(new long[]{buffer.handle(), offset, range, format, pl.get(0)});
			return pl.get(0);
		}
	}

	private long allocateSet(final long layout) {
		Frame f = this.frame;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer pl = stack.mallocLong(1);
			for (;;) {
				if (f.poolCursor >= f.descriptorPools.size()) {
					VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(5, stack);
					sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(4096);
					sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1024);
					sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4096);
					sizes.get(3).type(VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER).descriptorCount(256);
					sizes.get(4).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC).descriptorCount(256);
					VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1024).pPoolSizes(sizes);
					VkFormats.check(vkCreateDescriptorPool(this.device, ci, null, pl), "vkCreateDescriptorPool");
					f.descriptorPools.add(pl.get(0));
				}
				VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(f.descriptorPools.get(f.poolCursor))
					.pSetLayouts(stack.longs(layout));
				int rc = vkAllocateDescriptorSets(this.device, ai, pl);
				if (rc == VK_SUCCESS) {
					return pl.get(0);
				}
				if (rc != VK_ERROR_FRAGMENTED_POOL && rc != -1000069000 /* OUT_OF_POOL_MEMORY */ && rc != VK_ERROR_OUT_OF_DEVICE_MEMORY) {
					VkFormats.check(rc, "vkAllocateDescriptorSets");
				}
				f.poolCursor++;
			}
		}
	}

	/** Finds or creates the VkDescriptorSet for the current bindings. Sets are cached for the frame. */
	private long descriptorSet(final DxPipeline p, final VkPipelineState ps) {
		final long[] k = this.descKey;
		int n = 0;
		k[n++] = ps.dsl;
		for (int i = 0; i < ps.count; i++) {
			switch (ps.kind[i]) {
				case DxDescriptorSet.UNIFORM_BUFFER, DxDescriptorSet.STORAGE_BUFFER -> {
					DxBuffer b = this.bindings.buffer[i];
					k[n++] = b != null ? b.handle() : this.dummyBuffer.handle;
					k[n++] = b != null ? this.bindings.range[i] : 0L;
					k[n++] = b != null && !ps.dynamic[i] ? this.bindings.offset[i] : 0L;
				}
				case DxDescriptorSet.SAMPLER -> {
					DxTextureView v = this.bindings.view[i];
					VkTextureState t = v != null ? state(v.dxTexture()) : null;
					k[n++] = t != null ? this.view(t, v.baseMipLevel(), v.mipLevels(), false) : this.view(this.dummyTexture, 0, 1, false);
					k[n++] = t != null && this.bindings.sampler[i] != null ? this.sampler(this.bindings.sampler[i]) : this.dummySampler;
					k[n++] = 0L;
				}
				default -> {
					DxBuffer b = this.bindings.buffer[i];
					int format = VkFormats.format(p.uniformType[i], p.uniformComps[i]);
					k[n++] = b != null ? this.bufferView(b, this.bindings.offset[i], this.bindings.range[i], format)
						: this.bufferViewDummy(format);
					k[n++] = 0L;
					k[n++] = 0L;
				}
			}
		}
		long hash = 0xcbf29ce484222325L;
		for (int i = 0; i < n; i++) {
			hash = (hash ^ k[i]) * 0x100000001b3L;
		}
		hash ^= hash >>> 29;
		long set = this.frame.descCache.find(hash, k, n);
		if (set != 0L) {
			this.stats[DxStats.DESCRIPTOR_CACHE_HITS]++;
			return set;
		}
		set = this.allocateSet(ps.dsl);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(ps.count, stack);
			for (int i = 0; i < ps.count; i++) {
				VkWriteDescriptorSet w = writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1).descriptorType(ps.type[i]);
				long a = k[1 + i * 3], b = k[2 + i * 3], c = k[3 + i * 3];
				switch (ps.kind[i]) {
					case DxDescriptorSet.UNIFORM_BUFFER, DxDescriptorSet.STORAGE_BUFFER -> {
						VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack);
						long range = b <= 0 ? 256L : ps.kind[i] == DxDescriptorSet.UNIFORM_BUFFER ? Math.min(b, Integer.toUnsignedLong(this.maxUniformRange)) : b;
						bi.get(0).buffer(a).offset(c).range(range);
						w.pBufferInfo(bi);
					}
					case DxDescriptorSet.SAMPLER -> {
						VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack);
						ii.get(0).imageView(a).sampler(b).imageLayout(RESTING);
						w.pImageInfo(ii);
					}
					default -> w.pTexelBufferView(stack.longs(a));
				}
			}
			vkUpdateDescriptorSets(this.device, writes, null);
		}
		this.frame.descCache.put(hash, k, n, set);
		this.stats[DxStats.DESCRIPTOR_SETS]++;
		return set;
	}

	private long bufferViewDummy(final int format) {
		for (long[] v : this.frame.bufferViews) {
			if (v[0] == this.dummyBuffer.handle && v[3] == format) {
				return v[4];
			}
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferViewCreateInfo ci = VkBufferViewCreateInfo.calloc(stack).sType$Default().buffer(this.dummyBuffer.handle).format(format).offset(0L).range(4096L);
			LongBuffer pl = stack.mallocLong(1);
			VkFormats.check(vkCreateBufferView(this.device, ci, null, pl), "vkCreateBufferView");
			this.frame.bufferViews.add(new long[]{this.dummyBuffer.handle, 0L, 4096L, format, pl.get(0)});
			return pl.get(0);
		}
	}

	// ---- non draw commands ----
	private void clear(final DxTexture texture, final int mip, final float r, final float g, final float b, final float a, final int x, final int y, final int width,
		final int height) {
		VkTextureState t = state(texture);
		if (t == null) {
			return;
		}
		if (this.inPass) {
			vkCmdEndRenderPass(this.cb());
			this.inPass = false;
		}
		int first = mip < 0 ? 0 : mip, last = mip < 0 ? t.mips - 1 : mip;
		for (int m = first; m <= last && m < t.mips; m++) {
			int lw = Math.max(1, t.width >> m), lh = Math.max(1, t.height >> m);
			boolean whole = width <= 0 || (x <= 0 && y <= 0 && x + width >= lw && y + height >= lh);
			if (whole) {
				// deferred: becomes a render pass load operation if the texture is rendered to next
				PendingClear pc = this.takePending(t, m);
				if (pc == null) {
					pc = new PendingClear();
				}
				pc.texture = t;
				pc.mip = m;
				pc.r = r; pc.g = g; pc.b = b; pc.a = a;
				this.pendingClears.add(pc);
				continue;
			}
			// partial clear: a one attachment pass that loads the contents and clears the rectangle
			PendingClear old = this.takePending(t, m);
			if (old != null) {
				this.materialize(old);
			}
			this.closeTransfer();
			this.bufferBarrier();
			boolean depth = t.aspect == VK_IMAGE_ASPECT_DEPTH_BIT;
			PassKey key = this.scratchKey;
			FbKey fk = this.scratchFb;
			key.count = depth ? 0 : 1;
			key.format[0] = t.format;
			key.load[0] = 1;
			key.depthFormat = depth ? t.format : 0;
			key.depthLoad = depth ? 1 : 0;
			PassEntry pass = this.renderPass(key);
			fk.count = 1;
			fk.views[0] = this.view(t, m, 1, true);
			fk.renderPass = pass.renderPass;
			fk.width = lw;
			fk.height = lh;
			long framebuffer = this.framebuffer(fk);
			int cx = Math.max(0, x), cy = Math.max(0, y), cw = Math.min(lw, x + width) - cx, ch = Math.min(lh, y + height) - cy;
			if (cw <= 0 || ch <= 0) {
				continue;
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkRenderPassBeginInfo bi = VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(pass.renderPass).framebuffer(framebuffer);
				bi.renderArea().offset().set(cx, cy);
				bi.renderArea().extent().set(cw, ch);
				vkCmdBeginRenderPass(this.cb(), bi, VK_SUBPASS_CONTENTS_INLINE);
				VkClearAttachment.Buffer ca = VkClearAttachment.calloc(1, stack);
				ca.get(0).aspectMask(t.aspect).colorAttachment(0);
				if (depth) {
					ca.get(0).clearValue().depthStencil().depth(r).stencil(0);
				} else {
					ca.get(0).clearValue().color(this.clearColor(stack, t, r, g, b, a));
				}
				VkClearRect.Buffer cr = VkClearRect.calloc(1, stack);
				cr.get(0).baseArrayLayer(0).layerCount(1);
				cr.get(0).rect().offset().set(cx, cy);
				cr.get(0).rect().extent().set(cw, ch);
				vkCmdClearAttachments(this.cb(), ca, cr);
				vkCmdEndRenderPass(this.cb());
			}
			this.resetBoundState();
		}
	}

	private void copyBuffer(final DxBuffer src, final long srcOffset, final DxBuffer dst, final long dstOffset, final long length) {
		if (length <= 0 || this.inPass) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
			region.get(0).srcOffset(srcOffset).dstOffset(dstOffset).size(length);
			vkCmdCopyBuffer(this.cb(), src.handle(), dst.handle(), region);
		}
		this.bufferWritesPending = true;
	}

	private void copyBufferToTexture(final DxBuffer src, final long offset, final int rowTexels, final DxTexture dst, final int layer, final int mip, final int x,
		final int y, final int width, final int height) {
		VkTextureState t = state(dst);
		if (t == null || this.inPass || mip >= t.mips || width <= 0 || height <= 0) {
			return;
		}
		PendingClear pc = this.takePending(t, mip);
		if (pc != null) {
			this.materialize(pc);
		}
		this.bufferBarrier();
		this.openTransferDst(t, mip); // stays open: a run of uploads into one atlas level shares its two barriers
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0).bufferOffset(offset).bufferRowLength(rowTexels > 0 ? rowTexels : 0).bufferImageHeight(0);
			region.get(0).imageSubresource().set(t.aspect, mip, Math.min(layer, t.layers - 1), 1);
			region.get(0).imageOffset().set(x, y, 0);
			region.get(0).imageExtent().set(width, height, 1);
			vkCmdCopyBufferToImage(this.cb(), src.handle(), t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
		}
	}

	private void copyTextureToBuffer(final DxTexture src, final int mip, final int x, final int y, final int width, final int height, final DxBuffer dst,
		final long offset) {
		VkTextureState t = state(src);
		if (t == null || this.inPass || mip >= t.mips) {
			return;
		}
		this.closeTransfer();
		this.materializeAll();
		this.transition(t, mip, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
			region.get(0).bufferOffset(offset).bufferRowLength(0).bufferImageHeight(0);
			region.get(0).imageSubresource().set(t.aspect, mip, 0, 1);
			region.get(0).imageOffset().set(x, y, 0);
			region.get(0).imageExtent().set(width, height, 1);
			vkCmdCopyImageToBuffer(this.cb(), t.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.handle(), region);
		}
		this.transition(t, mip, RESTING);
		this.bufferWritesPending = true;
	}

	private void copyTextureToTexture(final DxTexture src, final DxTexture dst, final int mip, final int dstX, final int dstY, final int srcX, final int srcY,
		final int width, final int height) {
		VkTextureState s = state(src), t = state(dst);
		if (s == null || t == null || s == t || this.inPass || width <= 0 || height <= 0) {
			return;
		}
		int sm = Math.min(mip, s.mips - 1), dm = Math.min(mip, t.mips - 1);
		this.closeTransfer();
		this.materializeAll();
		this.transition(s, sm, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		this.transition(t, dm, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (s.format == t.format) {
				VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
				region.get(0).srcSubresource().set(s.aspect, sm, 0, 1);
				region.get(0).dstSubresource().set(t.aspect, dm, 0, 1);
				region.get(0).srcOffset().set(srcX, srcY, 0);
				region.get(0).dstOffset().set(dstX, dstY, 0);
				region.get(0).extent().set(width, height, 1);
				vkCmdCopyImage(this.cb(), s.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
			} else {
				VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
				region.get(0).srcSubresource().set(s.aspect, sm, 0, 1);
				region.get(0).dstSubresource().set(t.aspect, dm, 0, 1);
				region.get(0).srcOffsets(0).set(srcX, srcY, 0);
				region.get(0).srcOffsets(1).set(srcX + width, srcY + height, 1);
				region.get(0).dstOffsets(0).set(dstX, dstY, 0);
				region.get(0).dstOffsets(1).set(dstX + width, dstY + height, 1);
				vkCmdBlitImage(this.cb(), s.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_NEAREST);
			}
		}
		this.transition(s, sm, RESTING);
		this.transition(t, dm, RESTING);
	}

	/** Copies a finished color level into a swapchain image, flipping it (row 0 is the bottom of the picture). */
	void recordPresent(final VkSwapchain swapchain, final long swapImage, final int swapWidth, final int swapHeight, final DxTexture texture, final int mip) {
		VkTextureState t = state(texture);
		VkCommandBuffer cb = this.cb();
		this.endOpenWork();
		this.drainTextureInit();
		int sw = Math.max(1, t.width >> mip), sh = Math.max(1, t.height >> mip);
		this.transition(t, mip, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
		this.imageBarrier(cb, swapImage, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 1, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
			VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
			region.get(0).srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, mip, 0, 1);
			region.get(0).dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
			region.get(0).srcOffsets(0).set(0, sh, 0);
			region.get(0).srcOffsets(1).set(sw, 0, 1);
			region.get(0).dstOffsets(0).set(0, 0, 0);
			region.get(0).dstOffsets(1).set(swapWidth, swapHeight, 1);
			boolean scale = sw != swapWidth || sh != swapHeight;
			boolean linear = scale && (this.formatFeatures(t.format) & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
			vkCmdBlitImage(cb, t.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region,
				linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
		}
		this.imageBarrier(cb, swapImage, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
			VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
		this.transition(t, mip, RESTING);
		this.pendingPresent = swapchain;
	}

	boolean canBlitTo(final int format) {
		return (this.formatFeatures(format) & VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0 && (this.formatFeatures(VK_FORMAT_R8G8B8A8_UNORM) & VK_FORMAT_FEATURE_BLIT_SRC_BIT) != 0;
	}

	@Override
	public DxSwapchain createSwapchain(final long window) {
		return new VkSwapchain(this, window);
	}

	// ======================================================================================
	// misc
	// ======================================================================================
	@Override
	public void applyConfig(final DxConfig config) {
		// Nothing to reconfigure: resolution and vsync arrive through the swapchain.
	}

	@Override
	public void collectStats(final long[] out, final boolean reset) {
		synchronized (this.lock) {
			this.stats[DxStats.VRAM_BYTES] = this.memory.reservedBytes();
			this.stats[DxStats.TEXTURE_BYTES] = this.memory.usedBytes();
			System.arraycopy(this.stats, 0, out, 0, Math.min(out.length, this.stats.length));
			if (reset) {
				Arrays.fill(this.stats, 0L);
			}
		}
	}

	@Override
	public String profileText(final boolean reset) {
		synchronized (this.lock) {
			return String.format(Locale.ROOT,
				"Vulkan driver: %d shader modules, %d render passes, %d framebuffers, %d samplers, %d memory blocks (%d MiB reserved, %d MiB used), validation messages: %d",
				this.shaderModules.size(), this.renderPasses.size(), this.framebuffers.size(), this.samplers.size(), this.memory.blockCount(),
				this.memory.reservedBytes() >> 20, this.memory.usedBytes() >> 20, VALIDATION_MESSAGES.get());
		}
	}

	void waitIdle() {
		vkDeviceWaitIdle(this.device);
	}

	@Override
	public void close() {
		vkDeviceWaitIdle(this.device);
		this.savePipelineCache();
		for (Frame f : this.frames) {
			for (long[] v : f.bufferViews) {
				vkDestroyBufferView(this.device, v[4], null);
			}
			for (long pool : f.descriptorPools) {
				vkDestroyDescriptorPool(this.device, pool, null);
			}
			vkDestroyFence(this.device, f.fence, null);
			vkDestroyCommandPool(this.device, f.pool, null);
		}
		for (long fb : this.framebuffers.values()) {
			vkDestroyFramebuffer(this.device, fb, null);
		}
		for (PassEntry e : this.renderPasses.values()) {
			vkDestroyRenderPass(this.device, e.renderPass, null);
		}
		for (long m : this.shaderModules.values()) {
			vkDestroyShaderModule(this.device, m, null);
		}
		for (long s : this.samplers.values()) {
			vkDestroySampler(this.device, s, null);
		}
		vkDestroySampler(this.device, this.dummySampler, null);
		this.destroyImage(this.dummyTexture);
		this.memory.freeBuffer(this.dummyBuffer);
		if (this.queryPool != 0L) {
			vkDestroyQueryPool(this.device, this.queryPool, null);
		}
		vkDestroyPipelineCache(this.device, this.pipelineCache, null);
		this.memory.destroy();
		vkDestroyDevice(this.device, null);
		if (this.debugMessenger != 0L) {
			EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(this.instance, this.debugMessenger, null);
		}
		if (this.debugCallback != null) {
			this.debugCallback.free();
		}
		vkDestroyInstance(this.instance, null);
		MemoryUtil.memFree(this.pLong);
		MemoryUtil.memFree(this.pLong2);
		MemoryUtil.memFree(this.dynOffsets);
		MemoryUtil.memFree(this.pushData);
		MemoryUtil.memFree(this.entryMain);
	}
}
