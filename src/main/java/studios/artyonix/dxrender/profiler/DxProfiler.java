package studios.artyonix.dxrender.profiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import studios.artyonix.dxrender.device.DxDevice;
import studios.artyonix.dxrender.gpu.DxStats;

/**
 * DX Render Profiler: real measurements only. Frame times are wall clock intervals between
 * submitted frames; the other figures are counters and timers kept by the active driver
 * (and GPU timestamp queries where the device has them).
 */
public final class DxProfiler {
	private static final int WINDOW = 240;

	private final long[] frameNs = new long[WINDOW];
	private int count, head;
	private long lastPresent;
	private final long[] stats = new long[DxStats.COUNT];
	private final long[] accum = new long[DxStats.COUNT];
	private final long[] lastSecond = new long[DxStats.COUNT];
	private final long[] totals = new long[DxStats.COUNT];
	private int accumFrames, lastSecondFrames = 1;
	private long secondStart;
	private double fps;
	private Path benchFile;
	private long totalFrames;
	private String backend = "?";

	public synchronized void frame(final DxDevice device) {
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
		this.backend = device.driver().backendName();
		device.driver().collectStats(this.stats, true);
		long driverTime = device.queue().takeDriverTime();
		if (this.stats[DxStats.NS_CPU] == 0L) {
			this.stats[DxStats.NS_CPU] = driverTime;
		}
		for (int i = 0; i < this.accum.length; i++) {
			if (DxStats.isGauge(i)) {
				this.accum[i] = this.stats[i];
				this.totals[i] = this.stats[i];
			} else {
				this.accum[i] += this.stats[i];
				this.totals[i] += this.stats[i];
			}
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

	public synchronized String backend() {
		return this.backend;
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

	/** Per frame average of a counter over the last full second (gauges: the latest value). */
	public synchronized double perFrame(final int statIndex) {
		return DxStats.isGauge(statIndex) ? this.lastSecond[statIndex] : this.lastSecond[statIndex] / (double)this.lastSecondFrames;
	}

	/** Sum since start-up (gauges: the latest value). */
	public synchronized long total(final int statIndex) {
		return this.totals[statIndex];
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
				Files.writeString(this.benchFile, "time_ms,backend,fps,frame_ms,p99_ms,cpu_ms,gpu_ms,draw_calls,triangles,vram_mb\n", StandardOpenOption.CREATE);
			}
			String line = String.format(Locale.ROOT, "%d,%s,%.2f,%.3f,%.3f,%.3f,%.3f,%.0f,%.0f,%.1f%n", System.currentTimeMillis(), this.backend, this.fps,
				this.frameTimeMs(), this.frameTimePercentileMs(0.99), this.perFrame(DxStats.NS_CPU) / 1.0e6, this.perFrame(DxStats.NS_GPU) / 1.0e6,
				this.perFrame(DxStats.DRAW_CALLS), this.perFrame(DxStats.TRIANGLES), this.perFrame(DxStats.VRAM_BYTES) / 1048576.0);
			Files.writeString(this.benchFile, line, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			this.benchFile = null;
		}
	}
}
