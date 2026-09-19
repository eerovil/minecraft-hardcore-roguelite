package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.ore.Ore;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Locked ores have no large veins either.
 *
 * <p>The big iron and copper veins deep underground do not come from an ore feature at all. In 26.3
 * they are a material rule, applied while the chunk's blocks are being chosen, and they place raw
 * ore blocks as well as ore. So they need their own stop, or a locked iron unlock would still leave
 * a vein's worth of iron in the ground.
 *
 * <p>The rule is compiled once per chunk, so this is both cheap and correctly scoped: a vein is
 * decided when its chunk is generated, like everything else here. A locked vein is dropped whole,
 * filler stone included — there is no vein rather than an empty shell of one.
 */
@Mixin(OreVeinRule.class)
public class OreVeinMixin {
	private static final RuleEvaluator NO_VEIN = (x, y, z) -> null;

	@Inject(method = "compile", at = @At("HEAD"), cancellable = true)
	private void hardcoreRoguelite$blockLockedVeins(MaterialRuleContext context,
			CallbackInfoReturnable<RuleEvaluator> cir) {
		OreVeinRule self = (OreVeinRule) (Object) this;
		if (Ore.isSuppressed(self.oreBlock()) || Ore.isSuppressed(self.rawOreBlock())) {
			cir.setReturnValue(NO_VEIN);
		}
	}
}
