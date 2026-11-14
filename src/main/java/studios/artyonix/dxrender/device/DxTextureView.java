package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

public final class DxTextureView extends BaseGpuTextureView {
	private boolean closed;

	DxTextureView(final DxTexture texture, final int baseMipLevel, final int mipLevels) {
		super(texture, baseMipLevel, mipLevels);
	}

	public DxTexture dxTexture() {
		return (DxTexture)this.texture();
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		this.closed = true;
	}
}
