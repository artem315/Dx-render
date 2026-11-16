package studios.artyonix.dxrender.integration;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import studios.artyonix.dxrender.integration.gui.DxOverlay;
import studios.artyonix.dxrender.integration.gui.DxSettingsScreen;
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
		DxReport.register();
		KeyMapping open = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.dxrender.settings", InputConstants.KEY_F8,
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(DxRender.MOD_ID, "main"))));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (open.consumeClick()) {
				client.gui.setScreen(new DxSettingsScreen(client.gui.screen()));
			}
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(DxRender.MOD_ID, "profiler"), DxOverlay::extract);
		// A "DX Render..." button in Video Settings.
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof VideoSettingsScreen) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatable("dxrender.settings.button"), b -> client.gui.setScreen(new DxSettingsScreen(screen)))
					.bounds(w - 106, 6, 100, 20).build());
			}
		});
		if (DxConfig.get().benchmarkLog) {
			ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
				var dev = DxRender.device();
				if (dev != null) {
					dev.profiler().setBenchmarkFile(client.gameDirectory.toPath().resolve("dxrender-bench.csv"));
				}
			});
		}
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
