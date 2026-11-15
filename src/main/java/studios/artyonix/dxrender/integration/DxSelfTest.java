package studios.artyonix.dxrender.integration;

import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.device.DxDevice;
import studios.artyonix.dxrender.device.DxSurface;
import studios.artyonix.dxrender.gpu.DxStats;
import studios.artyonix.dxrender.gpu.kbase.DxKernelGpu;
import studios.artyonix.dxrender.gpu.vulkan.VkDriver;
import studios.artyonix.dxrender.profiler.DxProfiler;

/**
 * Automated end-to-end check, off unless -Ddxrender.selftest=menu|world is given.
 * "world" creates a fresh creative world with a fixed seed, lets it run for
 * -Ddxrender.selftest.seconds (default 60) while logging measured frame statistics and
 * saving screenshots through the game's own read-back path, then stops the client.
 * It drives the real game on whichever DX Render backend is active; nothing is simulated.
 */
public final class DxSelfTest {
	private static final String MODE = System.getProperty("dxrender.selftest", "");
	private static final int SECONDS = Integer.getInteger("dxrender.selftest.seconds", 60);
	private static final int RENDER_DISTANCE = Integer.getInteger("dxrender.selftest.renderDistance", 0);
	private static final int SHOT_SECONDS = Integer.getInteger("dxrender.selftest.screenshotEvery", 10);
	private static final boolean LOG_STATS = Boolean.getBoolean("dxrender.logStats") || !MODE.isEmpty();

	private static int ticks;
	private static boolean worldRequested;
	private static long inWorldSince;
	private static long lastLog, lastShot;
	private static final boolean RESIZE = Boolean.getBoolean("dxrender.selftest.resize");
	private static int resizeStep;
	private static final boolean SETTINGS = Boolean.getBoolean("dxrender.selftest.settings");
	private static boolean settingsOpened;

	private DxSelfTest() {
	}

	static void register() {
		if (Boolean.getBoolean("dxrender.kernelProbe")) {
			DxKernelGpu.probe(true, null);
		}
		if (MODE.isEmpty() && !LOG_STATS) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(DxSelfTest::tick);
	}

	public static String statsLine() {
		DxDevice dev = DxRender.device();
		if (dev == null) {
			return "DX Render inactive: " + DxRender.inactiveReason();
		}
		DxProfiler p = dev.profiler();
		return String.format(Locale.ROOT,
			"backend=%s fps=%.2f frame=%.2fms p99=%.2fms cpu=%.2fms gpu=%.2fms draws=%.0f tris=%.0f passes=%.0f vram=%.1fMiB shaderCache=%d/%d pipelines=%d (%.1fms total) descSets=%.0f descHits=%.0f",
			p.backend(), p.fps(), p.frameTimeMs(), p.frameTimePercentileMs(0.99), p.perFrame(DxStats.NS_CPU) / 1.0e6, p.perFrame(DxStats.NS_GPU) / 1.0e6,
			p.perFrame(DxStats.DRAW_CALLS), p.perFrame(DxStats.TRIS_IN), p.perFrame(DxStats.PASSES), p.perFrame(DxStats.VRAM_BYTES) / 1048576.0,
			p.total(DxStats.SHADER_CACHE_HITS), p.total(DxStats.SHADER_CACHE_MISSES), p.total(DxStats.PIPELINES_COMPILED),
			p.total(DxStats.NS_PIPELINE_COMPILE) / 1.0e6, p.perFrame(DxStats.DESCRIPTOR_SETS), p.perFrame(DxStats.DESCRIPTOR_CACHE_HITS));
	}

	private static void tick(final Minecraft mc) {
		ticks++;
		long now = System.nanoTime();
		DxDevice dev = DxRender.device();
		if (LOG_STATS && now - lastLog > 5_000_000_000L) {
			lastLog = now;
			Screen s = mc.gui.screen();
			DxSurface surface = dev == null ? null : dev.surface();
			DxRender.LOGGER.info("[stats] {} screen={} level={} present={}", statsLine(), s == null ? "none" : s.getClass().getSimpleName(), mc.level != null,
				surface == null ? "-" : surface.presentPath());
			if (dev != null && Boolean.getBoolean("dxrender.profilePipelines")) {
				DxRender.LOGGER.info("[driver profile]\n{}", dev.driver().profileText(true));
			}
		}
		if (MODE.isEmpty()) {
			return;
		}
		if (SHOT_SECONDS > 0 && now - lastShot > SHOT_SECONDS * 1_000_000_000L && ticks > 40) {
			lastShot = now;
			try {
				Screenshot.grab(mc.gameDirectory, mc.gameRenderer.mainRenderTarget(), msg -> DxRender.LOGGER.info("[selftest] screenshot: {}", msg.getString()));
			} catch (Throwable t) {
				DxRender.LOGGER.error("[selftest] screenshot failed", t);
			}
		}
		if (mc.options.onboardAccessibility) {
			mc.options.onboardAccessibility = false;
		}
		Screen screen = mc.gui.screen();
		if (screen instanceof AccessibilityOnboardingScreen) {
			mc.gui.setScreen(new TitleScreen());
			return;
		}
		if (MODE.equals("world")) {
			if (!worldRequested && screen instanceof TitleScreen && ticks > 60) {
				worldRequested = true;
				if (RENDER_DISTANCE > 0) {
					mc.options.renderDistance().set(RENDER_DISTANCE);
				}
				LevelSettings settings = new LevelSettings("DX Render Test", GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
					true, WorldDataConfiguration.DEFAULT);
				DxRender.LOGGER.info("[selftest] creating test world");
				mc.createWorldOpenFlows().createFreshLevel("dxrender-test-" + System.currentTimeMillis(), settings, new WorldOptions(20261114L, true, false),
					WorldPresets::createNormalWorldDimensions, screen);
				return;
			}
			if (mc.level != null && mc.player != null && inWorldSince == 0L) {
				inWorldSince = now;
				if (Boolean.getBoolean("dxrender.selftest.f3")) {
					mc.debugEntries.setOverlayVisible(true);
				}
				// Same view for every run, so backends can be compared on identical work.
				String view = System.getProperty("dxrender.selftest.view", "spectator");
				if (!view.equals("spawn")) {
					mc.player.connection.sendCommand("gamerule advance_time false");
					mc.player.connection.sendCommand("gamerule advance_weather false");
					mc.player.connection.sendCommand("time set 6000");
					mc.player.connection.sendCommand("weather clear");
					mc.player.connection.sendCommand("gamemode spectator");
					mc.player.connection.sendCommand(System.getProperty("dxrender.selftest.tp", "tp @s 40 110 40 135 25"));
				}
				DxRender.LOGGER.info("[selftest] in world, render distance {}", mc.options.renderDistance().get());
			}
		} else if (inWorldSince == 0L && screen instanceof TitleScreen) {
			inWorldSince = now;
		}
		if (RESIZE && inWorldSince != 0L && inWorldSince < Long.MAX_VALUE / 4) {
			long t = now - inWorldSince, third = SECONDS * 1_000_000_000L / 3;
			if (resizeStep == 0 && t > third) {
				resizeStep = 1;
				DxRender.LOGGER.info("[selftest] resizing window to 1024x600");
				mc.getWindow().setWindowed(1024, 600);
			} else if (resizeStep == 1 && t > 2 * third) {
				resizeStep = 2;
				DxRender.LOGGER.info("[selftest] resizing window to 640x400");
				mc.getWindow().setWindowed(640, 400);
			}
		}
		if (SETTINGS && !settingsOpened && inWorldSince != 0L && inWorldSince < Long.MAX_VALUE / 4 && now - inWorldSince > SECONDS * 1_000_000_000L / 2) {
			settingsOpened = true;
			DxRender.LOGGER.info("[selftest] opening the DX Render settings screen");
			mc.gui.setScreen(Boolean.getBoolean("dxrender.kernelProbe") ? new studios.artyonix.dxrender.integration.gui.DxKernelProbeScreen(null)
				: new studios.artyonix.dxrender.integration.gui.DxSettingsScreen(null));
		}
		if (inWorldSince != 0L && now - inWorldSince > SECONDS * 1_000_000_000L) {
			DxRender.LOGGER.info("[selftest] done: {} | vulkan validation messages: {}", statsLine(), VkDriver.validationMessages());
			if (dev != null) {
				DxRender.LOGGER.info("[selftest] {}", dev.driver().profileText(false));
			}
			inWorldSince = Long.MAX_VALUE / 2;
			mc.stop();
		}
	}
}
