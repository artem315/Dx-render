package studios.artyonix.dxrender.command;

import studios.artyonix.dxrender.gpu.DxDriver;

/**
 * DX Render command queue: owns the command buffer being recorded and hands it to the
 * driver. Frames are numbered; a fence is simply "has frame N finished executing".
 */
public final class DxCommandQueue {
	private final DxDriver driver;
	private final DxCommandBuffer recording = new DxCommandBuffer();
	private final boolean immediate;
	private long nsInDriver;

	public DxCommandQueue(final DxDriver driver) {
		this.driver = driver;
		this.immediate = driver.executesImmediately();
	}

	public DxCommandBuffer buffer() {
		return this.recording;
	}

	/** Called after each recorded command. Immediate drivers execute it right away. */
	public void commit() {
		if (this.immediate) {
			this.flush();
		}
	}

	/** Hands everything recorded so far to the driver. */
	public void flush() {
		if (!this.recording.isEmpty()) {
			long t0 = System.nanoTime();
			this.driver.execute(this.recording);
			this.nsInDriver += System.nanoTime() - t0;
			this.recording.reset();
		}
	}

	/** Ends the frame: flushes and submits. */
	public void submitFrame() {
		this.flush();
		long t0 = System.nanoTime();
		this.driver.endFrame();
		this.nsInDriver += System.nanoTime() - t0;
	}

	public long frameIndex() {
		return this.driver.frameIndex();
	}

	public boolean awaitFrame(final long frame, final long timeoutNs) {
		return this.driver.awaitFrame(frame, timeoutNs);
	}

	/** Nanoseconds spent executing and submitting since the last call. */
	public long takeDriverTime() {
		long v = this.nsInDriver;
		this.nsInDriver = 0L;
		return v;
	}
}
