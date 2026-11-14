package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.GpuFormat;
import studios.artyonix.dxrender.core.DxNative;

/** Maps the game's format enum onto the component encodings of the native core. */
public final class DxFormats {
	private DxFormats() {
	}

	public static boolean isDepth(final GpuFormat format) {
		return format.hasDepthAspect();
	}

	public static int componentType(final GpuFormat format) {
		if (format.hasDepthAspect()) {
			return DxNative.CT_DEPTH32F;
		}
		return switch (format.componentType()) {
			case UNORM_8 -> DxNative.CT_UNORM8;
			case SNORM_8 -> DxNative.CT_SNORM8;
			case UNORM_16 -> DxNative.CT_UNORM16;
			case SNORM_16 -> DxNative.CT_SNORM16;
			case UINT_8 -> DxNative.CT_UINT8;
			case SINT_8 -> DxNative.CT_SINT8;
			case UINT_16 -> DxNative.CT_UINT16;
			case SINT_16 -> DxNative.CT_SINT16;
			case UINT_32 -> DxNative.CT_UINT32;
			case SINT_32 -> DxNative.CT_SINT32;
			case FLOAT_16 -> DxNative.CT_FLOAT16;
			case FLOAT_32 -> DxNative.CT_FLOAT32;
			default -> throw new IllegalArgumentException("DX Render does not support the format " + format);
		};
	}

	public static int componentCount(final GpuFormat format) {
		return format.hasDepthAspect() ? 1 : format.componentCount();
	}
}
