package studios.artyonix.dxrender.command;

import studios.artyonix.dxrender.device.DxBuffer;
import studios.artyonix.dxrender.device.DxSampler;
import studios.artyonix.dxrender.device.DxTextureView;

/**
 * DX Render descriptor set: the table of resources bound to the uniform slots of the
 * current pipeline. Drivers keep one while replaying a command buffer and turn it into
 * whatever their backend binds (slots of the native draw block, or a VkDescriptorSet).
 */
public final class DxDescriptorSet {
	public static final int MAX_BINDINGS = 24;
	public static final int NONE = 0, UNIFORM_BUFFER = 1, SAMPLER = 2, TEXEL_BUFFER = 3, STORAGE_BUFFER = 4;

	public final DxBuffer[] buffer = new DxBuffer[MAX_BINDINGS];
	public final long[] offset = new long[MAX_BINDINGS];
	public final long[] range = new long[MAX_BINDINGS];
	public final DxTextureView[] view = new DxTextureView[MAX_BINDINGS];
	public final DxSampler[] sampler = new DxSampler[MAX_BINDINGS];
	/** Set whenever a resource (not just an offset) changed since the driver last looked. */
	public boolean resourcesDirty = true;
	public boolean offsetsDirty = true;

	public void clear() {
		for (int i = 0; i < MAX_BINDINGS; i++) {
			this.buffer[i] = null;
			this.view[i] = null;
			this.sampler[i] = null;
			this.offset[i] = 0L;
			this.range[i] = 0L;
		}
		this.resourcesDirty = true;
		this.offsetsDirty = true;
	}

	public void setBuffer(final int index, final DxBuffer buf, final long off, final long len) {
		if (this.buffer[index] != buf || this.range[index] != len || this.view[index] != null) {
			this.resourcesDirty = true;
		}
		if (this.offset[index] != off) {
			this.offsetsDirty = true;
		}
		this.buffer[index] = buf;
		this.offset[index] = off;
		this.range[index] = len;
		this.view[index] = null;
		this.sampler[index] = null;
	}

	public void setTexture(final int index, final DxTextureView v, final DxSampler s) {
		if (this.view[index] != v || this.sampler[index] != s || this.buffer[index] != null) {
			this.resourcesDirty = true;
		}
		this.view[index] = v;
		this.sampler[index] = s;
		this.buffer[index] = null;
		this.offset[index] = 0L;
		this.range[index] = 0L;
	}

	public void setNone(final int index) {
		if (this.buffer[index] != null || this.view[index] != null) {
			this.resourcesDirty = true;
		}
		this.buffer[index] = null;
		this.view[index] = null;
		this.sampler[index] = null;
		this.offset[index] = 0L;
		this.range[index] = 0L;
	}
}
