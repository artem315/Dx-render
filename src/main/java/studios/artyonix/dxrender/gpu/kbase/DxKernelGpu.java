package studios.artyonix.dxrender.gpu.kbase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import studios.artyonix.dxrender.core.DxNative;
import studios.artyonix.dxrender.core.DxRender;

/**
 * DX Render GPU Interface at kernel level (experimental): direct use of the Arm Mali kernel
 * driver, without Vulkan or OpenGL. At this stage it probes what the device allows - driver
 * handshake, GPU properties, GPU memory, and GPU execution of a command stream written by
 * DX Render. It does not render frames; see docs/GPU_KERNEL_INTERFACE.md.
 */
public final class DxKernelGpu {
	private static final AtomicReference<String> LAST = new AtomicReference<>("");
	private static volatile boolean running;

	private DxKernelGpu() {
	}

	public static String lastReport() {
		return LAST.get();
	}

	public static boolean isRunning() {
		return running;
	}

	/** Runs the probe on a background thread (the command test may wait up to 2 s for the GPU). */
	public static CompletableFuture<String> probe(final boolean runCommands, final Path saveTo) {
		running = true;
		LAST.set("running...");
		return CompletableFuture.supplyAsync(() -> {
			String report;
			try {
				DxNative.load();
				report = DxNative.nKbaseProbe(runCommands);
			} catch (Throwable t) {
				report = "DX Render native core not available: " + t.getMessage();
			}
			LAST.set(report);
			running = false;
			DxRender.LOGGER.info("[kernel GPU probe]\n{}", report);
			if (saveTo != null) {
				try {
					Files.createDirectories(saveTo.getParent());
					Files.writeString(saveTo, report);
				} catch (Exception e) {
					DxRender.LOGGER.warn("could not save the probe report", e);
				}
			}
			return report;
		}, r -> {
			Thread t = new Thread(r, "DX Render kernel GPU probe");
			t.setDaemon(true);
			t.start();
		});
	}
}
