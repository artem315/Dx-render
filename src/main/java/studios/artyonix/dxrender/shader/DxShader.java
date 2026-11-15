package studios.artyonix.dxrender.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import studios.artyonix.dxrender.command.DxDescriptorSet;

/**
 * DX Render shader: one SPIR-V module, checked before any backend sees it.
 *
 * SPIR-V is the only shader representation DX Render accepts. parse() validates the
 * container (magic, version, instruction stream, memory model, entry point) and reflects
 * the resources the module declares. Drivers then ask for the extra guarantees they need:
 * the Vulkan driver calls {@link #checkVulkan} and {@link #withBindings}, which either makes
 * the descriptor decorations match the pipeline or explains why that is impossible.
 */
public final class DxShader {
	public enum Stage {
		VERTEX(0), FRAGMENT(4);

		final int executionModel;

		Stage(final int executionModel) {
			this.executionModel = executionModel;
		}
	}

	/** A descriptor (uniform block, storage block, sampler or texel buffer) declared by the module. */
	public static final class Binding {
		public int variable;
		public String name;
		public int kind;
		public int set = -1, binding = -1;
		int setWord = -1, bindingWord = -1;
	}

	private static final int MAGIC = 0x07230203;

	public final String name;
	public final Stage stage;
	private final int[] words;
	public final int versionMajor, versionMinor;
	public final long hash;
	public final List<Binding> bindings = new ArrayList<>();
	public final Set<Integer> capabilities = new HashSet<>();
	public boolean usesPushConstants;
	public boolean writesPosition;
	/** Fragment stage: bit i set when the shader declares an output at location i. */
	public int outputLocations;
	private int annotationEnd = -1;

	private DxShader(final String name, final Stage stage, final int[] words) {
		this.name = name;
		this.stage = stage;
		this.words = words;
		this.versionMajor = words[1] >>> 16 & 0xFF;
		this.versionMinor = words[1] >>> 8 & 0xFF;
		long h = 0xcbf29ce484222325L;
		for (int w : words) {
			h = (h ^ (w & 0xFFFFFFFFL)) * 0x100000001b3L;
		}
		this.hash = h ^ stage.ordinal();
	}

	public int wordCount() {
		return this.words.length;
	}

	public int[] words() {
		return this.words;
	}

	/** Copies the module into native memory provided by the caller (little endian words). */
	public void copyTo(final ByteBuffer dst) {
		dst.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(this.words);
	}

	private static String string(final int[] w, final int from, final int end) {
		byte[] bytes = new byte[(end - from) * 4];
		int n = 0;
		outer:
		for (int i = from; i < end; i++) {
			for (int b = 0; b < 4; b++) {
				byte c = (byte)(w[i] >>> (8 * b));
				if (c == 0) {
					break outer;
				}
				bytes[n++] = c;
			}
		}
		return new String(bytes, 0, n, StandardCharsets.UTF_8);
	}

	/** Validates the SPIR-V container and reflects its resources. */
	public static DxShader parse(final String name, final Stage stage, final ByteBuffer spirv) throws DxShaderException {
		if (spirv == null || spirv.remaining() < 20 || (spirv.remaining() & 3) != 0) {
			throw new DxShaderException(name + ": not a SPIR-V module (size " + (spirv == null ? 0 : spirv.remaining()) + " bytes)");
		}
		IntBuffer ib = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		int[] w = new int[ib.remaining()];
		ib.get(w);
		if (w[0] == Integer.reverseBytes(MAGIC)) {
			throw new DxShaderException(name + ": SPIR-V module is big endian, which DX Render does not accept");
		}
		if (w[0] != MAGIC) {
			throw new DxShaderException(name + ": bad SPIR-V magic number 0x" + Integer.toHexString(w[0]));
		}
		DxShader s = new DxShader(name, stage, w);
		if (s.versionMajor != 1 || s.versionMinor > 6) {
			throw new DxShaderException(name + ": unsupported SPIR-V version " + s.versionMajor + "." + s.versionMinor);
		}
		int bound = w[3];
		if (bound <= 0 || bound > 4_000_000) {
			throw new DxShaderException(name + ": implausible id bound " + bound);
		}
		Map<Integer, String> names = new HashMap<>();
		Map<Integer, int[]> pointers = new HashMap<>();   // id -> {storage class, pointee}
		Map<Integer, Integer> imageDim = new HashMap<>(); // image type id -> dim
		Map<Integer, Integer> sampledImage = new HashMap<>();
		Map<Integer, Integer> arrayElement = new HashMap<>();
		Set<Integer> bufferBlocks = new HashSet<>();
		Map<Integer, int[]> decorations = new HashMap<>(); // variable -> {set, setWord, binding, bindingWord}
		Map<Integer, Integer> locations = new HashMap<>();
		Map<Integer, Integer> constants = new HashMap<>();
		Map<Integer, Integer> arrayLength = new HashMap<>(); // array type -> length constant id
		List<int[]> variables = new ArrayList<>();          // {id, pointer type, storage class}
		boolean memoryModel = false, entry = false, originLowerLeft = false;
		int pos = 5;
		while (pos < w.length) {
			int op = w[pos] & 0xFFFF, wc = w[pos] >>> 16;
			if (wc == 0 || pos + wc > w.length) {
				throw new DxShaderException(name + ": corrupt instruction stream at word " + pos);
			}
			switch (op) {
				case 17 -> s.capabilities.add(w[pos + 1]);
				case 14 -> {
					memoryModel = true;
					if (w[pos + 1] != 0 || (w[pos + 2] != 1 && w[pos + 2] != 3)) {
						throw new DxShaderException(name + ": only the Logical addressing model with GLSL450/Vulkan memory model is supported");
					}
				}
				case 15 -> {
					if (w[pos + 1] == stage.executionModel) {
						entry = true;
						String entryName = string(w, pos + 3, pos + wc);
						if (!entryName.equals("main")) {
							throw new DxShaderException(name + ": entry point must be called main, found '" + entryName + "'");
						}
					}
				}
				case 16 -> {
					if (wc >= 3 && w[pos + 2] == 8) {
						originLowerLeft = true;
					}
				}
				case 5 -> names.put(w[pos + 1], string(w, pos + 2, pos + wc));
				case 71 -> {
					if (wc >= 4 && (w[pos + 2] == 33 || w[pos + 2] == 34)) {
						int[] d = decorations.computeIfAbsent(w[pos + 1], k -> new int[]{-1, -1, -1, -1});
						if (w[pos + 2] == 34) {
							d[0] = w[pos + 3];
							d[1] = pos + 3;
						} else {
							d[2] = w[pos + 3];
							d[3] = pos + 3;
						}
					} else if (wc >= 3 && w[pos + 2] == 3) {
						bufferBlocks.add(w[pos + 1]);
					} else if (wc >= 4 && w[pos + 2] == 30) {
						locations.put(w[pos + 1], w[pos + 3]);
					}
				}
				case 72 -> {
					if (wc >= 5 && w[pos + 3] == 11 && w[pos + 4] == 0) {
						s.writesPosition = true;
					}
				}
				case 25 -> imageDim.put(w[pos + 1], w[pos + 3]);
				case 27 -> sampledImage.put(w[pos + 1], w[pos + 2]);
				case 28, 29 -> {
					arrayElement.put(w[pos + 1], w[pos + 2]);
					if (op == 28) {
						arrayLength.put(w[pos + 1], w[pos + 3]);
					}
				}
				case 43 -> {
					if (wc >= 4) {
						constants.put(w[pos + 2], w[pos + 3]);
					}
				}
				case 32 -> pointers.put(w[pos + 1], new int[]{w[pos + 2], w[pos + 3]});
				case 59 -> {
					if (wc >= 4) {
						variables.add(new int[]{w[pos + 2], w[pos + 1], w[pos + 3]});
					}
				}
				default -> {
				}
			}
			if (s.annotationEnd < 0 && (op == 1 || (op >= 19 && op <= 33) || op == 39 || (op >= 41 && op <= 52) || op == 59 || op == 54)) {
				s.annotationEnd = pos;
			}
			pos += wc;
		}
		if (!s.capabilities.contains(1)) {
			throw new DxShaderException(name + ": module does not declare the Shader capability");
		}
		if (!memoryModel) {
			throw new DxShaderException(name + ": module has no OpMemoryModel");
		}
		if (!entry) {
			throw new DxShaderException(name + ": module has no " + stage + " entry point");
		}
		if (stage == Stage.FRAGMENT && originLowerLeft) {
			throw new DxShaderException(name + ": fragment shader uses OriginLowerLeft, DX Render requires OriginUpperLeft");
		}
		for (int[] v : variables) {
			if (v[2] == 3 && locations.containsKey(v[0])) {
				int[] ptr = pointers.get(v[1]);
				int span = 1;
				if (ptr != null && arrayLength.containsKey(ptr[1])) {
					span = Math.max(1, constants.getOrDefault(arrayLength.get(ptr[1]), 1));
				}
				int loc = locations.get(v[0]);
				for (int k = 0; k < span && loc + k < 32; k++) {
					s.outputLocations |= 1 << (loc + k);
				}
			}
		}
		for (int[] v : variables) {
			int storage = v[2];
			if (storage == 9) {
				s.usesPushConstants = true;
				continue;
			}
			if (storage == 0 && s.annotationEnd >= 0) {
				// builtin position written through a plain output variable
			}
			if (storage != 0 && storage != 2 && storage != 12) {
				continue;
			}
			int[] ptr = pointers.get(v[1]);
			if (ptr == null) {
				continue;
			}
			int type = ptr[1];
			while (arrayElement.containsKey(type)) {
				type = arrayElement.get(type);
			}
			Binding b = new Binding();
			b.variable = v[0];
			if (storage == 0) {
				int image = sampledImage.getOrDefault(type, type);
				Integer dim = imageDim.get(image);
				if (dim == null) {
					continue; // not an image: plain uniform constants do not exist in Vulkan style SPIR-V
				}
				b.kind = dim == 5 ? DxDescriptorSet.TEXEL_BUFFER : DxDescriptorSet.SAMPLER;
				b.name = names.getOrDefault(v[0], "");
			} else {
				b.kind = storage == 12 || bufferBlocks.contains(type) ? DxDescriptorSet.STORAGE_BUFFER : DxDescriptorSet.UNIFORM_BUFFER;
				String blockName = names.getOrDefault(type, "");
				b.name = blockName.isEmpty() ? names.getOrDefault(v[0], "") : blockName;
			}
			int[] d = decorations.get(v[0]);
			if (d != null) {
				b.set = d[0];
				b.setWord = d[1];
				b.binding = d[2];
				b.bindingWord = d[3];
			}
			s.bindings.add(b);
		}
		if (stage == Stage.VERTEX && !s.writesPosition) {
			// gl_Position may also be a stand-alone variable decorated BuiltIn Position
			pos = 5;
			while (pos < w.length) {
				int op = w[pos] & 0xFFFF, wc = w[pos] >>> 16;
				if (op == 71 && wc >= 4 && w[pos + 2] == 11 && w[pos + 3] == 0) {
					s.writesPosition = true;
					break;
				}
				pos += wc;
			}
			if (!s.writesPosition) {
				throw new DxShaderException(name + ": vertex shader never writes gl_Position");
			}
		}
		return s;
	}

	private static final Set<Integer> BASE_CAPABILITIES = Set.of(0, 1, 40, 43, 44, 46, 47, 50, 51);

	/**
	 * Checks what Vulkan requires beyond container validity: SPIR-V version accepted by the
	 * device's Vulkan version and capabilities backed by enabled device features.
	 */
	public void checkVulkan(final int apiMajor, final int apiMinor, final boolean drawParameters, final boolean imageCubeArray, final boolean clipDistance)
		throws DxShaderException {
		int maxMinor = apiMinor >= 3 ? 6 : apiMinor == 2 ? 5 : apiMinor == 1 ? 3 : 0;
		if (apiMajor == 1 && this.versionMinor > maxMinor) {
			throw new DxShaderException(this.name + ": SPIR-V " + this.versionMajor + "." + this.versionMinor + " needs a newer Vulkan than the device's " + apiMajor
				+ "." + apiMinor + " (which accepts up to 1." + maxMinor + ")");
		}
		for (int cap : this.capabilities) {
			if (BASE_CAPABILITIES.contains(cap)) {
				continue;
			}
			boolean ok = switch (cap) {
				case 4427 -> drawParameters;
				case 34, 45 -> imageCubeArray;
				case 32 -> clipDistance;
				default -> false;
			};
			if (!ok) {
				throw new DxShaderException(this.name + ": SPIR-V capability " + cap + " is not available on this device or not supported by the DX Render GPU backend");
			}
		}
	}

	/**
	 * Returns a module whose descriptors use set 0 and the binding equal to the index of the
	 * uniform with the same name in the pipeline. Existing decorations are rewritten in place;
	 * missing ones are inserted. Fails if the module uses a resource the pipeline does not declare
	 * or declares with another kind.
	 */
	public DxShader withBindings(final String[] uniformNames, final int[] uniformKinds) throws DxShaderException {
		int[] patched = this.words.clone();
		List<int[]> inserts = new ArrayList<>();
		boolean changed = false;
		for (Binding b : this.bindings) {
			int index = -1;
			for (int i = 0; i < uniformNames.length; i++) {
				if (uniformNames[i].equals(b.name)) {
					index = i;
					break;
				}
			}
			if (index < 0) {
				throw new DxShaderException(this.name + ": shader uses '" + b.name + "' which the pipeline does not declare");
			}
			int expected = uniformKinds[index];
			if (expected != b.kind && !(expected == DxDescriptorSet.UNIFORM_BUFFER && b.kind == DxDescriptorSet.STORAGE_BUFFER)) {
				throw new DxShaderException(this.name + ": '" + b.name + "' is declared as kind " + b.kind + " in the shader but as kind " + expected + " in the pipeline");
			}
			if (b.bindingWord >= 0) {
				if (patched[b.bindingWord] != index) {
					patched[b.bindingWord] = index;
					changed = true;
				}
			} else {
				inserts.add(new int[]{4 << 16 | 71, b.variable, 33, index});
			}
			if (b.setWord >= 0) {
				if (patched[b.setWord] != 0) {
					patched[b.setWord] = 0;
					changed = true;
				}
			} else {
				inserts.add(new int[]{4 << 16 | 71, b.variable, 34, 0});
			}
		}
		if (!inserts.isEmpty()) {
			if (this.annotationEnd < 0) {
				throw new DxShaderException(this.name + ": cannot place descriptor decorations (module has no type section)");
			}
			int[] grown = new int[patched.length + inserts.size() * 4];
			System.arraycopy(patched, 0, grown, 0, this.annotationEnd);
			int at = this.annotationEnd;
			for (int[] ins : inserts) {
				System.arraycopy(ins, 0, grown, at, 4);
				at += 4;
			}
			System.arraycopy(patched, this.annotationEnd, grown, at, patched.length - this.annotationEnd);
			patched = grown;
			changed = true;
		}
		if (!changed) {
			return this;
		}
		ByteBuffer bb = ByteBuffer.allocate(patched.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		bb.asIntBuffer().put(patched);
		return parse(this.name, this.stage, bb);
	}
}
