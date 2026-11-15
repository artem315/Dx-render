package studios.artyonix.dxrender.device;

/** DX Render swapchain: takes a finished color texture and puts it on the window. */
public interface DxSwapchain {
	/** Thrown when the window surface cannot be used right now (minimised, lost, being resized). */
	final class SurfaceUnavailable extends Exception {
		public SurfaceUnavailable(final String message) {
			super(message);
		}
	}

	void configure(int width, int height, boolean vsync) throws SurfaceUnavailable;

	void acquire() throws SurfaceUnavailable;

	/** Schedules the given texture level for presentation in the current frame. */
	void blit(DxTexture texture, int mipLevel);

	/** Shows the frame. Called after the frame has been submitted. */
	void present();

	boolean suboptimal();

	/** True when the swapchain itself limits the rate (vsync); false when frames appear as soon as posted. */
	boolean paced();

	boolean supportsImmediate();

	/** Human readable description of how frames reach the screen. */
	String describe();

	void close();
}
