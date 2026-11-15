package studios.artyonix.dxrender.device;

/** DX Render framebuffer: the attachments, clear values and area of one render pass. */
public final class DxFramebuffer {
	public static final int MAX_COLOR = 8;

	public final DxTextureView[] color = new DxTextureView[MAX_COLOR];
	public int colorCount;
	public DxTextureView depth;
	public final float[] clearColor = new float[MAX_COLOR * 4];
	public int clearColorMask;
	public boolean clearDepth;
	public float clearDepthValue;
	public int x, y, width, height;

	/** Size of the attachments at their bound mip level. */
	public int targetWidth() {
		for (int i = 0; i < this.colorCount; i++) {
			if (this.color[i] != null) {
				return this.color[i].getWidth(0);
			}
		}
		return this.depth != null ? this.depth.getWidth(0) : 0;
	}

	public int targetHeight() {
		for (int i = 0; i < this.colorCount; i++) {
			if (this.color[i] != null) {
				return this.color[i].getHeight(0);
			}
		}
		return this.depth != null ? this.depth.getHeight(0) : 0;
	}
}
