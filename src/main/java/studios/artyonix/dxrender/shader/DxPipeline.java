package studios.artyonix.dxrender.shader;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import studios.artyonix.dxrender.core.DxNative;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.device.DxDevice;
import studios.artyonix.dxrender.device.DxFormats;

/**
 * DX Render Shader System, Java side: hands the SPIR-V the game compiled (vanilla or
 * resource pack shaders alike) to the native interpreter together with the fixed function
 * state of the pipeline.
 */
public final class DxPipeline implements BackendRenderPipeline {
	private final DxDevice device;
	private final long handle;
	private final String name;
	private final int uniformCount;
	private final int[] uniformKind;
	private final long[] uniformFormatBits;
	private final String[] uniformNames;
	private volatile boolean closed;

	private DxPipeline(final DxDevice device, final long handle, final String name, final List<BindGroupLayout.UniformDescription> uniforms) {
		this.device = device;
		this.handle = handle;
		this.name = name;
		this.uniformCount = Math.min(uniforms.size(), DxNative.MAX_UNIFORMS);
		this.uniformKind = new int[this.uniformCount];
		this.uniformFormatBits = new long[this.uniformCount];
		this.uniformNames = new String[this.uniformCount];
		for (int i = 0; i < this.uniformCount; i++) {
			BindGroupLayout.UniformDescription u = uniforms.get(i);
			this.uniformKind[i] = kind(u.type());
			this.uniformNames[i] = u.name();
			GpuFormat f = u.gpuFormat();
			if (f != null) {
				this.uniformFormatBits[i] = (long)DxFormats.componentType(f) << 32 | (long)DxFormats.componentCount(f) << 40;
			}
		}
	}

	public long handle() {
		return this.handle;
	}

	public String name() {
		return this.name;
	}

	public int uniformCount() {
		return this.uniformCount;
	}

	public int uniformKind(final int index) {
		return this.uniformKind[index];
	}

	public long uniformFormatBits(final int index) {
		return this.uniformFormatBits[index];
	}

	public int uniformIndex(final String uniformName) {
		for (int i = 0; i < this.uniformCount; i++) {
			if (this.uniformNames[i].equals(uniformName)) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			long dev = this.device.handle(), h = this.handle;
			this.device.memory().defer(() -> DxNative.nPipelineDestroy(dev, h));
		}
	}

	private static int kind(final UniformType type) {
		return switch (type) {
			case UNIFORM_BUFFER -> DxNative.UNIFORM_BUFFER;
			case COMBINED_IMAGE_SAMPLER -> DxNative.UNIFORM_SAMPLER;
			case TEXEL_BUFFER -> DxNative.UNIFORM_TEXEL_BUFFER;
		};
	}

	private static int topology(final PrimitiveTopology t) {
		return switch (t) {
			case TRIANGLES, QUADS, LINES -> 0; // quads and "lines" arrive as indexed triangles
			case TRIANGLE_STRIP -> 1;
			case TRIANGLE_FAN -> 2;
			case DEBUG_LINES -> 3;
			case DEBUG_LINE_STRIP -> 4;
			case POINTS -> 5;
		};
	}

	private static int compare(final CompareOp op) {
		return switch (op) {
			case ALWAYS_PASS -> 0;
			case LESS_THAN -> 1;
			case LESS_THAN_OR_EQUAL -> 2;
			case EQUAL -> 3;
			case NOT_EQUAL -> 4;
			case GREATER_THAN_OR_EQUAL -> 5;
			case GREATER_THAN -> 6;
			case NEVER_PASS -> 7;
		};
	}

	private static int factor(final BlendFactor f) {
		return switch (f) {
			case CONSTANT_ALPHA -> 0;
			case CONSTANT_COLOR -> 1;
			case DST_ALPHA -> 2;
			case DST_COLOR -> 3;
			case ONE -> 4;
			case ONE_MINUS_CONSTANT_ALPHA -> 5;
			case ONE_MINUS_CONSTANT_COLOR -> 6;
			case ONE_MINUS_DST_ALPHA -> 7;
			case ONE_MINUS_DST_COLOR -> 8;
			case ONE_MINUS_SRC_ALPHA -> 9;
			case ONE_MINUS_SRC_COLOR -> 10;
			case SRC_ALPHA -> 11;
			case SRC_ALPHA_SATURATE -> 12;
			case SRC_COLOR -> 13;
			case ZERO -> 14;
		};
	}

	private static int blendOp(final BlendOp op) {
		return switch (op) {
			case ADD -> 0;
			case SUBTRACT -> 1;
			case REVERSE_SUBTRACT -> 2;
			case MIN -> 3;
			case MAX -> 4;
		};
	}

	/** Little endian writer for the blob described in native/src/dxr_pipeline.c. */
	private static final class Blob {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream(8192);

		void u32(final int v) {
			this.out.write(v);
			this.out.write(v >>> 8);
			this.out.write(v >>> 16);
			this.out.write(v >>> 24);
		}

		void f32(final float v) {
			this.u32(Float.floatToRawIntBits(v));
		}

		void str(final String s) {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			this.u32(b.length);
			this.out.writeBytes(b);
		}

		void words(final ByteBuffer spv) {
			ByteBuffer dup = spv.duplicate().order(ByteOrder.LITTLE_ENDIAN);
			int n = dup.remaining() / 4;
			this.u32(n);
			byte[] bytes = new byte[n * 4];
			dup.get(bytes);
			this.out.writeBytes(bytes);
		}
	}

	public static @Nullable DxPipeline create(final DxDevice device, final BackendRenderPipeline.CreateInfo info) {
		SpvModule vs = null, fs = null;
		for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
			if (shader.module().type() == ShaderType.VERTEX) {
				vs = shader.module();
			} else if (shader.module().type() == ShaderType.FRAGMENT) {
				fs = shader.module();
			}
		}
		if (vs == null || fs == null) {
			DxRender.LOGGER.error("Pipeline {} lacks a vertex or fragment stage", info.name());
			return null;
		}
		if (info.uniforms().size() > DxNative.MAX_UNIFORMS || info.attribBindings().size() > 16) {
			DxRender.LOGGER.error("Pipeline {} exceeds DX Render limits ({} uniforms, {} attributes)", info.name(), info.uniforms().size(), info.attribBindings().size());
			return null;
		}
		Blob b = new Blob();
		b.u32(0x50525844);
		b.str(info.name());
		b.words(vs.spv());
		b.words(fs.spv());
		b.u32(info.attribBindings().size());
		for (BackendRenderPipeline.CreateInfo.AttribBinding a : info.attribBindings()) {
			b.u32(a.bufferSlot());
			b.u32(a.location());
			b.u32(DxFormats.componentType(a.format()));
			b.u32(DxFormats.componentCount(a.format()));
			b.u32(a.offset());
		}
		b.u32(info.vertexBuffers().size());
		for (BackendRenderPipeline.CreateInfo.VertexBuffer v : info.vertexBuffers()) {
			b.u32(v.bufferSlot());
			b.u32(v.stride());
			b.u32(v.stepRate());
		}
		b.u32(info.uniforms().size());
		for (BindGroupLayout.UniformDescription u : info.uniforms()) {
			b.str(u.name());
			b.u32(kind(u.type()));
			GpuFormat f = u.gpuFormat();
			b.u32(f == null ? 0 : DxFormats.componentType(f));
			b.u32(f == null ? 0 : DxFormats.componentCount(f));
		}
		DepthStencilState depth = info.depthStencilState();
		b.u32(depth == null ? 0 : 1);
		b.u32(depth == null ? 0 : compare(depth.depthTest()));
		b.u32(depth != null && depth.writeDepth() ? 1 : 0);
		b.f32(depth == null ? 0.0F : depth.depthBiasScaleFactor());
		b.f32(depth == null ? 0.0F : depth.depthBiasConstant());
		b.u32(info.cull() ? 1 : 0);
		b.u32(topology(info.primitiveTopology()));
		b.u32(info.polygonMode() == PolygonMode.WIREFRAME ? 1 : 0);
		List<ColorTargetState> targets = info.colorTargetStates();
		int nt = Math.min(8, targets.size());
		b.u32(nt);
		for (int i = 0; i < nt; i++) {
			ColorTargetState t = targets.get(i);
			if (t == null) {
				for (int k = 0; k < 9; k++) {
					b.u32(0);
				}
				continue;
			}
			b.u32(1);
			BlendFunction bf = t.blendFunction().orElse(null);
			b.u32(bf == null ? 0 : 1);
			b.u32(t.writeMask());
			BlendEquation c = bf == null ? null : bf.color(), a = bf == null ? null : bf.alpha();
			b.u32(c == null ? 0 : factor(c.sourceFactor()));
			b.u32(c == null ? 0 : factor(c.destFactor()));
			b.u32(c == null ? 0 : blendOp(c.op()));
			b.u32(a == null ? 0 : factor(a.sourceFactor()));
			b.u32(a == null ? 0 : factor(a.destFactor()));
			b.u32(a == null ? 0 : blendOp(a.op()));
		}
		byte[] bytes = b.out.toByteArray();
		long mem = MemoryUtil.nmemAlloc(bytes.length);
		try {
			MemoryUtil.memByteBuffer(mem, bytes.length).put(bytes);
			long handle = DxNative.nPipelineCreate(device.handle(), mem, bytes.length);
			if (handle == 0L) {
				DxRender.LOGGER.error("DX Render could not build pipeline {}: {}", info.name(), DxNative.nLastError());
				return null;
			}
			return new DxPipeline(device, handle, info.name(), info.uniforms());
		} finally {
			MemoryUtil.nmemFree(mem);
		}
	}
}
