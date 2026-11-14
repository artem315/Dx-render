package studios.artyonix.dxrender.device;

import java.util.concurrent.locks.LockSupport;

/**
 * Frame Scheduler, presentation side. A CPU renderer produces frames at uneven intervals;
 * showing each one the instant it is ready makes motion judder. The pacer delays
 * presentation to a steady cadence: the smoothed render time rounded up to a whole number
 * of display refresh periods. It never makes frames faster, it only evens them out.
 */
public final class DxFramePacer {
	private double smoothedWorkNs;
	private long nextPresentNs;
	private long lastPresentNs;

	/** Blocks until the frame should be shown. refreshHz may be 0 when unknown. */
	public void pace(final float refreshHz) {
		long now = System.nanoTime();
		long periodNs = (long)(1.0e9 / (refreshHz >= 20.0F ? refreshHz : 60.0F));
		if (this.lastPresentNs == 0L) {
			this.lastPresentNs = now;
			this.nextPresentNs = now;
			return;
		}
		long work = now - this.lastPresentNs;
		this.smoothedWorkNs = this.smoothedWorkNs == 0.0 ? work : this.smoothedWorkNs * 0.9 + work * 0.1;
		long slots = Math.max(1L, (long)Math.ceil((this.smoothedWorkNs * 0.97) / periodNs));
		long interval = slots * periodNs;
		long target = this.nextPresentNs + interval;
		if (target < now - interval || target > now + 4 * interval) {
			target = now; // fell behind (or the cadence changed): resynchronise instead of sleeping in bursts
		}
		long wait = target - now;
		if (wait > 200_000L) {
			LockSupport.parkNanos(wait - 100_000L);
			while (System.nanoTime() < target) {
				Thread.onSpinWait();
			}
		}
		this.nextPresentNs = Math.max(target, System.nanoTime() - interval);
		this.lastPresentNs = System.nanoTime();
	}

	public void reset() {
		this.lastPresentNs = 0L;
		this.smoothedWorkNs = 0.0;
	}
}
