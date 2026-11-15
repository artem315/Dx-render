package studios.artyonix.dxrender.shader;

/** A shader module or pipeline was rejected; the message says which rule it broke. */
public final class DxShaderException extends Exception {
	public DxShaderException(final String message) {
		super(message);
	}
}
