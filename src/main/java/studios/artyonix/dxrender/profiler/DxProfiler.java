package studios.artyonix.dxrender.profiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import studios.artyonix.dxrender.core.DxNative;

/**
 * DX Render Profiler: real measurements only. Frame times are wall clock intervals between
 * presented frames; stage times come from timers inside the native core.
 */
public final class DxProfiler {
	private static final int WINDOW = 240;

	private final long[] frameNs = new long[WINDOW];
	private int count, head;
	private long lastPresent;
	private final long[] stats = new long[DxNative.ST_COUNT];
	private final long[] accum = new long[DxNative.ST_COUNT];
	private final long[] lastSecond = new long[DxNative.ST_COUNT];
	private int accumFrames, lastSecondFrames = 1;
	private long secondStart;
	private double fps;
	private Path benchFile;
	private long totalFrames;

	public synchronized void frame(final long device) {
		long now = System.nanoTime();
		if (this.lastPresent != 0L) {
			this.frameNs[this.head] = now - this.lastPresent;
			this.head = (this.head + 1) % WINDOW;
			if (this.count < WINDOW) {
				this.count++;
			}
		}
		this.lastPresent = now;
		this.totalFrames++;
		DxNative.nDeviceStats(device, this.stats, true);
		for (int i = 0; i < this.accum.length; i++) {
			this.accum[i] = i == DxNative.ST_ARENA_PEAK || i == DxNative.ST_TEXTURE_BYTES ? this.stats[i] : this.accum[i] + this.stats[i];
		}
		this.accumFrames++;
		if (this.secondStart == 0L) {
			this.secondStart = now;
		}
		if (now - this.secondStart >= 1_000_000_000L) {
			this.fps = this.accumFrames * 1.0e9 / (now - this.secondStart);
			System.arraycopy(this.accum, 0, this.lastSecond, 0, this.accum.length);
			this.lastSecondFrames = Math.max(1, this.accumFrames);
			Arrays.fill(this.accum, 0L);
			this.accumFrames = 0;
			this.secondStart = now;
			this.writeBenchLine();
		}
	}

	public synchronized double fps() {
		return this.fps;
	}

	public synchronized long totalFrames() {
		return this.totalFrames;
	}

	/** Mean frame time of the measurement window in milliseconds. */
	public synchronized double frameTimeMs() {
		if (this.count == 0) {
			return 0.0;
		}
		long sum = 0L;
		for (int i = 0; i < this.count; i++) {
			sum += this.frameNs[i];
		}
		return sum / (double)this.count / 1.0e6;
	}

	/** Frame time below which the given fraction of frames fall (0.99 gives the "1% low" basis). */
	public synchronized double frameTimePercentileMs(final double fraction) {
		if (this.count == 0) {
			return 0.0;
		}
		long[] copy = Arrays.copyOf(this.frameNs, this.count);
		Arrays.sort(copy);
		int idx = (int)Math.min(this.count - 1, Math.floor(fraction * this.count));
		return copy[idx] / 1.0e6;
	}

	/** Per frame average of a native counter over the last full second. */
	public synchronized double perFrame(final int statIndex) {
		return this.lastSecond[statIndex] / (double)this.lastSecondFrames;
	}

	public synchronized long lastValue(final int statIndex) {
		return this.lastSecond[statIndex];
	}

	public synchronized void setBenchmarkFile(final Path file) {
		this.benchFile = file;
	}

	private void writeBenchLine() {
		if (this.benchFile == null) {
			return;
		}
		try {
			if (!Files.exists(this.benchFile)) {
				Files.writeString(this.benchFile, "time_ms,fps,frame_ms,p99_ms,geometry_ms,raster_ms,triangles,fragments,draw_calls\n", StandardOpenOption.CREATE);
			}
			String line = String.format(Locale.ROOT, "%d,%.2f,%.3f,%.3f,%.3f,%.3f,%.0f,%.0f,%.0f%n", System.currentTimeMillis(), this.fps, this.frameTimeMs(),
				this.frameTimePercentileMs(0.99), this.perFrame(DxNative.ST_NS_GEOMETRY) / 1.0e6, this.perFrame(DxNative.ST_NS_RASTER) / 1.0e6,
				this.perFrame(DxNative.ST_TRIS_BINNED), this.perFrame(DxNative.ST_FRAGS), this.perFrame(DxNative.ST_DRAW_CALLS));
			Files.writeString(this.benchFile, line, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			this.benchFile = null;
		}
	}
}
