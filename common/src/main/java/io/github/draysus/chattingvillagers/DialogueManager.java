package io.github.draysus.chattingvillagers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.github.draysus.chattingvillagers.platform.Services;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

public class DialogueManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type SOLO_LIST_TYPE = new TypeToken<List<SoloLine>>() {}.getType();
	private static final Type CONVERSATION_LIST_TYPE = new TypeToken<List<Conversation>>() {}.getType();
	private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

	private static final String SOLO_FILE = "solo.json";
	private static final String CONVERSATIONS_FILE = "conversations.json";
	private static final String REACTIONS_FILE = "reactions.json";
	private static final String NAMES_FILE = "names.json";
	private static final String MESSAGES_FILE = "messages.json";

	// Content update 1: new lines live in their own files, so that players who already have
	// the original files in their config folder receive them too (existing files are never touched).
	private static final String SOLO_UPDATE_1 = "solo_update1.json";
	private static final String CONVERSATIONS_UPDATE_1 = "conversations_update1.json";
	private static final String REACTIONS_UPDATE_1 = "reactions_update1.json";

	// File name prefixes that decide what a file in the dialogue folder contains.
	private static final String CONVERSATIONS_PREFIX = "conversations";
	private static final String REACTIONS_PREFIX = "reactions";

	/**
	 * Catalogue files shipped inside the mod jar under /dialogue/<language>/ and copied into
	 * the config folder when they are missing. A later content update adds its own files here
	 * (for example "conversations_update2.json"): existing installations do not have them yet,
	 * so they are created automatically, while every file that is already present stays untouched.
	 */
	private static final String[] PACKAGED_FILES = {
			SOLO_FILE, CONVERSATIONS_FILE, REACTIONS_FILE, NAMES_FILE, MESSAGES_FILE,
			SOLO_UPDATE_1, CONVERSATIONS_UPDATE_1, REACTIONS_UPDATE_1
	};

	/**
	 * How a *.json file in the dialogue folder is used is decided by its name:
	 * names.json and messages.json are special files, names starting with "conversations"
	 * are conversation pools, names starting with "reactions" are reaction pools,
	 * and every other *.json is a solo pool. Players can add their own files the same way.
	 */
	private static final Set<String> SPECIAL_FILES = Set.of(NAMES_FILE, MESSAGES_FILE);

	/**
	 * Optional "category" of a line or conversation. Categories listed in the config's
	 * disabledCategories are not loaded. Lines without a category are always loaded.
	 * Built-in categories: popculture (films, series, books, anime, games), insider
	 * (community in-jokes and memes) and realworld (real events). Servers may use their own.
	 */
	public static final String CATEGORY_POPCULTURE = "popculture";

	// Lines from older catalogue files carry no category; pop culture references are
	// recognised by these parts of their id (for example "ref_gandalf" or "react_movie_2").
	private static final Set<String> LEGACY_POPCULTURE_ID_PARTS = Set.of("ref", "pop", "anime", "movie");
	// Fourth-wall jokes about Minecraft itself stay in the game world and are never categorised.
	private static final Set<String> LEGACY_UNCATEGORISED_ID_PARTS = Set.of("meta", "blocky");

	/** Language whose packaged catalogue is used when the requested one is not shipped. */
	private static final String FALLBACK_LANGUAGE = "en_us";

	private final List<SoloLine> soloLines = new ArrayList<>();
	private final List<SoloLine> reactionLines = new ArrayList<>();
	private final List<Conversation> conversations = new ArrayList<>();

	// Language-dependent display names and command messages (from names.json / messages.json).
	private final Map<String, String> professionNames = new HashMap<>();
	private final Map<String, String> messages = new HashMap<>();
	private String nameFallback = "Villager";

	// Categories that are skipped while loading (lower case), and how many entries were skipped.
	private final Set<String> disabledCategories = new HashSet<>();
	private int skippedByCategory = 0;

	/** Reloads everything using the language, sources and categories from the config. */
	public void reload(ModConfig config) {
		reload(config.language, config.builtInDialogue, config.disabledCategories);
	}

	public void reload(String language) {
		reload(language, true, null);
	}

	/**
	 * @param builtInDialogue    false = never create the mod's own line files (solo, conversations,
	 *                           reactions); only names.json and messages.json are still created.
	 * @param disabledCategories categories whose lines are not loaded (may be null).
	 */
	public void reload(String language, boolean builtInDialogue, Collection<String> disabledCategories) {
		this.disabledCategories.clear();
		if (disabledCategories != null) {
			for (String category : disabledCategories) {
				if (category != null && !category.isBlank()) {
					this.disabledCategories.add(category.trim().toLowerCase(Locale.ROOT));
				}
			}
		}
		skippedByCategory = 0;

		soloLines.clear();
		reactionLines.clear();
		conversations.clear();
		professionNames.clear();
		messages.clear();
		nameFallback = "Villager";

		Path dialogueDir = Services.PLATFORM.getConfigDir()
				.resolve("chattingvillagers").resolve("dialogue").resolve(language);

		try {
			Files.createDirectories(dialogueDir);

			// On first start, copy the catalogue shipped in the jar into the config folder.
			// Existing files are NEVER touched: custom lines survive every update.
			// With builtInDialogue = false only the names and messages are created, so a server
			// can replace the mod's lines completely with its own files.
			for (String fileName : PACKAGED_FILES) {
				if (!builtInDialogue && !SPECIAL_FILES.contains(fileName)) {
					continue;
				}
				seedIfMissing(dialogueDir.resolve(fileName), language, fileName);
			}

			// Sort every *.json into its pool by file name (see SPECIAL_FILES).
			List<Path> jsonFiles;
			try (Stream<Path> files = Files.list(dialogueDir)) {
				jsonFiles = files
						.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
						.sorted()
						.toList();
			}

			boolean anyConversations = false;
			boolean anyReactions = false;
			for (Path file : jsonFiles) {
				String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
				if (SPECIAL_FILES.contains(name)) {
					continue;
				}
				if (name.startsWith(CONVERSATIONS_PREFIX)) {
					loadConversationsFrom(file);
					anyConversations = true;
				} else if (name.startsWith(REACTIONS_PREFIX)) {
					loadSoloInto(file, reactionLines);
					anyReactions = true;
				} else {
					loadSoloInto(file, soloLines);
				}
			}
			if (!anyConversations) {
				ChattingVillagers.LOGGER.warn("[Chatting Villagers] No conversation files found - no conversations loaded.");
			}
			if (!anyReactions) {
				ChattingVillagers.LOGGER.warn("[Chatting Villagers] No reaction files found - no reactions loaded.");
			}

			loadNames(dialogueDir);
			loadMessages(dialogueDir);

		} catch (Exception e) {
			ChattingVillagers.LOGGER.error("[Chatting Villagers] Could not access the dialogue directory.", e);
		}

		ChattingVillagers.LOGGER.info(
				"[Chatting Villagers] Loaded {} solo lines, {} reactions, {} conversations (language: {}).",
				soloLines.size(), reactionLines.size(), conversations.size(), language);
		if (skippedByCategory > 0) {
			ChattingVillagers.LOGGER.info(
					"[Chatting Villagers] Skipped {} entries from disabled categories {}.",
					skippedByCategory, this.disabledCategories);
		}
	}

	/** Effective category: the one given in the file, or one derived from the id of older lines. */
	static String categoryOf(String category, String id) {
		if (category != null && !category.isBlank()) {
			return category.trim().toLowerCase(Locale.ROOT);
		}
		if (id == null || id.isBlank()) {
			return "";
		}
		List<String> parts = Arrays.asList(id.toLowerCase(Locale.ROOT).split("_"));
		for (String part : parts) {
			if (LEGACY_UNCATEGORISED_ID_PARTS.contains(part)) {
				return "";
			}
		}
		for (String part : parts) {
			if (LEGACY_POPCULTURE_ID_PARTS.contains(part)) {
				return CATEGORY_POPCULTURE;
			}
		}
		return "";
	}

	private boolean isDisabled(String category, String id) {
		if (disabledCategories.isEmpty()) {
			return false;
		}
		String effective = categoryOf(category, id);
		if (!effective.isEmpty() && disabledCategories.contains(effective)) {
			skippedByCategory++;
			return true;
		}
		return false;
	}

	/**
	 * Creates a catalogue file if it is missing. The source is the template shipped in the jar
	 * at /dialogue/<language>/<file>; if the file already exists, nothing happens.
	 * Only when the jar holds no template (for a language that is not shipped, for example)
	 * does the built-in minimal catalogue act as an emergency fallback.
	 */
	private void seedIfMissing(Path target, String language, String fileName) {
		if (Files.exists(target)) {
			return;
		}
		try (InputStream in = openPackaged(language, fileName)) {
			if (in != null) {
				Files.copy(in, target);
				ChattingVillagers.LOGGER.info(
						"[Chatting Villagers] Created {} from the mod jar (language: {}).", fileName, language);
				return;
			}
		} catch (Exception e) {
			ChattingVillagers.LOGGER.error("[Chatting Villagers] Could not create {}.", fileName, e);
			return;
		}

		String fallback = builtInFallback(fileName);
		if (fallback == null) {
			ChattingVillagers.LOGGER.warn(
					"[Chatting Villagers] No packaged template found for {} (language: {}).", fileName, language);
			return;
		}
		try {
			Files.writeString(target, fallback, StandardCharsets.UTF_8);
			ChattingVillagers.LOGGER.warn(
					"[Chatting Villagers] No packaged template for {} (language: {}) - wrote built-in minimal catalog.",
					fileName, language);
		} catch (Exception e) {
			ChattingVillagers.LOGGER.error("[Chatting Villagers] Could not create {}.", fileName, e);
		}
	}

	/** Template from the jar: the requested language first, then the fallback language. */
	private static InputStream openPackaged(String language, String fileName) {
		InputStream in = DialogueManager.class.getResourceAsStream("/dialogue/" + language + "/" + fileName);
		if (in == null && !FALLBACK_LANGUAGE.equals(language)) {
			in = DialogueManager.class.getResourceAsStream("/dialogue/" + FALLBACK_LANGUAGE + "/" + fileName);
		}
		return in;
	}

	private static String builtInFallback(String fileName) {
		return switch (fileName) {
			case SOLO_FILE -> DEFAULT_SOLO_JSON;
			case CONVERSATIONS_FILE -> DEFAULT_CONVERSATIONS_JSON;
			case REACTIONS_FILE -> DEFAULT_REACTIONS_JSON;
			case NAMES_FILE -> DEFAULT_NAMES_JSON;
			case MESSAGES_FILE -> DEFAULT_MESSAGES_JSON;
			default -> null;
		};
	}

	private void loadSoloInto(Path file, List<SoloLine> target) {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			List<SoloLine> lines = GSON.fromJson(reader, SOLO_LIST_TYPE);
			if (lines != null) {
				for (SoloLine line : lines) {
					if (line != null && line.text != null && !line.text.isBlank()) {
						if (isDisabled(line.category, line.id)) {
							continue;
						}
						if (!Double.isFinite(line.weight) || line.weight <= 0.0) {
							line.weight = 1.0;
						}
						target.add(line);
					}
				}
			}
		} catch (Exception e) {
			ChattingVillagers.LOGGER.error("[Chatting Villagers] Failed to read {}", file.getFileName(), e);
		}
	}

	private void loadConversationsFrom(Path convFile) {
		try (Reader reader = Files.newBufferedReader(convFile, StandardCharsets.UTF_8)) {
			List<Conversation> convs = GSON.fromJson(reader, CONVERSATION_LIST_TYPE);
			if (convs != null) {
				for (Conversation conv : convs) {
					if (conv == null || conv.lines == null || conv.lines.isEmpty()) {
						continue;
					}
					boolean hasText = false;
					for (ConversationLine cl : conv.lines) {
						if (cl != null && cl.text != null && !cl.text.isBlank()) {
							hasText = true;
							break;
						}
					}
					if (!hasText || isDisabled(conv.category, conv.id)) {
						continue;
					}
					if (!Double.isFinite(conv.weight) || conv.weight <= 0.0) {
						conv.weight = 1.0;
					}
					conversations.add(conv);
				}
			}
		} catch (Exception e) {
			ChattingVillagers.LOGGER.error("[Chatting Villagers] Failed to read {}", convFile.getFileName(), e);
		}
	}

	private void loadNames(Path dialogueDir) {
		Path file = dialogueDir.resolve(NAMES_FILE);
		if (Files.notExists(file)) {
			ChattingVillagers.LOGGER.warn("[Chatting Villagers] {} is missing - profession names not loaded.", NAMES_FILE);
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Names loaded = GSON.fromJson(reader, Names.class);
			if (loaded == null) {
				return;
			}
			if (loaded.fallback != null && !loaded.fallback.isBlank()) {
				nameFallback = loaded.fallback;
			}
			if (loaded.professions != null) {
				loaded.professions.forEach((key, value) -> {
					if (key != null && value != null && !value.isBlank()) {
						professionNames.put(key.toLowerCase(Locale.ROOT), value);
					}
				});
			}
		} catch (Exception e) {
			ChattingVillagers.LOGGER.error("[Chatting Villagers] Failed to read {}", NAMES_FILE, e);
		}
	}

	private void loadMessages(Path dialogueDir) {
		Path file = dialogueDir.resolve(MESSAGES_FILE);
		if (Files.notExists(file)) {
			ChattingVillagers.LOGGER.warn("[Chatting Villagers] {} is missing - command messages not loaded.", MESSAGES_FILE);
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Map<String, String> loaded = GSON.fromJson(reader, STRING_MAP_TYPE);
			if (loaded != null) {
				loaded.forEach((key, value) -> {
					if (key != null && value != null) {
						messages.put(key, value);
					}
				});
			}
		} catch (Exception e) {
			ChattingVillagers.LOGGER.error("[Chatting Villagers] Failed to read {}", MESSAGES_FILE, e);
		}
	}

	/** Display name for a profession; unknown ones (including "none"/"nitwit") return the fallback. */
	public String professionName(String professionPath) {
		if (professionPath == null) {
			return nameFallback;
		}
		return professionNames.getOrDefault(professionPath.toLowerCase(Locale.ROOT), nameFallback);
	}

	/** Command message for a key. If the key is missing, the key itself is returned. */
	public String rawMessage(String key) {
		String value = messages.get(key);
		return (value == null || value.isBlank()) ? key : value;
	}

	/** Command message with placeholders: message("debug").with("time", x).toString() */
	public MessageBuilder message(String key) {
		return new MessageBuilder(rawMessage(key));
	}

	public static final class MessageBuilder {
		private String text;

		private MessageBuilder(String text) {
			this.text = text;
		}

		public MessageBuilder with(String placeholder, Object value) {
			this.text = this.text.replace("{" + placeholder + "}", String.valueOf(value));
			return this;
		}

		@Override
		public String toString() {
			return text;
		}
	}

	public int getSoloCount() {
		return soloLines.size();
	}

	public int getReactionCount() {
		return reactionLines.size();
	}

	public int getConversationCount() {
		return conversations.size();
	}

	public SoloLine pickSolo(VillagerContext ctx, String excludeId) {
		return pickWeighted(soloLines, ctx, excludeId);
	}

	public SoloLine pickReaction(VillagerContext ctx, String excludeId) {
		return pickWeighted(reactionLines, ctx, excludeId);
	}

	// Shared weighted random pick among the matching lines.
	// Weights are decimals: 1 = normal, 0.2 = rare, 0.1 = very rare.
	// Only the ratios matter, not the absolute values.
	/**
	 * Reaction for a villager that can trade (its trade menu opens at the same time).
	 * Only lines written for a specific profession qualify, because the general reactions
	 * are meant for jobless villagers and nitwits, who cannot trade.
	 */
	public SoloLine pickTradeReaction(VillagerContext ctx) {
		List<SoloLine> professionLines = new ArrayList<>();
		for (SoloLine line : reactionLines) {
			if (line.conditions != null && line.conditions.profession != null
					&& !line.conditions.profession.isEmpty()) {
				professionLines.add(line);
			}
		}
		return pickWeighted(professionLines, ctx, null);
	}

	private SoloLine pickWeighted(List<SoloLine> pool, VillagerContext ctx, String excludeId) {
		List<SoloLine> matching = new ArrayList<>();
		for (SoloLine line : pool) {
			if (matches(line, ctx)) {
				matching.add(line);
			}
		}
		if (matching.isEmpty()) {
			return null;
		}
		if (excludeId != null && matching.size() > 1) {
			matching.removeIf(line -> excludeId.equals(line.id));
		}

		double totalWeight = 0.0;
		for (SoloLine line : matching) {
			totalWeight += line.weight;
		}
		if (totalWeight <= 0.0) {
			return null;
		}
		double roll = ThreadLocalRandom.current().nextDouble(totalWeight);
		for (SoloLine line : matching) {
			roll -= line.weight;
			if (roll < 0) {
				return line;
			}
		}
		return matching.get(matching.size() - 1);
	}

	public Conversation pickConversation(VillagerContext ctx) {
		List<Conversation> matching = new ArrayList<>();
		for (Conversation conv : conversations) {
			if (conditionsMatch(conv.conditions, ctx)) {
				matching.add(conv);
			}
		}
		if (matching.isEmpty()) {
			return null;
		}
		double totalWeight = 0.0;
		for (Conversation conv : matching) {
			totalWeight += conv.weight;
		}
		if (totalWeight <= 0.0) {
			return null;
		}
		double roll = ThreadLocalRandom.current().nextDouble(totalWeight);
		for (Conversation conv : matching) {
			roll -= conv.weight;
			if (roll < 0) {
				return conv;
			}
		}
		return matching.get(matching.size() - 1);
	}

	public int countMatching(VillagerContext ctx) {
		int n = 0;
		for (SoloLine line : soloLines) {
			if (matches(line, ctx)) {
				n++;
			}
		}
		return n;
	}

	public int countMatchingConversations(VillagerContext ctx) {
		int n = 0;
		for (Conversation conv : conversations) {
			if (conditionsMatch(conv.conditions, ctx)) {
				n++;
			}
		}
		return n;
	}

	// --- Context matching ---

	private static boolean matches(SoloLine line, VillagerContext ctx) {
		return conditionsMatch(line.conditions, ctx);
	}

	private static boolean conditionsMatch(Conditions c, VillagerContext ctx) {
		if (c == null) {
			return true;
		}
		return single(c.timeOfDay, ctx.timeOfDay)
				&& single(c.weather, ctx.weather)
				&& single(c.profession, ctx.profession)
				&& biomeMatch(c.biome, ctx.biome)
				&& stateMatch(c.state, ctx.states);
	}

	private static boolean single(List<String> allowed, String value) {
		if (allowed == null || allowed.isEmpty()) {
			return true;
		}
		for (String token : allowed) {
			if (token != null && token.equalsIgnoreCase(value)) {
				return true;
			}
		}
		return false;
	}

	private static boolean biomeMatch(List<String> allowed, String value) {
		if (allowed == null || allowed.isEmpty()) {
			return true;
		}
		for (String token : allowed) {
			if (token == null) {
				continue;
			}
			String t = token.toLowerCase(Locale.ROOT);
			if (value.equals(t) || value.contains(t)) {
				return true;
			}
		}
		return false;
	}

	private static boolean stateMatch(List<String> required, Set<String> current) {
		if (required == null || required.isEmpty()) {
			return true;
		}
		for (String token : required) {
			if (token != null && current.contains(token.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	// --- Data model ---

	public static class SoloLine {
		public String id;
		public String type;
		public Conditions conditions;
		public double weight = 1.0;
		public String category;
		public String text;
	}

	public static class Conversation {
		public String id;
		public String type;
		public Conditions conditions;
		public double weight = 1.0;
		public String category;
		public List<ConversationLine> lines;
	}

	public static class ConversationLine {
		public String speaker;
		public String text;
	}

	public static class Names {
		public String fallback;
		public Map<String, String> professions;
	}

	public static class Conditions {
		public List<String> profession;
		public List<String> timeOfDay;
		public List<String> biome;
		public List<String> weather;
		public List<String> state;
	}

	// Emergency fallback: only used when the jar holds no template for the language.
	private static final String DEFAULT_SOLO_JSON = """
			[
			  { "id": "general_1", "type": "solo", "conditions": {}, "weight": 1, "text": "What a day this is." },
			  { "id": "general_2", "type": "solo", "conditions": {}, "weight": 1, "text": "Have you heard the latest from the village?" },
			  { "id": "general_3", "type": "solo", "conditions": {}, "weight": 1, "text": "An emerald here, an emerald there - that's how a day goes." },
			  { "id": "morning_greet", "type": "solo", "conditions": { "timeOfDay": ["morning"] }, "weight": 1, "text": "Good morning! The sun is barely up." },
			  { "id": "farmer_work", "type": "solo", "conditions": { "profession": ["farmer"] }, "weight": 1, "text": "The wheat doesn't harvest itself." },
			  { "id": "librarian_book", "type": "solo", "conditions": { "profession": ["librarian"] }, "weight": 1, "text": "I have a new book that might interest you." },
			  { "id": "night_tired", "type": "solo", "conditions": { "timeOfDay": ["night"] }, "weight": 1, "text": "It's getting late. I should be off to bed." },
			  { "id": "rain_weather", "type": "solo", "conditions": { "weather": ["rain"] }, "weight": 1, "text": "In rain like this you're better off indoors." },
			  { "id": "trader_1", "type": "solo", "conditions": { "profession": ["wandering_trader"] }, "weight": 1, "text": "Rare goods from distant lands - take a look!" },
			  { "id": "trader_2", "type": "solo", "conditions": { "profession": ["wandering_trader"] }, "weight": 1, "text": "I'm only passing through." },
			  { "id": "trader_3", "type": "solo", "conditions": { "profession": ["wandering_trader"] }, "weight": 1, "text": "My llamas and I have seen a great deal." },
			  { "id": "trader_night", "type": "solo", "conditions": { "profession": ["wandering_trader"], "timeOfDay": ["night"] }, "weight": 1, "text": "Travelling by night is dangerous - but trade never rests." },
			  { "id": "trader_rain", "type": "solo", "conditions": { "profession": ["wandering_trader"], "weather": ["rain"] }, "weight": 1, "text": "The rain makes the roads heavy." }
			]
			""";

	private static final String DEFAULT_REACTIONS_JSON = """
			[
			  { "id": "react_1", "type": "reaction", "conditions": {}, "weight": 1, "text": "Hm? What is it?" },
			  { "id": "react_2", "type": "reaction", "conditions": {}, "weight": 1, "text": "Yes? How can I help?" },
			  { "id": "react_3", "type": "reaction", "conditions": {}, "weight": 1, "text": "Don't bother me, all right?" },
			  { "id": "react_4", "type": "reaction", "conditions": {}, "weight": 1, "text": "Hmph." },
			  { "id": "react_nitwit", "type": "reaction", "conditions": { "profession": ["nitwit"] }, "weight": 1, "text": "Heh-heh... hello!" },
			  { "id": "react_jobless", "type": "reaction", "conditions": { "profession": ["none"] }, "weight": 1, "text": "I really ought to find myself a trade." }
			]
			""";

	private static final String DEFAULT_NAMES_JSON = """
			{
			  "fallback": "Villager",
			  "professions": {
			    "armorer": "Armorer",
			    "butcher": "Butcher",
			    "cartographer": "Cartographer",
			    "cleric": "Cleric",
			    "farmer": "Farmer",
			    "fisherman": "Fisherman",
			    "fletcher": "Fletcher",
			    "leatherworker": "Leatherworker",
			    "librarian": "Librarian",
			    "mason": "Mason",
			    "shepherd": "Shepherd",
			    "toolsmith": "Toolsmith",
			    "weaponsmith": "Weaponsmith",
			    "wandering_trader": "Wandering Trader"
			  }
			}
			""";

	private static final String DEFAULT_MESSAGES_JSON = """
			{
			  "prefix": "[Chatting Villagers] ",
			  "playerOnly": "This command can only be used by a player.",
			  "selfOn": "Village chat for you: ON.",
			  "selfOff": "Village chat for you: OFF.",
			  "statusOn": "Village chat is ON for you.",
			  "statusOff": "Village chat is OFF for you.",
			  "bubblesOn": "Speech bubbles (global): ON.",
			  "bubblesOff": "Speech bubbles (global): OFF.",
			  "chatOn": "Chat output: ON.",
			  "chatOff": "Chat output: OFF (speech bubbles only).",
			  "presetUnknown": "Unknown preset. Available: quiet, normal, busy.",
			  "presetQuiet": "Quiet (rare, relaxed)",
			  "presetNormal": "Normal (balanced)",
			  "presetBusy": "Busy (frequent, lively)",
			  "frequencySet": "Speech frequency: {preset} | chance {chance}, cooldown {cooldown}s, max {limit} msg/min.",
			  "reloaded": "Reloaded: {solo} solo lines, {reactions} reactions, {conversations} conversations (language: {language}).",
			  "noVillagerInRange": "No villager in range ({radius} blocks).",
			  "debug": "Context: time={time}, weather={weather}, profession={profession}, biome={biome}, states={states}, name={name} | matching lines: {solo} | matching conversations: {conversations} | cooldown left: {cooldown}s | your village chat: {self}",
			  "wordOn": "ON",
			  "wordOff": "OFF",
			  "wordEnabled": "on",
			  "wordDisabled": "off"
			}
			""";

	private static final String DEFAULT_CONVERSATIONS_JSON = """
			[
			  {
			    "id": "smalltalk_general",
			    "type": "conversation",
			    "conditions": {},
			    "weight": 1,
			    "lines": [
			      { "speaker": "A", "text": "All quiet on your end?" },
			      { "speaker": "B", "text": "Can't complain. Village is still standing." }
			    ]
			  },
			  {
			    "id": "smalltalk_day_clear",
			    "type": "conversation",
			    "conditions": { "timeOfDay": ["day"], "weather": ["clear"] },
			    "weight": 1,
			    "lines": [
			      { "speaker": "A", "text": "Fine day today, isn't it?" },
			      { "speaker": "B", "text": "Aye, perfect for the fields." },
			      { "speaker": "A", "text": "That's what I said. Out we go." }
			    ]
			  },
			  {
			    "id": "smalltalk_evening",
			    "type": "conversation",
			    "conditions": { "timeOfDay": ["evening"] },
			    "weight": 1,
			    "lines": [
			      { "speaker": "A", "text": "Getting dark. Are your doors shut?" },
			      { "speaker": "B", "text": "Of course. I want no visitors tonight." }
			    ]
			  },
			  {
			    "id": "smalltalk_rain",
			    "type": "conversation",
			    "conditions": { "weather": ["rain"] },
			    "weight": 1,
			    "lines": [
			      { "speaker": "A", "text": "Best stay under a roof in this rain." },
			      { "speaker": "B", "text": "At least the field waters itself." }
			    ]
			  }
			]
			""";
}