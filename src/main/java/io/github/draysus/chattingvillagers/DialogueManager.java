package io.github.draysus.chattingvillagers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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

	/**
	 * Catalogue files shipped inside the mod jar under /dialogue/<language>/ and copied into
	 * the config folder on first start. If a later version adds another solo file (say
	 * "solo_extra.json"), one entry here is enough: existing installations do not have it yet,
	 * so it is created for them automatically, while every file that is already present
	 * stays untouched.
	 */
	private static final String[] PACKAGED_FILES =
			{ SOLO_FILE, CONVERSATIONS_FILE, REACTIONS_FILE, NAMES_FILE, MESSAGES_FILE };

	/**
	 * Files in the dialogue folder that are NOT solo pools. Everything else ending in .json is
	 * treated as an additional solo pool, so any new non-pool file must be listed here too.
	 */
	private static final Set<String> NON_SOLO_FILES =
			Set.of(CONVERSATIONS_FILE, REACTIONS_FILE, NAMES_FILE, MESSAGES_FILE);

	/** Language whose packaged catalogue is used when the requested one is not shipped. */
	private static final String FALLBACK_LANGUAGE = "en_us";

	private final List<SoloLine> soloLines = new ArrayList<>();
	private final List<SoloLine> reactionLines = new ArrayList<>();
	private final List<Conversation> conversations = new ArrayList<>();

	// Language-dependent display names and command messages (from names.json / messages.json).
	private final Map<String, String> professionNames = new HashMap<>();
	private final Map<String, String> messages = new HashMap<>();
	private String nameFallback = "Villager";

	public void reload(String language) {
		soloLines.clear();
		reactionLines.clear();
		conversations.clear();
		professionNames.clear();
		messages.clear();
		nameFallback = "Villager";

		Path dialogueDir = FabricLoader.getInstance().getConfigDir()
				.resolve("chattingvillagers").resolve("dialogue").resolve(language);

		try {
			Files.createDirectories(dialogueDir);

			// On first start, copy the catalogue shipped in the jar into the config folder.
			// Existing files are NEVER touched: custom lines survive every update.
			for (String fileName : PACKAGED_FILES) {
				seedIfMissing(dialogueDir.resolve(fileName), language, fileName);
			}

			// Every *.json that is not one of the special files above counts as a solo pool.
			try (Stream<Path> files = Files.list(dialogueDir)) {
				List<Path> jsonFiles = files
						.filter(p -> p.getFileName().toString().endsWith(".json"))
						.filter(p -> !NON_SOLO_FILES.contains(p.getFileName().toString()))
						.toList();

				for (Path file : jsonFiles) {
					loadSoloInto(file, soloLines);
				}
			}

			loadConversations(dialogueDir);
			loadReactions(dialogueDir);
			loadNames(dialogueDir);
			loadMessages(dialogueDir);

		} catch (Exception e) {
			ChattingVillagers.LOGGER.error("[Chatting Villagers] Could not access the dialogue directory.", e);
		}

		ChattingVillagers.LOGGER.info(
				"[Chatting Villagers] Loaded {} solo lines, {} reactions, {} conversations (language: {}).",
				soloLines.size(), reactionLines.size(), conversations.size(), language);
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

	private void loadReactions(Path dialogueDir) {
		Path file = dialogueDir.resolve(REACTIONS_FILE);
		if (Files.notExists(file)) {
			ChattingVillagers.LOGGER.warn("[Chatting Villagers] {} is missing - no reactions loaded.", REACTIONS_FILE);
			return;
		}
		loadSoloInto(file, reactionLines);
	}

	private void loadConversations(Path dialogueDir) {
		Path convFile = dialogueDir.resolve(CONVERSATIONS_FILE);
		if (Files.notExists(convFile)) {
			ChattingVillagers.LOGGER.warn("[Chatting Villagers] {} is missing - no conversations loaded.", CONVERSATIONS_FILE);
			return;
		}
		try {
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
						if (!hasText) {
							continue;
						}
						if (!Double.isFinite(conv.weight) || conv.weight <= 0.0) {
							conv.weight = 1.0;
						}
						conversations.add(conv);
					}
				}
			}
		} catch (Exception e) {
			ChattingVillagers.LOGGER.error("[Chatting Villagers] Failed to read {}", CONVERSATIONS_FILE, e);
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
		public String text;
	}

	public static class Conversation {
		public String id;
		public String type;
		public Conditions conditions;
		public double weight = 1.0;
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