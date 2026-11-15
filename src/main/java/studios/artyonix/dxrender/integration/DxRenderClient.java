package studios.artyonix.dxrender.integration;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import studios.artyonix.dxrender.core.DxConfig;
import studios.artyonix.dxrender.core.DxRender;

/** DX Render Minecraft Integration: mod entry point. */
public final class DxRenderClient implements ClientModInitializer {
	private static boolean configLoaded;

	/** The graphics backend is chosen before mod initialisers run, so config loading is lazy. */
	public static synchronized void ensureConfigLoaded() {
		if (configLoaded) {
			return;
		}
		configLoaded = true;
		FabricLoader loader = FabricLoader.getInstance();
		loader.getModContainer(DxRender.MOD_ID).ifPresent(c -> DxRender.setVersion(c.getMetadata().getVersion().getFriendlyString()));
		DxConfig.load(loader.getConfigDir().resolve("dxrender.json"));
	}

	@Override
	public void onInitializeClient() {
		ensureConfigLoaded();
		DxSelfTest.register();
		// The backend is created after mod initialisation, so report once the client is up.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			if (DxRender.isBackendActive()) {
				var dev = DxRender.device();
				String backend = dev == null ? "?" : dev.driver().backendName();
				DxRender.LOGGER.info("DX Render {} is producing the frames on the {} backend ({})", DxRender.version(), backend,
					"CPU".equals(backend) ? "software rasterizer and SPIR-V interpreter, no GPU API in use" : "Vulkan GPU driver, " + dev.driver().caps().deviceName());
			} else {
				DxRender.LOGGER.warn("DX Render is NOT active: {}. The game is running on its own renderer.", DxRender.inactiveReason());
			}
		});
	}
}
