package studios.artyonix.dxrender.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * JNI surface of the DX Render native core (native/src). The slot numbers of the draw and
 * pass blocks mirror the enums in native/src/dxr_jni.c.
 */
public final class DxNative {
	private DxNative() {
	}

	// ---- draw block (64 bit slots) ----
	public static final int DS_PIPELINE = 0;
	public static final int DS_VB = 1;
	public static final int DS_VB_SIZE = 9;
	public static final int DS_INDEX = 17;
	public static final int DS_INDEX_SIZE = 18;
	public static final int DS_INDEX_BYTES = 19;
	public static final int DS_PUSH = 20;
	public static final int DS_PUSH_SIZE = 21;
	public static final int DS_SCISSOR_ON = 22;
	public static final int DS_SCISSOR = 23;
	public static final int DS_FIRST = 27;
	public static final int DS_COUNT = 28;
	public static final int DS_BASE_VERTEX = 29;
	public static final int DS_INSTANCES = 30;
	public static final int DS_FIRST_INSTANCE = 31;
	public static final int DS_UNIFORMS = 32;
	public static final int DS_UNIFORM_STRIDE = 4;
	public static final int MAX_UNIFORMS = 24;
	public static final int MAX_VERTEX_BUFFERS = 8;
	public static final int DS_SLOTS = DS_UNIFORMS + DS_UNIFORM_STRIDE * MAX_UNIFORMS;

	// ---- pass block ----
	public static final int PS_NUM_COLOR = 0;
	public static final int PS_COLOR = 1;
	public static final int PS_COLOR_MIP = 9;
	public static final int PS_DEPTH = 17;
	public static final int PS_DEPTH_MIP = 18;
	public static final int PS_AREA = 19;
	public static final int PS_CLEAR_MASK = 23;
	public static final int PS_CLEAR_COLOR = 24;
	public static final int PS_CLEAR_DEPTH_ON = 56;
	public static final int PS_CLEAR_DEPTH = 57;
	public static final int PS_SLOTS = 58;

	// ---- component types ----
	public static final int CT_UNORM8 = 0, CT_SNORM8 = 1, CT_UNORM16 = 2, CT_SNORM16 = 3, CT_UINT8 = 4, CT_SINT8 = 5, CT_UINT16 = 6, CT_SINT16 = 7,
		CT_UINT32 = 8, CT_SINT32 = 9, CT_FLOAT16 = 10, CT_FLOAT32 = 11, CT_DEPTH32F = 12;

	public static final int UNIFORM_NONE = 0, UNIFORM_BUFFER = 1, UNIFORM_SAMPLER = 2, UNIFORM_TEXEL_BUFFER = 3;

	// ---- indices into the array filled by nDeviceStats (order of struct dxr_stats) ----
	public static final int ST_FRAMES = 0, ST_DRAW_CALLS = 1, ST_VERTICES = 2, ST_TRIS_IN = 3, ST_TRIS_CLIPPED = 4, ST_TRIS_CULLED = 5, ST_TRIS_BINNED = 6,
		ST_TILE_REFS = 7, ST_TRIS_HIZ = 8, ST_FRAGS = 9, ST_FRAGS_FAST = 10, ST_FRAGS_DISCARDED = 11, ST_FRAGS_ZFAIL = 12, ST_TILES = 13, ST_PASSES = 14,
		ST_NS_GEOMETRY = 15, ST_NS_RASTER = 16, ST_NS_CLEAR = 17, ST_NS_PRESENT = 18, ST_ARENA_PEAK = 19, ST_TEXTURE_BYTES = 20, ST_INTERP_OPS = 21,
		ST_COUNT = 22;

	private static boolean loaded;
	private static String loadError;
	private static String loadedFrom = "";

	public static native String nVersion();

	public static native String nSimdName();

	public static native String nLastError();

	public static native int nCpuCount();

	public static native int nDrawSlots();

	public static native int nPassSlots();

	public static native boolean nIsAndroid();

	public static native long nDeviceCreate(int threads, int tileSize, boolean occlusion, boolean fastShaders, boolean simd, boolean adaptive);

	public static native void nDeviceDestroy(long device);

	public static native void nDeviceConfigure(long device, int threads, int tileSize, boolean occlusion, boolean fastShaders, boolean simd, boolean adaptive);

	public static native int nDeviceThreads(long device);

	public static native void nDeviceStats(long device, long[] out, boolean reset);

	public static native void nEndFrame(long device);

	public static native long nTextureCreate(long device, int ctype, int ncomp, int width, int height, int layers, int mips, boolean cube);

	public static native void nTextureDestroy(long device, long texture);

	public static native long nTextureLevelAddress(long texture, int layer, int mip);

	public static native void nTextureWrite(long texture, int layer, int mip, int x, int y, int w, int h, long src, int srcRowTexels);

	public static native void nTextureRead(long texture, int layer, int mip, int x, int y, int w, int h, long dst);

	public static native void nTextureCopy(long src, long dst, int mip, int dx, int dy, int sx, int sy, int w, int h);

	public static native void nTextureClearColor(long texture, int mip, float r, float g, float b, float a, int x, int y, int w, int h);

	public static native void nTextureClearDepth(long texture, int mip, float depth, int x, int y, int w, int h);

	public static native long nPipelineCreate(long device, long blob, int length);

	public static native void nPipelineDestroy(long device, long pipeline);

	public static native String nPipelineInfo(long pipeline);

	public static native boolean nPipelineFast(long pipeline);

	public static native String nProfileText(long device, boolean reset);

	/** Experimental kernel GPU interface probe (Mali kbase), see native/src/dxr_kbase.c. */
	public static native String nKbaseProbe(boolean runCommands);

	public static native int nPassBegin(long device, long passBlock);

	public static native int nDraw(long device, long drawBlock);

	public static native int nMultiDraw(long device, long drawBlock, long params, int drawCount);

	public static native void nPassEnd(long device);

	public static native void nPresentBlit(long device, long src, int srcW, int srcH, int srcStride, long dst, int dstW, int dstH, int dstStride, int dstFormat);

	public static native int nPresentAndroid(long device, long nativeWindow, long src, int srcW, int srcH, int srcStride);

	public static boolean isLoaded() {
		return loaded;
	}

	public static String loadedFrom() {
		return loadedFrom;
	}

	public static boolean looksLikeAndroid() {
		return Files.exists(Path.of("/system/build.prop")) || Files.exists(Path.of("/system/bin/app_process64")) || System.getenv("ANDROID_ROOT") != null;
	}

	/** Name of the directory inside the jar that holds the library for this platform, or null. */
	public static String platformDir() {
		String arch = System.getProperty("os.arch", "").toLowerCase();
		boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
		boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
		String os = System.getProperty("os.name", "").toLowerCase();
		if (looksLikeAndroid()) {
			return arm64 ? "android-arm64" : null;
		}
		if (os.contains("linux")) {
			return x64 ? "linux-x86_64" : arm64 ? "linux-arm64" : null;
		}
		return null;
	}

	/**
	 * Loads libdxrender. Throws with a readable reason when the platform has no native build
	 * or the library cannot be mapped; the caller turns that into a backend creation failure.
	 */
	public static synchronized void load() throws IOException {
		if (loaded) {
			return;
		}
		if (loadError != null) {
			throw new IOException(loadError);
		}
		try {
			String override = System.getProperty("dxrender.native");
			if (override != null && !override.isEmpty()) {
				System.load(override);
				loadedFrom = override;
			} else {
				String dir = platformDir();
				if (dir == null) {
					throw new IOException("DX Render has no native core for " + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
						+ " (built targets: android-arm64, linux-x86_64)");
				}
				byte[] bytes;
				try (InputStream in = DxNative.class.getResourceAsStream("/natives/" + dir + "/libdxrender.so")) {
					if (in == null) {
						throw new IOException("libdxrender.so for " + dir + " is missing from the mod jar");
					}
					bytes = in.readAllBytes();
				}
				String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)).substring(0, 12);
				List<Path> roots = new ArrayList<>();
				String tmp = System.getProperty("java.io.tmpdir");
				if (tmp != null) {
					roots.add(Path.of(tmp));
				}
				roots.add(Path.of(System.getProperty("user.dir", ".")).resolve(".dxrender"));
				String home = System.getProperty("user.home");
				if (home != null) {
					roots.add(Path.of(home).resolve(".dxrender"));
				}
				Throwable last = null;
				for (Path root : roots) {
					try {
						Path target = root.resolve("dxrender-natives").resolve(hash).resolve("libdxrender.so");
						if (!Files.exists(target) || Files.size(target) != bytes.length) {
							Files.createDirectories(target.getParent());
							Path part = target.resolveSibling("libdxrender.so." + ProcessHandle.current().pid() + ".part");
							Files.write(part, bytes);
							Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
						}
						System.load(target.toAbsolutePath().toString());
						loadedFrom = target.toAbsolutePath().toString();
						last = null;
						break;
					} catch (IOException | UnsatisfiedLinkError | SecurityException e) {
						last = e;
					}
				}
				if (last != null) {
					throw new IOException("could not load libdxrender.so (" + dir + "): " + last, last);
				}
			}
			if (nDrawSlots() != DS_SLOTS || nPassSlots() != PS_SLOTS) {
				throw new IOException("native core / Java layout mismatch (draw " + nDrawSlots() + " vs " + DS_SLOTS + ")");
			}
			loaded = true;
		} catch (IOException e) {
			loadError = e.getMessage();
			throw e;
		} catch (Throwable t) {
			loadError = String.valueOf(t);
			throw new IOException(loadError, t);
		}
	}
}
