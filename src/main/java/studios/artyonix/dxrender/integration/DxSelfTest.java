package studios.artyonix.dxrender.integration;

import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import studios.artyonix.dxrender.core.DxNative;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.device.DxDevice;
import studios.artyonix.dxrender.profiler.DxProfiler;

/**
 * Automated end-to-end check, off unless -Ddxrender.selftest=menu|world is given.
 * "world" creates a fresh creative world with a fixed seed, lets it run for
 * -Ddxrender.selftest.seconds (default 60) while logging measured frame statistics, then
 * stops the client. It drives the real game; nothing is simulated.
 */
public final class DxSelfTest {
	private static final String MODE = System.getProperty("dxrender.selftest", "");
	private static final int SECONDS = Integer.getInteger("dxrender.selftest.seconds", 60);
	private static final int RENDER_DISTANCE = Integer.getInteger("dxrender.selftest.renderDistance", 0);
	private static final boolean LOG_STATS = Boolean.getBoolean("dxrender.logStats") || !MODE.isEmpty();

	private static int ticks;
	private static boolean worldRequested;
	private static long inWorldSince;
	private static long lastLog;

	private DxSelfTest() {
	}

	static void register() {
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
			"fps=%.2f frame=%.2fms p99=%.2fms geometry=%.2fms raster=%.2fms draws=%.0f verts=%.0f tris=%.0f hizRejected=%.0f frags=%.0f fast=%.0f zfail=%.0f tiles=%.0f passes=%.0f",
			p.fps(), p.frameTimeMs(), p.frameTimePercentileMs(0.99), p.perFrame(DxNative.ST_NS_GEOMETRY) / 1.0e6, p.perFrame(DxNative.ST_NS_RASTER) / 1.0e6,
			p.perFrame(DxNative.ST_DRAW_CALLS), p.perFrame(DxNative.ST_VERTICES), p.perFrame(DxNative.ST_TRIS_BINNED), p.perFrame(DxNative.ST_TRIS_HIZ),
			p.perFrame(DxNative.ST_FRAGS), p.perFrame(DxNative.ST_FRAGS_FAST), p.perFrame(DxNative.ST_FRAGS_ZFAIL), p.perFrame(DxNative.ST_TILES),
			p.perFrame(DxNative.ST_PASSES));
	}

	private static void tick(final Minecraft mc) {
		ticks++;
		long now = System.nanoTime();
		if (LOG_STATS && now - lastLog > 5_000_000_000L) {
			lastLog = now;
			Screen s = mc.gui.screen();
			DxRender.LOGGER.info("[stats] {} screen={} level={}", statsLine(), s == null ? "none" : s.getClass().getSimpleName(), mc.level != null);
			DxDevice dev = DxRender.device();
			if (dev != null && Boolean.getBoolean("dxrender.profilePipelines")) {
				DxRender.LOGGER.info("[pipelines over the last 5 s]\n{}", DxNative.nProfileText(dev.handle(), true));
			}
		}
		if (MODE.isEmpty()) {
			return;
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
				DxRender.LOGGER.info("[selftest] in world, render distance {}", mc.options.renderDistance().get());
			}
		} else if (inWorldSince == 0L && screen instanceof TitleScreen) {
			inWorldSince = now;
		}
		if (inWorldSince != 0L && now - inWorldSince > SECONDS * 1_000_000_000L) {
			DxRender.LOGGER.info("[selftest] done: {}", statsLine());
			inWorldSince = Long.MAX_VALUE / 2;
			mc.stop();
		}
	}
}
