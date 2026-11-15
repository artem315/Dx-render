package studios.artyonix.dxrender.gpu;

import java.util.List;

/** What a driver can do. Filled from real queries, never assumed. */
public record DxCaps(
	String deviceName,
	String vendorName,
	String driverInfo,
	DeviceKind kind,
	int maxAnisotropy,
	int uniformOffsetAlignment,
	int maxTextureSize,
	long maxAllocationSize,
	int maxColorAttachments,
	int maxDrawIndirectCount,
	boolean wireframe,
	boolean instanceIndexIncludesBase,
	boolean drawIndirect,
	boolean multiDrawIndirect,
	boolean nonZeroFirstInstance,
	boolean independentBlend,
	boolean gpuTimestamps,
	float timestampPeriod,
	List<String> extensions
) {
	public enum DeviceKind {
		CPU, INTEGRATED, DISCRETE, VIRTUAL, OTHER
	}
}
