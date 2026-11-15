package studios.artyonix.dxrender.gpu;

import studios.artyonix.dxrender.command.DxCommandBuffer;
import studios.artyonix.dxrender.core.DxConfig;
import studios.artyonix.dxrender.device.DxSampler;
import studios.artyonix.dxrender.device.DxSwapchain;
import studios.artyonix.dxrender.device.DxTexture;
import studios.artyonix.dxrender.memory.DxMemoryAllocator;
import studios.artyonix.dxrender.shader.DxPipeline;
import studios.artyonix.dxrender.shader.DxShaderException;

/**
 * DX Render GPU Interface: the contract between the DX Render API and whatever executes it.
 * Two drivers exist: the CPU rasterizer (gpu.cpu) and the Vulkan driver (gpu.vulkan).
 * Nothing above this interface knows which one is running.
 */
public interface DxDriver {
	/** "CPU" or "GPU". */
	String backendName();

	DxCaps caps();

	DxMemoryAllocator allocator();

	/** True when commands must be executed as soon as they are recorded (the CPU rasterizer reads buffers at draw time). */
	boolean executesImmediately();

	long createWindow(String title, int width, int height, long flags);

	void createTexture(DxTexture texture);

	void destroyTexture(DxTexture texture);

	void destroySampler(DxSampler sampler);

	void createPipeline(DxPipeline pipeline) throws DxShaderException;

	void destroyPipeline(DxPipeline pipeline);

	DxSwapchain createSwapchain(long window);

	/** The Command Processor entry: translates recorded DX Render commands into backend work. */
	void execute(DxCommandBuffer commands);

	/** Submits everything recorded since the previous call as one frame. */
	void endFrame();

	/** Number of frames submitted so far; the frame being recorded has this index. */
	long frameIndex();

	/** True once the given frame has finished executing. Waits up to timeoutNs (0 = poll, negative = forever). */
	boolean awaitFrame(long frame, long timeoutNs);

	void applyConfig(DxConfig config);

	/** Fills counters indexed by DxStats.*; resets them when asked. */
	void collectStats(long[] out, boolean reset);

	String profileText(boolean reset);

	void close();
}
