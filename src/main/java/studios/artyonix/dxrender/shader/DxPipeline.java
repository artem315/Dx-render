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
import java.util.List;
import org.jspecify.annotations.Nullable;
import studios.artyonix.dxrender.command.DxDescriptorSet;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.device.DxDevice;
import studios.artyonix.dxrender.device.DxFormats;

/**
 * DX Render pipeline: two validated SPIR-V stages plus the vertex layout and fixed function
 * state, described with DX Render's own enumerations. Drivers attach their compiled object.
 *
 * Numbering used below (shared with the native core):
 * topology 0 triangles, 1 strip, 2 fan, 3 lines, 4 line strip, 5 points;
 * compare 0 always, 1 less, 2 lequal, 3 equal, 4 notequal, 5 gequal, 6 greater, 7 never;
 * blend factors and ops follow the order of dxr.h.
 */
public final class DxPipeline implements BackendRenderPipeline {
	public static final int MAX_UNIFORMS = DxDescriptorSet.MAX_BINDINGS;
	public static final int MAX_TARGETS = 8;

	public final String name;
	public final DxShader vertex, fragment;
	public final int attribCount;
	public final int[] attribSlot, attribLocation, attribType, attribComps, attribOffset;
	public final int vbCount;
	public final int[] vbSlot, vbStride, vbStepRate;
	public final int uniformCount;
	public final String[] uniformName;
	public final int[] uniformKind, uniformType, uniformComps;
	public final int pushConstantSize;
	public final boolean depthTest, depthWrite, cull, wireframe;
	public final int depthFunc, topology;
	public final float depthBiasScale, depthBiasConst;
	public final int targetCount;
	public final boolean[] targetPresent = new boolean[MAX_TARGETS], targetBlend = new boolean[MAX_TARGETS];
	public final int[] targetMask = new int[MAX_TARGETS], srcColor = new int[MAX_TARGETS], dstColor = new int[MAX_TARGETS], opColor = new int[MAX_TARGETS],
		srcAlpha = new int[MAX_TARGETS], dstAlpha = new int[MAX_TARGETS], opAlpha = new int[MAX_TARGETS];
	/** CPU backend: handle of the native pipeline. */
	public long handle;
	/** GPU backend: compiled state. */
	public Object driverData;
	private final DxDevice device;
	private volatile boolean closed;

	private DxPipeline(final DxDevice device, final BackendRenderPipeline.CreateInfo info, final DxShader vs, final DxShader fs) {
		this.device = device;
		this.name = info.name();
		this.vertex = vs;
		this.fragment = fs;
		List<BackendRenderPipeline.CreateInfo.AttribBinding> attribs = info.attribBindings();
		this.attribCount = attribs.size();
		this.attribSlot = new int[this.attribCount];
		this.attribLocation = new int[this.attribCount];
		this.attribType = new int[this.attribCount];
		this.attribComps = new int[this.attribCount];
		this.attribOffset = new int[this.attribCount];
		for (int i = 0; i < this.attribCount; i++) {
			BackendRenderPipeline.CreateInfo.AttribBinding a = attribs.get(i);
			this.attribSlot[i] = a.bufferSlot();
			this.attribLocation[i] = a.location();
			this.attribType[i] = DxFormats.componentType(a.format());
			this.attribComps[i] = DxFormats.componentCount(a.format());
			this.attribOffset[i] = a.offset();
		}
		List<BackendRenderPipeline.CreateInfo.VertexBuffer> vbs = info.vertexBuffers();
		this.vbCount = vbs.size();
		this.vbSlot = new int[this.vbCount];
		this.vbStride = new int[this.vbCount];
		this.vbStepRate = new int[this.vbCount];
		for (int i = 0; i < this.vbCount; i++) {
			this.vbSlot[i] = vbs.get(i).bufferSlot();
			this.vbStride[i] = vbs.get(i).stride();
			this.vbStepRate[i] = vbs.get(i).stepRate();
		}
		List<BindGroupLayout.UniformDescription> uniforms = info.uniforms();
		this.uniformCount = uniforms.size();
		this.uniformName = new String[this.uniformCount];
		this.uniformKind = new int[this.uniformCount];
		this.uniformType = new int[this.uniformCount];
		this.uniformComps = new int[this.uniformCount];
		for (int i = 0; i < this.uniformCount; i++) {
			BindGroupLayout.UniformDescription u = uniforms.get(i);
			this.uniformName[i] = u.name();
			this.uniformKind[i] = kind(u.type());
			GpuFormat f = u.gpuFormat();
			if (f != null) {
				this.uniformType[i] = DxFormats.componentType(f);
				this.uniformComps[i] = DxFormats.componentCount(f);
			}
		}
		this.pushConstantSize = info.pushConstantsSize();
		DepthStencilState depth = info.depthStencilState();
		this.depthTest = depth != null;
		this.depthFunc = depth == null ? 0 : compare(depth.depthTest());
		this.depthWrite = depth != null && depth.writeDepth();
		this.depthBiasScale = depth == null ? 0.0F : depth.depthBiasScaleFactor();
		this.depthBiasConst = depth == null ? 0.0F : depth.depthBiasConstant();
		this.cull = info.cull();
		this.topology = topology(info.primitiveTopology());
		this.wireframe = info.polygonMode() == PolygonMode.WIREFRAME;
		List<ColorTargetState> targets = info.colorTargetStates();
		this.targetCount = Math.min(MAX_TARGETS, targets.size());
		for (int i = 0; i < this.targetCount; i++) {
			ColorTargetState t = targets.get(i);
			if (t == null) {
				continue;
			}
			this.targetPresent[i] = true;
			this.targetMask[i] = t.writeMask();
			BlendFunction bf = t.blendFunction().orElse(null);
			if (bf != null) {
				BlendEquation c = bf.color(), a = bf.alpha();
				this.targetBlend[i] = true;
				this.srcColor[i] = factor(c.sourceFactor());
				this.dstColor[i] = factor(c.destFactor());
				this.opColor[i] = blendOp(c.op());
				this.srcAlpha[i] = factor(a.sourceFactor());
				this.dstAlpha[i] = factor(a.destFactor());
				this.opAlpha[i] = blendOp(a.op());
			}
		}
	}

	public int uniformIndex(final String uniformName) {
		for (int i = 0; i < this.uniformCount; i++) {
			if (this.uniformName[i].equals(uniformName)) {
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
			this.device.releasePipeline(this);
		}
	}

	private static int kind(final UniformType type) {
		return switch (type) {
			case UNIFORM_BUFFER -> DxDescriptorSet.UNIFORM_BUFFER;
			case COMBINED_IMAGE_SAMPLER -> DxDescriptorSet.SAMPLER;
			case TEXEL_BUFFER -> DxDescriptorSet.TEXEL_BUFFER;
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

	/** Validates the SPIR-V of both stages and asks the active driver to compile the pipeline. Returns null (after logging why) on failure. */
	public static @Nullable DxPipeline create(final DxDevice device, final BackendRenderPipeline.CreateInfo info) {
		try {
			DxShader vs = null, fs = null;
			for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
				if (shader.module().type() == ShaderType.VERTEX) {
					vs = DxShader.parse(shader.name(), DxShader.Stage.VERTEX, shader.module().spv());
				} else if (shader.module().type() == ShaderType.FRAGMENT) {
					fs = DxShader.parse(shader.name(), DxShader.Stage.FRAGMENT, shader.module().spv());
				}
			}
			if (vs == null || fs == null) {
				throw new DxShaderException("pipeline needs a vertex and a fragment stage");
			}
			if (info.uniforms().size() > MAX_UNIFORMS || info.attribBindings().size() > 16 || info.vertexBuffers().size() > 8) {
				throw new DxShaderException("exceeds DX Render limits (" + info.uniforms().size() + " uniforms, " + info.attribBindings().size() + " attributes)");
			}
			DxPipeline pipeline = new DxPipeline(device, info, vs, fs);
			device.driver().createPipeline(pipeline);
			return pipeline;
		} catch (DxShaderException e) {
			DxRender.LOGGER.error("DX Render could not build pipeline {}: {}", info.name(), e.getMessage());
			return null;
		} catch (RuntimeException e) {
			DxRender.LOGGER.error("DX Render could not build pipeline {}", info.name(), e);
			return null;
		}
	}
}
