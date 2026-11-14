package studios.artyonix.dxrender.device;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.OptionalLong;

/** Timestamp queries are answered with the host clock: the "GPU" is this process. */
public final class DxQueryPool implements GpuQueryPool {
	private final long[] values;
	private final boolean[] written;

	DxQueryPool(final int size) {
		this.values = new long[size];
		this.written = new boolean[size];
	}

	public void writeTimestamp(final int index) {
		this.values[index] = System.nanoTime();
		this.written[index] = true;
	}

	@Override
	public int size() {
		return this.values.length;
	}

	@Override
	public OptionalLong getValue(final int index) {
		return this.written[index] ? OptionalLong.of(this.values[index]) : OptionalLong.empty();
	}

	@Override
	public OptionalLong[] getValues(final int index, final int count) {
		if (index + count > this.values.length) {
			throw new IndexOutOfBoundsException("getValues(" + index + ", " + count + ") exceeds pool of " + this.values.length);
		}
		OptionalLong[] result = new OptionalLong[count];
		for (int i = 0; i < count; i++) {
			result[i] = this.getValue(index + i);
		}
		return result;
	}

	@Override
	public void close() {
	}
}
