package studios.artyonix.dxrender.integration.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import studios.artyonix.dxrender.gpu.kbase.DxKernelGpu;

/** Runs the experimental kernel GPU probe and shows its report. */
public final class DxKernelProbeScreen extends Screen {
	private final @Nullable Screen parent;
	private int scroll;

	public DxKernelProbeScreen(final @Nullable Screen parent) {
		super(Component.translatable("dxrender.kernel.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int y = this.height - 28;
		this.addRenderableWidget(Button.builder(Component.translatable("dxrender.kernel.run_memory"), b -> this.run(false)).bounds(this.width / 2 - 155, y - 24, 150, 20)
			.build());
		this.addRenderableWidget(Button.builder(Component.translatable("dxrender.kernel.run_commands"), b -> this.run(true))
			.bounds(this.width / 2 + 5, y - 24, 150, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 100, y, 200, 20).build());
	}

	private void run(final boolean commands) {
		if (!DxKernelGpu.isRunning()) {
			this.scroll = 0;
			DxKernelGpu.probe(commands, this.minecraft.gameDirectory.toPath().resolve("dxrender").resolve("gpu-kernel-probe.txt"));
		}
	}

	@Override
	public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
		this.scroll = Math.max(0, this.scroll - (int)Math.signum(scrollY) * 3);
		return true;
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		String report = DxKernelGpu.lastReport();
		List<FormattedCharSequence> lines = new ArrayList<>();
		if (report.isEmpty()) {
			lines.addAll(this.font.split(Component.translatable("dxrender.kernel.intro"), this.width - 20));
		} else {
			for (String l : report.split("\n")) {
				lines.addAll(this.font.split(Component.literal(l), this.width - 20));
			}
		}
		int top = 22, bottom = this.height - 58;
		int visible = Math.max(1, (bottom - top) / 10);
		this.scroll = Math.min(this.scroll, Math.max(0, lines.size() - visible));
		int y = top;
		for (int i = this.scroll; i < lines.size() && y + 9 <= bottom; i++) {
			graphics.text(this.font, lines.get(i), 10, y, 0xFFD8D8D8, false);
			y += 10;
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
