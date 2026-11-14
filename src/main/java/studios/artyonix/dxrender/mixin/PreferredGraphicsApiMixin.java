package studios.artyonix.dxrender.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import studios.artyonix.dxrender.core.DxConfig;
import studios.artyonix.dxrender.core.DxRender;
import studios.artyonix.dxrender.device.DxBackend;
import studios.artyonix.dxrender.integration.DxRenderClient;

/**
 * The game tries graphics backends in the order returned here. DX Render is put first; the
 * game's own backends stay behind it only as a start-up fallback (and only if allowed).
 */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMixin {
	@Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
	private void dxrender$prependBackend(final CallbackInfoReturnable<GpuBackend[]> cir) {
		DxRenderClient.ensureConfigLoaded();
		DxConfig cfg = DxConfig.get();
		if (!cfg.enabled) {
			DxRender.setInactive("disabled in config/dxrender.json");
			return;
		}
		GpuBackend[] vanilla = cir.getReturnValue();
		if (!cfg.fallbackToVanilla) {
			cir.setReturnValue(new GpuBackend[]{new DxBackend()});
			return;
		}
		GpuBackend[] out = new GpuBackend[vanilla.length + 1];
		out[0] = new DxBackend();
		System.arraycopy(vanilla, 0, out, 1, vanilla.length);
		cir.setReturnValue(out);
	}
}
