package fi.vilpponen.mhr.gametest.mixin;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The {@link GameTestInfo} behind a {@link GameTestHelper}, which the helper keeps to itself.
 *
 * <p>Wanted for one thing: {@code GameTestInfo.addListener} is the only hook the framework runs on
 * every way a test can end — passed, failed, or timed out waiting for something that never
 * happened. A test that changes state the whole server shares needs to put it back on all three,
 * and a cleanup step at the end of a sequence only covers the first.
 */
@Mixin(GameTestHelper.class)
public interface GameTestHelperAccessor {
	@Accessor("testInfo")
	GameTestInfo mhr$testInfo();
}
