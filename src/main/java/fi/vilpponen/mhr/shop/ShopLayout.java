package fi.vilpponen.mhr.shop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.progression.Offer;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How the shop is arranged on screen, read from {@code shop-layout.json}.
 *
 * <p>This is the whole of the "data-driven presentation layer" the screen leans on. It answers two
 * questions and no others: which row does an offer belong in, and what does it look like. Both are
 * data, so the screen holds no list of unlock ids at all and adding a catalogue entry never means
 * editing a {@code switch}.
 *
 * <p>Groups are matched in file order and an offer joins the first one whose prefixes it starts
 * with. The last group in the file matches the empty prefix, so an id nobody has placed yet is
 * still drawn somewhere rather than quietly missing from a shop that looks complete. An id with no
 * icon gets the file's default icon for the same reason.
 *
 * <p>The tier order in the file is the visual hierarchy: vanilla restoration is declared first and
 * is therefore drawn first.
 */
public final class ShopLayout {
	private static final String RESOURCE = "/shop-layout.json";

	/** One row of the shop: a heading and everything the file says belongs under it. */
	public record Group(String id, String tier, List<String> prefixes) {
		boolean accepts(String unlockId) {
			for (String prefix : prefixes) {
				if (unlockId.startsWith(prefix)) {
					return true;
				}
			}
			return false;
		}
	}

	/** A row with the offers that landed in it. Only built for rows that have any. */
	public record Row(String groupId, List<Offer> offers) {
	}

	/** Vanilla restoration, or Vanilla+ — a heading and the rows under it. */
	public record Section(String tierId, List<Row> rows) {
	}

	private static final List<Group> GROUPS;
	private static final Map<String, String> ICONS;
	private static final String DEFAULT_ICON;

	static {
		List<Group> groups = new ArrayList<>();
		Map<String, String> icons = new LinkedHashMap<>();
		String defaultIcon = "minecraft:paper";
		try (InputStream stream = ShopLayout.class.getResourceAsStream(RESOURCE)) {
			if (stream == null) {
				throw new IllegalStateException("not on the classpath");
			}
			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
				defaultIcon = root.get("defaultIcon").getAsString();
				for (JsonElement element : root.getAsJsonArray("groups")) {
					JsonObject group = element.getAsJsonObject();
					List<String> prefixes = new ArrayList<>();
					for (JsonElement prefix : group.getAsJsonArray("prefixes")) {
						prefixes.add(prefix.getAsString());
					}
					groups.add(new Group(group.get("id").getAsString(), group.get("tier").getAsString(),
							List.copyOf(prefixes)));
				}
				for (Map.Entry<String, JsonElement> icon : root.getAsJsonObject("icons").entrySet()) {
					icons.put(icon.getKey(), icon.getValue().getAsString());
				}
			}
		} catch (Exception e) {
			// Loud, and then carry on with one row holding everything. A shop nobody can open is a
			// worse failure than a shop that is arranged badly, and the log line says which it is.
			HardcoreRoguelite.LOGGER.error("Could not read {}; the shop will be one undivided list", RESOURCE, e);
			groups = List.of(new Group("other", "vanilla_plus", List.of("")));
		}
		GROUPS = List.copyOf(groups);
		ICONS = Map.copyOf(icons);
		DEFAULT_ICON = defaultIcon;
	}

	private ShopLayout() {
	}

	/**
	 * Put the server's offers into sections and rows.
	 *
	 * <p>Order comes entirely from the layout file, not from the order the offers arrived in, so the
	 * shop looks the same however the balance file happens to be sorted. Empty rows and empty
	 * sections are dropped, so a build with no starter items in its catalogue has no starter row
	 * rather than an empty one.
	 */
	public static List<Section> arrange(List<Offer> offers) {
		Map<String, List<Row>> byTier = new LinkedHashMap<>();
		// First group wins, so an offer taken by an earlier row is not shown twice.
		Set<String> placed = new HashSet<>();
		for (Group group : GROUPS) {
			List<Offer> mine = new ArrayList<>();
			for (Offer offer : offers) {
				if (group.accepts(offer.id()) && placed.add(offer.id())) {
					mine.add(offer);
				}
			}
			if (!mine.isEmpty()) {
				byTier.computeIfAbsent(group.tier(), tier -> new ArrayList<>())
						.add(new Row(group.id(), List.copyOf(mine)));
			}
		}

		List<Section> sections = new ArrayList<>();
		for (Map.Entry<String, List<Row>> tier : byTier.entrySet()) {
			sections.add(new Section(tier.getKey(), List.copyOf(tier.getValue())));
		}
		return List.copyOf(sections);
	}

	/**
	 * The item an offer is drawn as, as a plain item id.
	 *
	 * <p>A string rather than a stack, because turning one into the other needs the item registry
	 * and this class deliberately stays clear of the game — which is what lets the arrangement be
	 * covered by ordinary unit tests. {@code ShopIcons} on the client does the conversion.
	 *
	 * <p>Never null: an id with no icon in the file gets the file's default, so a catalogue entry
	 * added without one is drawn as something rather than as a hole.
	 */
	public static String iconId(String unlockId) {
		return ICONS.getOrDefault(unlockId, DEFAULT_ICON);
	}

	/** Whether the layout file names an icon for this id, rather than falling back to the default. */
	public static boolean hasIcon(String unlockId) {
		return ICONS.containsKey(unlockId);
	}

	/** Every group in the file, in the order they are drawn. */
	public static List<Group> groups() {
		return GROUPS;
	}
}
