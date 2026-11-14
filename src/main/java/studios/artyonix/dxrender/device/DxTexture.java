package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

public final class DxTexture extends BaseGpuTexture {
	private final DxDevice device;
	private final long handle;
	private boolean closed;

	DxTexture(final DxDevice device, final long handle, final int usage, final String label, final GpuFormat format, final int width, final int height,
		final int depthOrLayers, final int mipLevels) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.device = device;
		this.handle = handle;
	}

	public long handle() {
		return this.handle;
	}

	public boolean isCube() {
		return (this.usage() & USAGE_CUBEMAP_COMPATIBLE) != 0;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.device.releaseTexture(this);
		}
	}
}
