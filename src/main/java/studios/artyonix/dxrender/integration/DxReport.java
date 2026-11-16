package studios.artyonix.dxrender.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import studios.artyonix.dxrender.core.DxConfig;
import studios.artyonix.dxrender.core.DxNative;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.device.DxDevice;
import studios.artyonix.dxrender.device.DxSurface;
import studios.artyonix.dxrender.gpu.DxCaps;
import studios.artyonix.dxrender.gpu.DxStats;
import studios.artyonix.dxrender.gpu.kbase.DxKernelGpu;
import studios.artyonix.dxrender.profiler.DxProfiler;
import studios.artyonix.dxrender.shader.DxPipeline;

/**
 * Writes .minecraft/dxrender/report.txt every 30 seconds and at exit: which backend runs on
 * which device, why AUTO chose it, measured performance in menus and in the world, pipeline
 * errors and the last kernel GPU probe. It is the one file to send when something needs to
 * be diagnosed on a phone.
 */
public final class DxReport {
	private static final long PERIOD_NS = 30_000_000_000L;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/** Per scene (menu / world): FPS samples taken every 5 seconds. */
	private static final class Scene {
		final ArrayDeque<double[]> recent = new ArrayDeque<>(); // {fps, frameMs, p99, cpuMs, gpuMs, draws, tris}
		double fpsMin = Double.MAX_VALUE, fpsMax, fpsSum;
		int samples;

		void add(final double[] s) {
			if (s[0] <= 0.0) {
				return;
			}
			this.fpsMin = Math.min(this.fpsMin, s[0]);
			this.fpsMax = Math.max(this.fpsMax, s[0]);
			this.fpsSum += s[0];
			this.samples++;
			this.recent.addLast(s);
			while (this.recent.size() > 12) {
				this.recent.removeFirst();
			}
		}
	}

	private static final Scene MENU = new Scene(), WORLD = new Scene();
	private static final String STARTED = LocalDateTime.now().format(TIME);
	private static long lastWrite, lastSample;

	private DxReport() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(DxReport::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> write(mc));
	}

	private static void tick(final Minecraft mc) {
		long now = System.nanoTime();
		DxDevice dev = DxRender.device();
		if (dev != null && now - lastSample > 5_000_000_000L) {
			lastSample = now;
			DxProfiler p = dev.profiler();
			double[] s = {p.fps(), p.frameTimeMs(), p.frameTimePercentileMs(0.99), p.perFrame(DxStats.NS_CPU) / 1.0e6, p.perFrame(DxStats.NS_GPU) / 1.0e6,
				p.perFrame(DxStats.DRAW_CALLS), p.perFrame(DxStats.TRIS_IN)};
			(mc.level != null && mc.gui.screen() == null ? WORLD : MENU).add(s);
		}
		if (lastWrite == 0L || now - lastWrite > PERIOD_NS) {
			lastWrite = now;
			write(mc);
		}
	}

	private static void scene(final StringBuilder sb, final String name, final Scene sc) {
		if (sc.samples == 0) {
			sb.append(name).append(": no samples yet\n");
			return;
		}
		sb.append(String.format(Locale.ROOT, "%s: FPS min %.1f / avg %.1f / max %.1f over %d samples (5 s each)%n", name, sc.fpsMin, sc.fpsSum / sc.samples,
			sc.fpsMax, sc.samples));
		sb.append("  recent:  fps   frame_ms  p99_ms  cpu_ms  gpu_ms  draws  triangles\n");
		for (double[] s : sc.recent) {
			sb.append(String.format(Locale.ROOT, "         %5.1f  %7.2f  %6.1f  %6.2f  %6.2f  %5.0f  %9.0f%n", s[0], s[1], s[2], s[3], s[4], s[5], s[6]));
		}
	}

	static synchronized void write(final Minecraft mc) {
		try {
			StringBuilder sb = new StringBuilder(4096);
			DxConfig cfg = DxConfig.get();
			sb.append("DX Render diagnostic report (rewritten every 30 s and at exit)\n");
			sb.append("version: ").append(DxRender.version()).append("   session started ").append(STARTED).append("   written ")
				.append(LocalDateTime.now().format(TIME)).append('\n');
			boolean android = false;
			try {
				android = DxNative.isLoaded() && DxNative.nIsAndroid();
			} catch (Throwable ignored) {
			}
			sb.append("platform: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch")).append(android ? " (Android)" : "")
				.append(", Java ").append(System.getProperty("java.version")).append(", ").append(Runtime.getRuntime().availableProcessors()).append(" CPU threads, max heap ")
				.append(Runtime.getRuntime().maxMemory() >> 20).append(" MiB\n");
			sb.append("render mode: configured ").append(cfg.renderMode).append(", effective ").append(cfg.effectiveMode()).append('\n');
			DxDevice dev = DxRender.device();
			if (dev == null) {
				sb.append("DX Render is NOT active: ").append(DxRender.inactiveReason()).append('\n');
			} else {
				DxCaps c = dev.driver().caps();
				sb.append("backend: ").append(dev.driver().backendName()).append('\n');
				if (DxRender.gpuFallbackReason() != null) {
					sb.append("AUTO did not use the GPU backend because: ").append(DxRender.gpuFallbackReason()).append('\n');
				}
				sb.append("device: ").append(c.deviceName()).append(" (").append(c.vendorName()).append(", ").append(c.kind()).append(")\n");
				sb.append("driver: ").append(c.driverInfo()).append('\n');
				sb.append(String.format(Locale.ROOT,
					"features: multiDrawIndirect=%s drawIndirect=%s independentBlend=%s wireframe=%s gpuTimestamps=%s maxAnisotropy=%d maxTexture=%d uboAlign=%d%n",
					c.multiDrawIndirect(), c.drawIndirect(), c.independentBlend(), c.wireframe(), c.gpuTimestamps(), c.maxAnisotropy(), c.maxTextureSize(),
					c.uniformOffsetAlignment()));
				DxSurface surface = dev.surface();
				sb.append("presentation: ").append(surface == null ? "-" : surface.presentPath()).append(", window ").append(mc.getWindow().getWidth()).append('x')
					.append(mc.getWindow().getHeight()).append('\n');
				DxProfiler p = dev.profiler();
				sb.append(String.format(Locale.ROOT,
					"now: fps %.1f, frame %.2f ms (99%% < %.1f ms), CPU %.2f ms, GPU %.2f ms, draws %.0f, triangles %.0f, passes %.0f%n", p.fps(), p.frameTimeMs(),
					p.frameTimePercentileMs(0.99), p.perFrame(DxStats.NS_CPU) / 1.0e6, p.perFrame(DxStats.NS_GPU) / 1.0e6, p.perFrame(DxStats.DRAW_CALLS),
					p.perFrame(DxStats.TRIS_IN), p.perFrame(DxStats.PASSES)));
				sb.append(String.format(Locale.ROOT, "memory: %.0f MiB %s; shaders: %d cache hits / %d misses; pipelines compiled: %d in %.0f ms; frames: %d%n",
					p.perFrame(DxStats.VRAM_BYTES) / 1048576.0, "CPU".equals(p.backend()) ? "RAM" : "GPU", p.total(DxStats.SHADER_CACHE_HITS),
					p.total(DxStats.SHADER_CACHE_MISSES), p.total(DxStats.PIPELINES_COMPILED), p.total(DxStats.NS_PIPELINE_COMPILE) / 1.0e6, p.totalFrames()));
				sb.append(String.format(Locale.ROOT, "settings: preset %s, threads %d, tile %d, pacing %s, overlay %s, render distance %d, gui scale %d%n", cfg.preset,
					cfg.effectiveThreads(), cfg.tileSize, cfg.framePacing, cfg.debugOverlay, mc.options.renderDistance().get(), mc.options.guiScale().get()));
				sb.append('\n');
				scene(sb, "menus", MENU);
				scene(sb, "world", WORLD);
				sb.append('\n').append(dev.driver().profileText(false)).append('\n');
			}
			sb.append("\npipelines rejected: ").append(DxPipeline.failureCount()).append('\n');
			for (String f : DxPipeline.recentFailures()) {
				sb.append("  ").append(f).append('\n');
			}
			String probe = DxKernelGpu.lastReport();
			sb.append("\nkernel GPU probe: ").append(probe.isEmpty() ? "not run (F8 -> Kernel GPU probe)" : "\n" + probe).append('\n');
			Path dir = mc.gameDirectory.toPath().resolve("dxrender");
			Files.createDirectories(dir);
			Path tmp = dir.resolve("report.txt.tmp");
			Files.writeString(tmp, sb.toString());
			Files.move(tmp, dir.resolve("report.txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Throwable t) {
			DxRender.LOGGER.warn("could not write dxrender/report.txt", t);
		}
	}
}
