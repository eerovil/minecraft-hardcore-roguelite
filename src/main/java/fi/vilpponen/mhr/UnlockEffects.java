package fi.vilpponen.mhr;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/**
 * "Something was bought or taken away — features, have another look."
 *
 * <p>Most unlocks need nothing here. Worldgen asks {@link UnlockState} when it generates a chunk
 * and the answer is simply different next time. A few are about state that already exists: an
 * equipment slot that just closed has to give back what is in it and tell the clients, and the
 * world border has to be the size the player has now paid for. Those features register here at
 * init, and whoever changed the state — the shop, or the dev command — fires this once afterwards.
 *
 * <p>It lives next to {@link UnlockState} rather than in the shop on purpose. Both the shop and the
 * dev command have to fire it, and a feature must never have to know which of the two happened, so
 * neither of them can own the list. The direction stays feature to progression: features register
 * themselves, and nothing here knows what any of them do.
 *
 * <p>Listeners run on whatever thread changed the state, which is the server thread for both
 * callers today. A listener that has to touch the world should hand itself back to the server the
 * way {@code WorldBorders} does.
 */
public final class UnlockEffects {
	private static final List<Consumer<MinecraftServer>> listeners = new CopyOnWriteArrayList<>();

	private UnlockEffects() {
	}

	/** Called once per feature, from its own {@code register}/{@code init}. */
	public static void onChange(Consumer<MinecraftServer> listener) {
		listeners.add(listener);
	}

	/**
	 * @param server the running server, or null when there is none — a listener is still told, since
	 *     some of what has to be re-decided is not a property of a world.
	 */
	public static void applyAll(MinecraftServer server) {
		for (Consumer<MinecraftServer> listener : listeners) {
			listener.accept(server);
		}
	}
}
