package studios.artyonix.dxrender.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persistent settings (config/dxrender.json). */
public final class DxConfig {
	public enum Preset {
		SAFE, BALANCED, EXTREME, CUSTOM
	}

	/**
	 * AUTO: GPU backend when a hardware Vulkan device works, otherwise the CPU rasterizer.
	 * GPU: Vulkan GPU backend only; start-up fails with an explanation if it cannot run.
	 * CPU: software rasterizer and SPIR-V interpreter.
	 */
	public enum RenderMode {
		AUTO, GPU, CPU
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static DxConfig instance;
	private static Path path;

	/** Use DX Render as the graphics backend. When false the game runs on its own renderer. */
	public boolean enabled = true;
	/** If the DX Render backend cannot start, let the game continue with its own renderer instead of failing. */
	public boolean fallbackToVanilla = true;
	/** Which DX Render backend draws the game. Takes effect at the next start. */
	public RenderMode renderMode = RenderMode.AUTO;
	public Preset preset = Preset.BALANCED;
	/** Rendering threads including the main thread; 0 = all cores. */
	public int workerThreads = 0;
	public int tileSize = 32;
	public boolean textureCache = true;
	public boolean chunkRendering = true;
	public boolean occlusionCulling = true;
	public boolean framePacing = true;
	public boolean debugOverlay = true;
	public boolean fastShaders = true;
	/** Internal resolution in percent of the window (25..100). */
	public int renderScale = 50;
	/** Write one CSV line per second to dxrender-bench.csv for comparisons with the vanilla renderer. */
	public boolean benchmarkLog = false;

	public static synchronized DxConfig get() {
		if (instance == null) {
			instance = new DxConfig();
		}
		return instance;
	}

	public static synchronized void load(final Path file) {
		path = file;
		DxConfig loaded = null;
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file)) {
				loaded = GSON.fromJson(r, DxConfig.class);
			} catch (Exception e) {
				DxRender.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}
		instance = loaded != null ? loaded : new DxConfig();
		instance.sanitize();
		if (loaded == null) {
			instance.applyPreset(Preset.BALANCED);
			save();
		}
	}

	public static synchronized void save() {
		if (path == null || instance == null) {
			return;
		}
		try {
			Files.createDirectories(path.getParent());
			try (Writer w = Files.newBufferedWriter(path)) {
				GSON.toJson(instance, w);
			}
		} catch (IOException e) {
			DxRender.LOGGER.warn("Could not save {}", path, e);
		}
	}

	/** The configured mode, unless -Ddxrender.mode=auto|gpu|cpu overrides it for this run. */
	public RenderMode effectiveMode() {
		String override = System.getProperty("dxrender.mode");
		if (override != null) {
			try {
				return RenderMode.valueOf(override.trim().toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return renderMode == null ? RenderMode.AUTO : renderMode;
	}

	public void sanitize() {
		if (preset == null) {
			preset = Preset.CUSTOM;
		}
		if (renderMode == null) {
			renderMode = RenderMode.AUTO;
		}
		workerThreads = Math.max(0, Math.min(16, workerThreads));
		if (tileSize != 16 && tileSize != 32 && tileSize != 64 && tileSize != 128) {
			tileSize = 32;
		}
		renderScale = Math.max(25, Math.min(100, renderScale));
	}

	public static int cpuCount() {
		return Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors()));
	}

	public int effectiveThreads() {
		return workerThreads <= 0 ? cpuCount() : Math.min(workerThreads, 16);
	}

	/**
	 * SAFE: half of the cores, half resolution, steady pacing (lowest heat).
	 * BALANCED: all cores but one, 66 % resolution.
	 * EXTREME: every core, full resolution, no pacing (highest load).
	 */
	public void applyPreset(final Preset p) {
		preset = p;
		int cpus = cpuCount();
		switch (p) {
			case SAFE -> {
				workerThreads = Math.max(1, cpus / 2);
				tileSize = 32;
				renderScale = 50;
				framePacing = true;
				occlusionCulling = true;
				chunkRendering = true;
				textureCache = true;
				fastShaders = true;
			}
			case BALANCED -> {
				workerThreads = Math.max(1, cpus - 1);
				tileSize = 32;
				renderScale = 66;
				framePacing = true;
				occlusionCulling = true;
				chunkRendering = true;
				textureCache = true;
				fastShaders = true;
			}
			case EXTREME -> {
				workerThreads = cpus;
				tileSize = 64;
				renderScale = 100;
				framePacing = false;
				occlusionCulling = true;
				chunkRendering = true;
				textureCache = true;
				fastShaders = true;
			}
			default -> {
			}
		}
	}
}
