package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.commands.GpuFence;
import studios.artyonix.dxrender.command.DxCommandQueue;

/** A fence is "the frame that was being recorded when it was created has finished executing". */
public final class DxFence implements GpuFence {
	private final DxCommandQueue queue;
	private final long frame;
	private boolean done;

	public DxFence(final DxCommandQueue queue) {
		this.queue = queue;
		this.frame = queue.frameIndex();
	}

	@Override
	public void close() {
		this.done = true;
	}

	@Override
	public boolean awaitCompletion(final long timeoutNS) {
		if (!this.done) {
			this.done = this.queue.awaitFrame(this.frame, timeoutNS);
		}
		return this.done;
	}
}
