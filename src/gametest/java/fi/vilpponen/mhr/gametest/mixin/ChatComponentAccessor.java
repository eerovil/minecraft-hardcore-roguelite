package fi.vilpponen.mhr.gametest.mixin;

import java.util.List;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What the client has actually been told, so a test can check the player was told it.
 *
 * <p>Vanilla's {@code getRecentChat} is the history of what the player typed, not what arrived.
 * Some of this mod's behaviour is a message and nothing else — a save that has stopped, or a run
 * that could not be finished — and "the server did not fall over" is not evidence that anybody was
 * informed. Reading the received messages is the only way to tell a handled failure from an
 * unhandled one that happened to be survivable.
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	@Accessor("allMessages")
	List<GuiMessage> mhr$allMessages();
}
