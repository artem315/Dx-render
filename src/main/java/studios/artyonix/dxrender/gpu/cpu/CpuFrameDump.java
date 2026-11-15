package studios.artyonix.dxrender.gpu.cpu;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.core.DxRender;

/**
 * Test aid: with -Ddxrender.dumpDir=DIR every N-th presented frame (-Ddxrender.dumpEvery=N,
 * default 120) is written as a PNG exactly as the rasterizer produced it. Used by the
 * automated checks; inactive unless the property is set.
 */
final class CpuFrameDump {
	private static final String DIR = System.getProperty("dxrender.dumpDir");
	private static final int EVERY = Math.max(1, Integer.getInteger("dxrender.dumpEvery", 120));
	private static long frame;

	private CpuFrameDump() {
	}

	static void maybeDump(final long src, final int w, final int h) {
		if (DIR == null) {
			return;
		}
		long f = frame++;
		if (f % EVERY != 0) {
			return;
		}
		try {
			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			int[] row = new int[w];
			for (int y = 0; y < h; y++) {
				long p = src + (long)(h - 1 - y) * w * 4; // stored bottom-up
				for (int x = 0; x < w; x++) {
					int v = MemoryUtil.memGetInt(p + 4L * x);
					row[x] = (v & 0xFF) << 16 | (v >> 8 & 0xFF) << 8 | (v >> 16 & 0xFF);
				}
				img.setRGB(0, y, w, 1, row, 0, w);
			}
			File dir = new File(DIR);
			dir.mkdirs();
			ImageIO.write(img, "png", new File(dir, String.format("frame-%06d.png", f)));
		} catch (Throwable t) {
			DxRender.LOGGER.warn("frame dump failed", t);
		}
	}
}
