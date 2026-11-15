package studios.artyonix.dxrender.integration.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import studios.artyonix.dxrender.core.DxConfig;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.device.DxDevice;

/**
 * DX Render settings. Every control here changes something real; options that only apply
 * to one backend say so in their tooltip. The render mode is read at start-up, so changing
 * it asks for a restart instead of pretending to switch live.
 */
public final class DxSettingsScreen extends Screen {
	private static final int[] TILE_SIZES = {16, 32, 64, 128};

	private final @Nullable Screen parent;
	private final DxConfig cfg = DxConfig.get();
	private final DxConfig.RenderMode startMode;
	private final List<Runnable> refreshers = new ArrayList<>();

	public DxSettingsScreen(final @Nullable Screen parent) {
		super(Component.translatable("dxrender.settings.title"));
		this.parent = parent;
		this.startMode = this.cfg.renderMode;
	}

	private static Component t(final String key) {
		return Component.translatable("dxrender.settings." + key);
	}

	private static Tooltip tip(final String key) {
		return Tooltip.create(Component.translatable("dxrender.settings." + key + ".tooltip"));
	}

	private void markCustom() {
		this.cfg.preset = DxConfig.Preset.CUSTOM;
	}

	@Override
	protected void init() {
		this.refreshers.clear();
		int left = this.width / 2 - 155, right = this.width / 2 + 5, y = 44, row = 24;

		CycleButton<DxConfig.RenderMode> mode = CycleButton.<DxConfig.RenderMode>builder(m -> t("mode." + m.name().toLowerCase()), this.cfg.renderMode)
			.withValues(DxConfig.RenderMode.values()).withTooltip(m -> tip("mode")).create(left, y, 150, 20, t("mode"), (b, v) -> this.cfg.renderMode = v);
		this.addRenderableWidget(mode);
		CycleButton<DxConfig.Preset> preset = CycleButton.<DxConfig.Preset>builder(p -> t("preset." + p.name().toLowerCase()), this.cfg.preset)
			.withValues(DxConfig.Preset.values()).withTooltip(p -> tip("preset")).create(right, y, 150, 20, t("preset"), (b, v) -> {
				if (v != DxConfig.Preset.CUSTOM) {
					this.cfg.applyPreset(v);
					this.rebuildWidgets();
				}
			});
		this.addRenderableWidget(preset);
		y += row;

		int cpus = DxConfig.cpuCount();
		List<Integer> threads = new ArrayList<>();
		for (int i = 0; i <= cpus; i++) {
			threads.add(i);
		}
		this.addRenderableWidget(CycleButton.<Integer>builder(n -> n == 0 ? Component.translatable("dxrender.settings.threads.auto", cpus) : Component.literal(String.valueOf(n)),
			Math.min(this.cfg.workerThreads, cpus)).withValues(threads).withTooltip(n -> tip("threads")).create(left, y, 150, 20, t("threads"), (b, v) -> {
				this.cfg.workerThreads = v;
				this.markCustom();
			}));
		List<Integer> tiles = new ArrayList<>();
		for (int s : TILE_SIZES) {
			tiles.add(s);
		}
		this.addRenderableWidget(CycleButton.<Integer>builder(n -> Component.literal(n + " px"), this.cfg.tileSize).withValues(tiles).withTooltip(n -> tip("tile"))
			.create(right, y, 150, 20, t("tile"), (b, v) -> {
				this.cfg.tileSize = v;
				this.markCustom();
			}));
		y += row;

		this.addRenderableWidget(CycleButton.onOffBuilder(this.cfg.textureCache).withTooltip(v -> tip("texture_cache")).create(left, y, 150, 20, t("texture_cache"),
			(b, v) -> {
				this.cfg.textureCache = v;
				this.markCustom();
			}));
		this.addRenderableWidget(CycleButton.onOffBuilder(this.cfg.occlusionCulling).withTooltip(v -> tip("occlusion")).create(right, y, 150, 20, t("occlusion"),
			(b, v) -> {
				this.cfg.occlusionCulling = v;
				this.markCustom();
			}));
		y += row;

		this.addRenderableWidget(CycleButton.onOffBuilder(this.cfg.fastShaders).withTooltip(v -> tip("fast_shaders")).create(left, y, 150, 20, t("fast_shaders"),
			(b, v) -> {
				this.cfg.fastShaders = v;
				this.markCustom();
			}));
		this.addRenderableWidget(CycleButton.onOffBuilder(this.cfg.framePacing).withTooltip(v -> tip("pacing")).create(right, y, 150, 20, t("pacing"), (b, v) -> {
			this.cfg.framePacing = v;
			this.markCustom();
		}));
		y += row;

		this.addRenderableWidget(CycleButton.onOffBuilder(this.cfg.debugOverlay).withTooltip(v -> tip("overlay")).create(left, y, 150, 20, t("overlay"),
			(b, v) -> this.cfg.debugOverlay = v));
		this.addRenderableWidget(CycleButton.onOffBuilder(this.cfg.benchmarkLog).withTooltip(v -> tip("bench")).create(right, y, 150, 20, t("bench"),
			(b, v) -> this.cfg.benchmarkLog = v));
		y += row;

		this.addRenderableWidget(Button.builder(t("kernel"), b -> this.minecraft.gui.setScreen(new DxKernelProbeScreen(this))).tooltip(tip("kernel"))
			.bounds(this.width / 2 - 155, this.height - 28, 150, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 + 5, this.height - 28, 150, 20).build());
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
		DxDevice dev = DxRender.device();
		String active = dev == null ? "-" : dev.driver().backendName() + ": " + dev.driver().caps().deviceName();
		graphics.centeredText(this.font, Component.translatable("dxrender.settings.active", active), this.width / 2, 26, 0xFFA0A0A0);
		int y = 44 + 24 * 5 + 6;
		if (this.cfg.renderMode != this.startMode) {
			graphics.centeredText(this.font, t("restart"), this.width / 2, y, 0xFFFFD050);
			y += 12;
		}
		if (dev != null) {
			// as many measurement lines as fit above the Done button
			int limit = this.height - 32;
			for (String line : DxOverlay.lines(dev)) {
				if (y + 9 > limit) {
					break;
				}
				graphics.centeredText(this.font, line, this.width / 2, y, 0xFFC8C8C8);
				y += 10;
			}
		}
	}

	@Override
	public void onClose() {
		DxConfig.save();
		DxDevice dev = DxRender.device();
		if (dev != null) {
			dev.applyConfig();
			dev.profiler().setBenchmarkFile(this.cfg.benchmarkLog ? this.minecraft.gameDirectory.toPath().resolve("dxrender-bench.csv") : null);
		}
		this.minecraft.gui.setScreen(this.parent);
	}
}
