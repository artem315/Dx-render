package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.commands.GpuFence;

/** Rendering is synchronous: by the time a fence can be observed, its work is done. */
public final class DxFence implements GpuFence {
	@Override
	public void close() {
	}

	@Override
	public boolean awaitCompletion(final long timeoutNS) {
		return true;
	}
}
