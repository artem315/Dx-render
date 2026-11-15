package studios.artyonix.dxrender.integration.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import studios.artyonix.dxrender.core.DxConfig;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.device.DxDevice;
import studios.artyonix.dxrender.gpu.DxStats;
import studios.artyonix.dxrender.profiler.DxProfiler;

/** DX Render Profiler on screen: measured numbers only, refreshed once per second. */
public final class DxOverlay {
	private DxOverlay() {
	}

	public static List<String> lines(final DxDevice dev) {
		DxProfiler p = dev.profiler();
		boolean gpu = dev.driver().caps().gpuTimestamps();
		List<String> out = new ArrayList<>(5);
		out.add(String.format(Locale.ROOT, "DX Render %s | %s: %s", DxRender.version(), p.backend(), dev.driver().caps().deviceName()));
		out.add(String.format(Locale.ROOT, "FPS %.1f | frame %.1f ms (99%% < %.1f ms)", p.fps(), p.frameTimeMs(), p.frameTimePercentileMs(0.99)));
		out.add(String.format(Locale.ROOT, "CPU render %.2f ms | GPU %s", p.perFrame(DxStats.NS_CPU) / 1.0e6,
			gpu ? String.format(Locale.ROOT, "%.2f ms", p.perFrame(DxStats.NS_GPU) / 1.0e6) : "n/a (no timestamps)"));
		out.add(String.format(Locale.ROOT, "Draws %.0f | Tris %.1fk | Passes %.0f", p.perFrame(DxStats.DRAW_CALLS), p.perFrame(DxStats.TRIS_IN) / 1000.0,
			p.perFrame(DxStats.PASSES)));
		out.add(String.format(Locale.ROOT, "%s ~%.0f MiB | Shaders %d hit / %d miss | Pipelines %d (%.0f ms)", "CPU".equals(p.backend()) ? "RAM" : "VRAM",
			p.perFrame(DxStats.VRAM_BYTES) / 1048576.0, p.total(DxStats.SHADER_CACHE_HITS), p.total(DxStats.SHADER_CACHE_MISSES), p.total(DxStats.PIPELINES_COMPILED),
			p.total(DxStats.NS_PIPELINE_COMPILE) / 1.0e6));
		return out;
	}

	public static void extract(final GuiGraphicsExtractor graphics, final DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		DxDevice dev = DxRender.device();
		if (dev == null || !DxConfig.get().debugOverlay || mc.debugEntries.isOverlayVisible() || mc.gui.screen() instanceof DxSettingsScreen) {
			return;
		}
		List<String> lines = lines(dev);
		int w = 0;
		for (String l : lines) {
			w = Math.max(w, mc.font.width(l));
		}
		graphics.fill(1, 1, w + 5, lines.size() * 10 + 3, 0x90000000);
		int y = 3;
		for (String l : lines) {
			graphics.text(mc.font, l, 3, y, 0xFFE0E0E0, false);
			y += 10;
		}
	}
}
