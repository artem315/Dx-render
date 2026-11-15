package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;

public final class DxSampler implements GpuSampler {
	private final AddressMode addressModeU, addressModeV;
	private final FilterMode minFilter, magFilter;
	private final int maxAnisotropy;
	private final OptionalDouble maxLod;
	private boolean closed;
	/** GPU backend: driver sampler object, created on first use. */
	public long driverHandle;
	private final DxDevice device;

	DxSampler(final DxDevice device, final AddressMode u, final AddressMode v, final FilterMode min, final FilterMode mag, final int maxAnisotropy, final OptionalDouble maxLod) {
		this.addressModeU = u;
		this.addressModeV = v;
		this.minFilter = min;
		this.magFilter = mag;
		this.maxAnisotropy = maxAnisotropy;
		this.maxLod = maxLod;
		this.device = device;
	}

	/** Bits 8..39 of the first uniform slot word, see dxr_jni.c. */
	public long stateBits() {
		long bits = 0L;
		if (this.minFilter == FilterMode.LINEAR) {
			bits |= 1L << 8;
		}
		if (this.magFilter == FilterMode.LINEAR) {
			bits |= 1L << 16;
		}
		if (this.addressModeU == AddressMode.CLAMP_TO_EDGE) {
			bits |= 1L << 24;
		}
		if (this.addressModeV == AddressMode.CLAMP_TO_EDGE) {
			bits |= 1L << 32;
		}
		return bits;
	}

	public boolean minLinear() {
		return this.minFilter == FilterMode.LINEAR;
	}

	public boolean magLinear() {
		return this.magFilter == FilterMode.LINEAR;
	}

	public boolean clampU() {
		return this.addressModeU == AddressMode.CLAMP_TO_EDGE;
	}

	public boolean clampV() {
		return this.addressModeV == AddressMode.CLAMP_TO_EDGE;
	}

	public float maxLodValue() {
		return this.maxLod.isPresent() ? (float)this.maxLod.getAsDouble() : 1000.0F;
	}

	@Override
	public AddressMode getAddressModeU() {
		return this.addressModeU;
	}

	@Override
	public AddressMode getAddressModeV() {
		return this.addressModeV;
	}

	@Override
	public FilterMode getMinFilter() {
		return this.minFilter;
	}

	@Override
	public FilterMode getMagFilter() {
		return this.magFilter;
	}

	@Override
	public int getMaxAnisotropy() {
		return this.maxAnisotropy;
	}

	@Override
	public OptionalDouble getMaxLod() {
		return this.maxLod;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.device.releaseSampler(this);
		}
	}
}
