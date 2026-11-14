package studios.artyonix.dxrender.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** DX Render Core: identity, shared state and the entry points other modules hang off. */
public final class DxRender {
	public static final String MOD_ID = "dxrender";
	public static final String NAME = "DX Render";
	public static final String BACKEND_NAME = "DX Render";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	private static String version = "1.0.0-ALPHA.1";
	private static volatile boolean backendActive;
	private static volatile String inactiveReason = "not initialised";
	private static volatile studios.artyonix.dxrender.device.DxDevice device;

	private DxRender() {
	}

	public static String version() {
		return version;
	}

	public static void setVersion(final String v) {
		if (v != null && !v.isEmpty()) {
			version = v;
		}
	}

	/** True only while frames are really produced by the DX Render rasterizer. */
	public static boolean isBackendActive() {
		return backendActive;
	}

	public static String inactiveReason() {
		return inactiveReason;
	}

	public static void setInactive(final String reason) {
		backendActive = false;
		inactiveReason = reason;
		device = null;
	}

	public static void setActive(final studios.artyonix.dxrender.device.DxDevice dev) {
		device = dev;
		backendActive = true;
		inactiveReason = "";
	}

	public static studios.artyonix.dxrender.device.DxDevice device() {
		return device;
	}
}
