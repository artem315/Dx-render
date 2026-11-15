package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

/** DX Render texture. The driver attaches its own object (native handle or GPU image). */
public final class DxTexture extends BaseGpuTexture {
	private final DxDevice device;
	/** CPU backend: handle of the native texture. */
	public long handle;
	/** GPU backend: driver state (image, views, layouts). */
	public Object driverData;
	public final int componentType, componentCount;
	private boolean closed;

	DxTexture(final DxDevice device, final int usage, final String label, final GpuFormat format, final int width, final int height, final int depthOrLayers,
		final int mipLevels) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.device = device;
		this.componentType = DxFormats.componentType(format);
		this.componentCount = DxFormats.componentCount(format);
	}

	public long handle() {
		return this.handle;
	}

	public boolean isCube() {
		return (this.usage() & USAGE_CUBEMAP_COMPATIBLE) != 0;
	}

	public boolean isDepth() {
		return this.getFormat().hasDepthAspect();
	}

	public boolean isRenderTarget() {
		return (this.usage() & USAGE_RENDER_ATTACHMENT) != 0;
	}

	public int layers() {
		return this.isCube() ? 6 : 1;
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
