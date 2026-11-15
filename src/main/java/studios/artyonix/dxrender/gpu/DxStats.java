package studios.artyonix.dxrender.gpu;

/** Indices of the counters every driver reports. 0..21 mirror struct dxr_stats of the native core. */
public final class DxStats {
	private DxStats() {
	}

	/** TRIS_IN: triangles submitted by draw calls (both backends). TRIANGLES: CPU backend only, triangles left after clipping and culling. */
	public static final int FRAMES = 0, DRAW_CALLS = 1, VERTICES = 2, TRIS_IN = 3, TRIS_CLIPPED = 4, TRIS_CULLED = 5, TRIANGLES = 6, TILE_REFS = 7,
		TRIS_HIZ = 8, FRAGS = 9, FRAGS_FAST = 10, FRAGS_DISCARDED = 11, FRAGS_ZFAIL = 12, TILES = 13, PASSES = 14, NS_GEOMETRY = 15, NS_RASTER = 16,
		NS_CLEAR = 17, NS_PRESENT = 18, ARENA_PEAK = 19, TEXTURE_BYTES = 20, INTERP_OPS = 21;
	/** Time the render thread spent inside the driver (recording, translating, rasterizing). */
	public static final int NS_CPU = 22;
	/** Time the GPU spent on the frame, from timestamp queries; 0 when unavailable. */
	public static final int NS_GPU = 23;
	/** Bytes reserved from the device (GPU) or the host (CPU) for buffers and textures. */
	public static final int VRAM_BYTES = 24;
	public static final int SHADER_CACHE_HITS = 25, SHADER_CACHE_MISSES = 26;
	public static final int NS_PIPELINE_COMPILE = 27, PIPELINES_COMPILED = 28;
	public static final int DESCRIPTOR_SETS = 29, DESCRIPTOR_CACHE_HITS = 30;
	public static final int COUNT = 31;

	/** Counters that are levels, not per-frame sums. */
	public static boolean isGauge(final int index) {
		return index == ARENA_PEAK || index == TEXTURE_BYTES || index == VRAM_BYTES;
	}
}
