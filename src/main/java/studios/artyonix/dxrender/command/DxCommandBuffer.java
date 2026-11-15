package studios.artyonix.dxrender.command;

import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.device.DxBuffer;
import studios.artyonix.dxrender.device.DxFramebuffer;
import studios.artyonix.dxrender.device.DxSampler;
import studios.artyonix.dxrender.device.DxTexture;
import studios.artyonix.dxrender.device.DxTextureView;
import studios.artyonix.dxrender.shader.DxPipeline;

/**
 * DX Render command buffer: a backend independent recording of rendering commands.
 * Commands are stored as a flat stream of 64 bit words plus a table of referenced objects,
 * so recording allocates nothing per command. A driver replays the stream in
 * {@link studios.artyonix.dxrender.gpu.DxDriver#execute}.
 *
 * Word layout per command: opcode, then the arguments listed next to each OP_ constant.
 * "ref" arguments are indices into {@link #refs()}.
 */
public final class DxCommandBuffer {
	/** ref framebuffer */
	public static final int OP_BEGIN_PASS = 1;
	public static final int OP_END_PASS = 2;
	/** ref pipeline */
	public static final int OP_SET_PIPELINE = 3;
	/** index, ref buffer, offset, length */
	public static final int OP_SET_UNIFORM_BUFFER = 4;
	/** index, ref view, ref sampler */
	public static final int OP_SET_UNIFORM_TEXTURE = 5;
	/** index */
	public static final int OP_SET_UNIFORM_NONE = 6;
	/** byte count n, then ceil(n / 8) data words */
	public static final int OP_PUSH_CONSTANTS = 7;
	/** enabled, x, y, width, height */
	public static final int OP_SET_SCISSOR = 8;
	/** slot, ref buffer (-1 = none), offset */
	public static final int OP_SET_VERTEX_BUFFER = 9;
	/** ref buffer (-1 = none), index size in bytes */
	public static final int OP_SET_INDEX_BUFFER = 10;
	/** firstVertex, vertexCount, instanceCount, firstInstance */
	public static final int OP_DRAW = 11;
	/** firstIndex, indexCount, baseVertex, instanceCount, firstInstance */
	public static final int OP_DRAW_INDEXED = 12;
	/** ref buffer, offset, drawCount */
	public static final int OP_DRAW_INDIRECT = 13;
	/** ref buffer, offset, drawCount */
	public static final int OP_DRAW_INDEXED_INDIRECT = 14;
	/** ref texture, mip (-1 = all), r, g, b, a (float bits), x, y, width, height (width 0 = whole level) */
	public static final int OP_CLEAR_COLOR = 15;
	/** ref texture, mip (-1 = all), depth (float bits), x, y, width, height */
	public static final int OP_CLEAR_DEPTH = 16;
	/** ref src, srcOffset, ref dst, dstOffset, length */
	public static final int OP_COPY_BUFFER = 17;
	/** ref buffer, offset, rowTexels, ref texture, layer, mip, x, y, width, height */
	public static final int OP_COPY_BUFFER_TO_TEXTURE = 18;
	/** ref texture, mip, x, y, width, height, ref buffer, offset */
	public static final int OP_COPY_TEXTURE_TO_BUFFER = 19;
	/** ref src, ref dst, mip, dstX, dstY, srcX, srcY, width, height */
	public static final int OP_COPY_TEXTURE_TO_TEXTURE = 20;

	private long[] data = new long[4096];
	private int size;
	private Object[] refs = new Object[512];
	private int refCount;
	private int draws;

	public long[] data() {
		return this.data;
	}

	public int size() {
		return this.size;
	}

	public Object[] refs() {
		return this.refs;
	}

	public boolean isEmpty() {
		return this.size == 0;
	}

	public int drawCount() {
		return this.draws;
	}

	/** Makes the buffer reusable; the arrays are kept. */
	public void reset() {
		Arrays.fill(this.refs, 0, this.refCount, null);
		this.size = 0;
		this.refCount = 0;
		this.draws = 0;
	}

	private void ensure(final int words) {
		if (this.size + words > this.data.length) {
			this.data = Arrays.copyOf(this.data, Math.max(this.data.length * 2, this.size + words));
		}
	}

	private long ref(final Object o) {
		if (o == null) {
			return -1L;
		}
		// consecutive commands very often name the same object
		if (this.refCount > 0 && this.refs[this.refCount - 1] == o) {
			return this.refCount - 1;
		}
		if (this.refCount == this.refs.length) {
			this.refs = Arrays.copyOf(this.refs, this.refs.length * 2);
		}
		this.refs[this.refCount] = o;
		return this.refCount++;
	}

	private static long f(final float v) {
		return Float.floatToRawIntBits(v) & 0xFFFFFFFFL;
	}

	public void beginPass(final DxFramebuffer framebuffer) {
		this.ensure(2);
		this.data[this.size++] = OP_BEGIN_PASS;
		this.data[this.size++] = this.ref(framebuffer);
	}

	public void endPass() {
		this.ensure(1);
		this.data[this.size++] = OP_END_PASS;
	}

	public void setPipeline(final DxPipeline pipeline) {
		this.ensure(2);
		this.data[this.size++] = OP_SET_PIPELINE;
		this.data[this.size++] = this.ref(pipeline);
	}

	public void setUniformBuffer(final int index, final DxBuffer buffer, final long offset, final long length) {
		this.ensure(5);
		this.data[this.size++] = OP_SET_UNIFORM_BUFFER;
		this.data[this.size++] = index;
		this.data[this.size++] = this.ref(buffer);
		this.data[this.size++] = offset;
		this.data[this.size++] = length;
	}

	public void setUniformTexture(final int index, final DxTextureView view, final DxSampler sampler) {
		this.ensure(4);
		this.data[this.size++] = OP_SET_UNIFORM_TEXTURE;
		this.data[this.size++] = index;
		this.data[this.size++] = this.ref(view);
		this.data[this.size++] = this.ref(sampler);
	}

	public void setUniformNone(final int index) {
		this.ensure(2);
		this.data[this.size++] = OP_SET_UNIFORM_NONE;
		this.data[this.size++] = index;
	}

	public void pushConstants(final long address, final int bytes) {
		int words = (bytes + 7) / 8;
		this.ensure(2 + words);
		this.data[this.size++] = OP_PUSH_CONSTANTS;
		this.data[this.size++] = bytes;
		for (int i = 0; i < words; i++) {
			long v = 0L;
			int n = Math.min(8, bytes - i * 8);
			for (int b = 0; b < n; b++) {
				v |= (MemoryUtil.memGetByte(address + i * 8L + b) & 0xFFL) << (8 * b);
			}
			this.data[this.size++] = v;
		}
	}

	public void setScissor(final boolean enabled, final int x, final int y, final int width, final int height) {
		this.ensure(6);
		this.data[this.size++] = OP_SET_SCISSOR;
		this.data[this.size++] = enabled ? 1 : 0;
		this.data[this.size++] = x;
		this.data[this.size++] = y;
		this.data[this.size++] = width;
		this.data[this.size++] = height;
	}

	public void setVertexBuffer(final int slot, final DxBuffer buffer, final long offset) {
		this.ensure(4);
		this.data[this.size++] = OP_SET_VERTEX_BUFFER;
		this.data[this.size++] = slot;
		this.data[this.size++] = this.ref(buffer);
		this.data[this.size++] = offset;
	}

	public void setIndexBuffer(final DxBuffer buffer, final int indexBytes) {
		this.ensure(3);
		this.data[this.size++] = OP_SET_INDEX_BUFFER;
		this.data[this.size++] = this.ref(buffer);
		this.data[this.size++] = indexBytes;
	}

	public void draw(final int firstVertex, final int vertexCount, final int instanceCount, final int firstInstance) {
		this.ensure(5);
		this.data[this.size++] = OP_DRAW;
		this.data[this.size++] = firstVertex;
		this.data[this.size++] = vertexCount;
		this.data[this.size++] = instanceCount;
		this.data[this.size++] = firstInstance;
		this.draws++;
	}

	public void drawIndexed(final int firstIndex, final int indexCount, final int baseVertex, final int instanceCount, final int firstInstance) {
		this.ensure(6);
		this.data[this.size++] = OP_DRAW_INDEXED;
		this.data[this.size++] = firstIndex;
		this.data[this.size++] = indexCount;
		this.data[this.size++] = baseVertex;
		this.data[this.size++] = instanceCount;
		this.data[this.size++] = firstInstance;
		this.draws++;
	}

	public void drawIndirect(final boolean indexed, final DxBuffer commands, final long offset, final int drawCount) {
		this.ensure(4);
		this.data[this.size++] = indexed ? OP_DRAW_INDEXED_INDIRECT : OP_DRAW_INDIRECT;
		this.data[this.size++] = this.ref(commands);
		this.data[this.size++] = offset;
		this.data[this.size++] = drawCount;
		this.draws++;
	}

	public void clearColor(final DxTexture texture, final int mip, final float r, final float g, final float b, final float a, final int x, final int y,
		final int width, final int height) {
		this.ensure(11);
		this.data[this.size++] = OP_CLEAR_COLOR;
		this.data[this.size++] = this.ref(texture);
		this.data[this.size++] = mip;
		this.data[this.size++] = f(r);
		this.data[this.size++] = f(g);
		this.data[this.size++] = f(b);
		this.data[this.size++] = f(a);
		this.data[this.size++] = x;
		this.data[this.size++] = y;
		this.data[this.size++] = width;
		this.data[this.size++] = height;
	}

	public void clearDepth(final DxTexture texture, final int mip, final float depth, final int x, final int y, final int width, final int height) {
		this.ensure(8);
		this.data[this.size++] = OP_CLEAR_DEPTH;
		this.data[this.size++] = this.ref(texture);
		this.data[this.size++] = mip;
		this.data[this.size++] = f(depth);
		this.data[this.size++] = x;
		this.data[this.size++] = y;
		this.data[this.size++] = width;
		this.data[this.size++] = height;
	}

	public void copyBuffer(final DxBuffer src, final long srcOffset, final DxBuffer dst, final long dstOffset, final long length) {
		this.ensure(6);
		this.data[this.size++] = OP_COPY_BUFFER;
		this.data[this.size++] = this.ref(src);
		this.data[this.size++] = srcOffset;
		this.data[this.size++] = this.ref(dst);
		this.data[this.size++] = dstOffset;
		this.data[this.size++] = length;
	}

	public void copyBufferToTexture(final DxBuffer src, final long offset, final int rowTexels, final DxTexture dst, final int layer, final int mip, final int x,
		final int y, final int width, final int height) {
		this.ensure(11);
		this.data[this.size++] = OP_COPY_BUFFER_TO_TEXTURE;
		this.data[this.size++] = this.ref(src);
		this.data[this.size++] = offset;
		this.data[this.size++] = rowTexels;
		this.data[this.size++] = this.ref(dst);
		this.data[this.size++] = layer;
		this.data[this.size++] = mip;
		this.data[this.size++] = x;
		this.data[this.size++] = y;
		this.data[this.size++] = width;
		this.data[this.size++] = height;
	}

	public void copyTextureToBuffer(final DxTexture src, final int mip, final int x, final int y, final int width, final int height, final DxBuffer dst,
		final long offset) {
		this.ensure(9);
		this.data[this.size++] = OP_COPY_TEXTURE_TO_BUFFER;
		this.data[this.size++] = this.ref(src);
		this.data[this.size++] = mip;
		this.data[this.size++] = x;
		this.data[this.size++] = y;
		this.data[this.size++] = width;
		this.data[this.size++] = height;
		this.data[this.size++] = this.ref(dst);
		this.data[this.size++] = offset;
	}

	public void copyTextureToTexture(final DxTexture src, final DxTexture dst, final int mip, final int dstX, final int dstY, final int srcX, final int srcY,
		final int width, final int height) {
		this.ensure(10);
		this.data[this.size++] = OP_COPY_TEXTURE_TO_TEXTURE;
		this.data[this.size++] = this.ref(src);
		this.data[this.size++] = this.ref(dst);
		this.data[this.size++] = mip;
		this.data[this.size++] = dstX;
		this.data[this.size++] = dstY;
		this.data[this.size++] = srcX;
		this.data[this.size++] = srcY;
		this.data[this.size++] = width;
		this.data[this.size++] = height;
	}
}
