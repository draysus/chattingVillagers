package io.github.draysus.chattingvillagers;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Loader-independent core of the mod. Contains no Fabric/NeoForge/Forge code.
 * The loader-specific classes (ChattingVillagersFabric, ChattingVillagersNeoForge) create
 * one instance, call init() once and forward their events to the public on...() methods.
 */
public class ChattingVillagers {
	public static final String MOD_ID = "chattingvillagers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final long TICKS_PER_MINUTE = 1200L;
	private static final String BUBBLE_TAG = "chattingvillagers_bubble";

	private ModConfig config;
	private final DialogueManager dialogueManager = new DialogueManager();

	private final Map<UUID, VillagerMemory> memories = new HashMap<>();
	private final Map<UUID, Deque<Long>> playerMessageTimes = new HashMap<>();

	private final List<ActiveConversation> activeConversations = new ArrayList<>();
	private final Map<String, Long> pairLastTalk = new HashMap<>();

	private final List<ActiveBubble> activeBubbles = new ArrayList<>();
	private final Set<UUID> liveBubbleIds = new HashSet<>();

	// Reaction cooldown per villager (guards against click spam).
	private final Map<UUID, Long> lastReactionTick = new HashMap<>();

	/** Called once by the loader-specific entry point at startup. */
	public void init() {
		LOGGER.info("[Chatting Villagers] Loaded. Ready to give the villagers a voice.");

		config = ModConfig.load();
		dialogueManager.reload(config);
	}

	/** Entity loaded into a server level: removes orphaned speech bubbles (e.g. after a crash). */
	public void onEntityLoad(Entity entity) {
		if (entity.getTags().contains(BUBBLE_TAG) && !liveBubbleIds.contains(entity.getUUID())) {
			entity.discard();
		}
	}

	/** Server is stopping: remove all speech bubbles so none are saved into the world. */
	public void onServerStopping() {
		for (ActiveBubble b : activeBubbles) {
			if (b.entity != null) {
				b.entity.discard();
			}
		}
		activeBubbles.clear();
		liveBubbleIds.clear();
	}

	/** Called at the end of every server tick. */
	public void tickSpeech(MinecraftServer server) {
		long now = server.getTickCount();

		tickBubbles(now);

		if (!config.masterEnabled) {
			return;
		}

		PlayerPrefs prefs = PlayerPrefs.get(server);

		tickConversations(server, now, prefs);

		int interval = Math.max(1, config.speakIntervalTicks);
		if (now % interval != 0L) {
			return;
		}

		if (now % 6000L == 0L) {
			memories.entrySet().removeIf(e -> now - e.getValue().lastSpokeTick > 12000L);
			pairLastTalk.entrySet().removeIf(e -> now - e.getValue() > 12000L);
			lastReactionTick.entrySet().removeIf(e -> now - e.getValue() > 12000L);
		}

		long cooldownTicks = Math.max(0, config.villagerCooldownSeconds) * 20L;
		double chance = Math.max(0.0, Math.min(1.0, config.speakChance));
		double radiusSq = config.radius * config.radius;

		Set<AbstractVillager> candidateSet = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerLevel level = player.serverLevel();
			AABB area = player.getBoundingBox().inflate(config.radius);
			candidateSet.addAll(level.getEntitiesOfClass(AbstractVillager.class, area));
		}
		List<AbstractVillager> candidates = new ArrayList<>(candidateSet);

		Set<UUID> engaged = new HashSet<>();
		for (ActiveConversation c : activeConversations) {
			engaged.add(c.a.getUUID());
			engaged.add(c.b.getUUID());
		}

		// --- Start conversations ---
		if (config.conversationsEnabled && dialogueManager.getConversationCount() > 0) {
			double convChance = Math.max(0.0, Math.min(1.0, config.conversationChance));
			for (AbstractVillager initiator : candidates) {
				if (engaged.contains(initiator.getUUID()) || isExcluded(initiator)) {
					continue;
				}
				VillagerMemory mem = memories.computeIfAbsent(initiator.getUUID(), k -> new VillagerMemory());
				if (now - mem.lastSpokeTick < cooldownTicks) {
					continue;
				}
				// The time-of-day factor applies before the roll (getDayTime is cheap, no context needed).
				ServerLevel level = (ServerLevel) initiator.level();
				double thisConvChance = config.effectiveChance(convChance, VillagerContext.timeOfDay(level));
				if (thisConvChance <= 0.0 || ThreadLocalRandom.current().nextDouble() > thisConvChance) {
					continue;
				}

				AbstractVillager partner = findPartner(initiator, candidates, engaged, now, cooldownTicks);
				if (partner == null) {
					continue;
				}

				VillagerContext ctx = VillagerContext.read(level, initiator, config.portalScanRadius);
				DialogueManager.Conversation script = dialogueManager.pickConversation(ctx);
				if (script == null) {
					continue;
				}

				startConversation(level, initiator, partner, script, now);

				engaged.add(initiator.getUUID());
				engaged.add(partner.getUUID());
				mem.lastSpokeTick = now;
				memories.computeIfAbsent(partner.getUUID(), k -> new VillagerMemory()).lastSpokeTick = now;
				pairLastTalk.put(pairKey(initiator.getUUID(), partner.getUUID()), now);
			}
		}

		// --- Solo lines ---
		for (AbstractVillager villager : candidates) {
			if (engaged.contains(villager.getUUID()) || isExcluded(villager)) {
				continue;
			}

			VillagerMemory mem = memories.computeIfAbsent(villager.getUUID(), k -> new VillagerMemory());
			if (now - mem.lastSpokeTick < cooldownTicks) {
				continue;
			}
			ServerLevel level = (ServerLevel) villager.level();
			double thisChance = config.effectiveChance(chance, VillagerContext.timeOfDay(level));
			if (thisChance <= 0.0 || ThreadLocalRandom.current().nextDouble() > thisChance) {
				continue;
			}

			VillagerContext vctx = VillagerContext.read(level, villager, config.portalScanRadius);
			DialogueManager.SoloLine line = dialogueManager.pickSolo(vctx, mem.lastLineId);
			if (line == null) {
				continue;
			}

			int audience = sendVillagerLine(server, level, villager, line.text, radiusSq, now, prefs);
			if (audience > 0) {
				mem.lastSpokeTick = now;
				mem.lastLineId = line.id;
			}
		}
	}

	// Right-click on a villager or trader -> a short reaction, if any. Vanilla behaviour is preserved:
	// jobless villagers and nitwits still shake their head, everyone else still opens the trade menu.
	// Head-shakers always react; villagers that trade only react now and then (tradeReactionChance).
	public InteractionResult onUseEntity(Player player, Level world, InteractionHand hand,
			Entity entity, EntityHitResult hitResult) {
		if (world.isClientSide()) {
			return InteractionResult.PASS;
		}
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS; // only once per click
		}
		if (!config.masterEnabled || !config.reactionsEnabled) {
			return InteractionResult.PASS;
		}
		if (player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (!(entity instanceof AbstractVillager av) || !(player instanceof ServerPlayer)) {
			return InteractionResult.PASS;
		}
		if (isExcluded(av)) {
			return InteractionResult.PASS;
		}

		MinecraftServer server = ((ServerPlayer) player).server;
		long now = server.getTickCount();
		long cd = Math.max(0, config.reactionCooldownTicks);
		Long last = lastReactionTick.get(av.getUUID());
		if (last != null && now - last < cd) {
			return InteractionResult.PASS;
		}
		boolean shakesHead = wouldShakeHead(av);
		if (!shakesHead) {
			// Trading villagers and wandering traders open their menu as usual;
			// only sometimes do they also say something.
			double chance = config.tradeReactionChance;
			if (!(chance > 0.0) || ThreadLocalRandom.current().nextDouble() >= chance) {
				return InteractionResult.PASS;
			}
		}

		ServerLevel level = (ServerLevel) world;
		VillagerContext ctx = VillagerContext.read(level, av, config.portalScanRadius);
		// Villagers that trade only use reactions written for their profession:
		// the general reactions are meant for villagers that cannot trade.
		DialogueManager.SoloLine line = shakesHead
				? dialogueManager.pickReaction(ctx, null)
				: dialogueManager.pickTradeReaction(ctx);
		if (line == null) {
			return InteractionResult.PASS;
		}

		double radiusSq = config.radius * config.radius;
		PlayerPrefs prefs = PlayerPrefs.get(server);
		sendVillagerLine(server, level, av, line.text, radiusSq, now, prefs);
		lastReactionTick.put(av.getUUID(), now);
		return InteractionResult.PASS; // do NOT suppress the vanilla reaction (head shake / sound)
	}

	private void tickConversations(MinecraftServer server, long now, PlayerPrefs prefs) {
		if (activeConversations.isEmpty()) {
			return;
		}

		double radiusSq = config.radius * config.radius;
		double tol = config.conversationRadius + 4.0;
		double tolSq = tol * tol;

		Iterator<ActiveConversation> it = activeConversations.iterator();
		while (it.hasNext()) {
			ActiveConversation c = it.next();

			if (c.index >= c.steps.size()) {
				it.remove();
				continue;
			}

			Step step = c.steps.get(c.index);
			if (now < step.fireTick) {
				continue;
			}

			if (!validParticipant(c.a) || !validParticipant(c.b)
					|| c.a.level() != c.level || c.b.level() != c.level
					|| c.a.distanceToSqr(c.b) > tolSq) {
				it.remove();
				continue;
			}

			AbstractVillager speaker = step.speakerIsA ? c.a : c.b;
			sendVillagerLine(server, c.level, speaker, step.text, radiusSq, now, prefs);

			c.index++;
			if (c.index >= c.steps.size()) {
				it.remove();
			}
		}
	}

	private void startConversation(ServerLevel level, AbstractVillager a, AbstractVillager b,
			DialogueManager.Conversation script, long now) {
		ActiveConversation c = new ActiveConversation();
		c.a = a;
		c.b = b;
		c.level = level;

		// Resolve both names once (custom name > profession name from names.json > fallback).
		String nameA = villagerName(a);
		String nameB = villagerName(b);

		long baseDelay = Math.max(1, config.conversationLineDelayTicks);
		long fire = now;
		int added = 0;
		for (DialogueManager.ConversationLine cl : script.lines) {
			if (cl == null || cl.text == null || cl.text.isBlank()) {
				continue;
			}
			Step step = new Step();
			step.speakerIsA = !"B".equalsIgnoreCase(cl.speaker);
			// Placeholders depend on the speaker: {self} = speaker, {other} = the other villager.
			String self = step.speakerIsA ? nameA : nameB;
			String other = step.speakerIsA ? nameB : nameA;
			step.text = applyNames(cl.text, self, other, nameA, nameB);
			if (added > 0) {
				long jitter = ThreadLocalRandom.current().nextInt((int) Math.max(1, baseDelay / 2));
				fire += baseDelay + jitter;
			}
			step.fireTick = fire;
			c.steps.add(step);
			added++;
		}

		if (!c.steps.isEmpty()) {
			activeConversations.add(c);
		}
	}

	private AbstractVillager findPartner(AbstractVillager initiator, List<AbstractVillager> candidates,
			Set<UUID> engaged, long now, long cooldownTicks) {
		double pairRadiusSq = config.conversationRadius * config.conversationRadius;
		long pairCd = Math.max(0, config.pairCooldownSeconds) * 20L;

		AbstractVillager best = null;
		double bestDist = Double.MAX_VALUE;
		for (AbstractVillager other : candidates) {
			if (other == initiator || engaged.contains(other.getUUID()) || isExcluded(other)) {
				continue;
			}
			if (other.level() != initiator.level()) {
				continue;
			}
			double d = initiator.distanceToSqr(other);
			if (d > pairRadiusSq) {
				continue;
			}
			VillagerMemory om = memories.computeIfAbsent(other.getUUID(), k -> new VillagerMemory());
			if (now - om.lastSpokeTick < cooldownTicks) {
				continue;
			}
			Long lastTalk = pairLastTalk.get(pairKey(initiator.getUUID(), other.getUUID()));
			if (lastTalk != null && now - lastTalk < pairCd) {
				continue;
			}
			if (d < bestDist) {
				bestDist = d;
				best = other;
			}
		}
		return best;
	}

	/**
	 * Shared delivery path for solo, conversation and reaction lines.
	 * Spawns the global speech bubble if enabled, and sends chat only when
	 * chatMessagesEnabled is set, to players who have not opted out. Returns the audience size.
	 */
	private int sendVillagerLine(MinecraftServer server, ServerLevel level, AbstractVillager villager,
			String text, double radiusSq, long now, PlayerPrefs prefs) {
		List<ServerPlayer> inRange = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() != level) {
				continue;
			}
			if (player.distanceToSqr(villager) > radiusSq) {
				continue;
			}
			inRange.add(player);
		}
		if (inRange.isEmpty()) {
			return 0;
		}

		ChatFormatting textCol = config.resolveChatColor();

		if (config.bubblesEnabled) {
			spawnBubble(level, villager, text, textCol, now);
		}

		// Chat output only when enabled (otherwise: speech bubbles only).
		if (config.chatMessagesEnabled) {
			ChatFormatting nameCol = config.resolveNameColor();
			String name = villagerName(villager);
			Component message = Component.empty()
					.append(Component.literal(name).withStyle(nameCol))
					.append(Component.literal(": ").withStyle(textCol))
					.append(Component.literal(text).withStyle(textCol));

			for (ServerPlayer player : inRange) {
				if (!prefs.isEnabled(player.getUUID())) {
					continue;
				}
				if (isRateLimited(player.getUUID(), now)) {
					continue;
				}
				player.sendSystemMessage(message);
				recordMessage(player.getUUID(), now);
			}
		}
		return inRange.size();
	}

	// Custom name (e.g. from "Villager Names") > profession name from names.json > fallback from names.json.
	private String villagerName(AbstractVillager villager) {
		if (villager.getCustomName() != null) {
			return villager.getCustomName().getString();
		}
		if (villager instanceof Villager v) {
			return dialogueManager.professionName(professionPath(v));
		}
		// Wandering trader and similar entities without a villager profession.
		return dialogueManager.professionName("wandering_trader");
	}

	// --- Command messages from messages.json ---

	private String txt(String key) {
		return dialogueManager.rawMessage(key);
	}

	private Component prefixed(String body) {
		return Component.literal(txt("prefix") + body);
	}

	private static String professionPath(Villager v) {
		ResourceLocation id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession());
		return id != null ? id.getPath() : "none";
	}

	// Villagers that only shake their head in vanilla (jobless/nitwit). Traders always trade.
	private boolean wouldShakeHead(AbstractVillager av) {
		if (av instanceof Villager v) {
			String path = professionPath(v);
			return path.equals("none") || path.equals("nitwit");
		}
		return false;
	}

	private void spawnBubble(ServerLevel level, AbstractVillager villager, String text, ChatFormatting color, long now) {
		Iterator<ActiveBubble> it = activeBubbles.iterator();
		while (it.hasNext()) {
			ActiveBubble b = it.next();
			if (b.owner != null && villager.getUUID().equals(b.owner.getUUID())) {
				if (b.entity != null) {
					b.entity.discard();
				}
				liveBubbleIds.remove(b.id);
				it.remove();
			}
		}

		try {
			// Up to 1.21.4 the display entity reads "text" as an NBT string holding JSON
			// (Display$TextDisplay -> Component.Serializer.fromJson). Plain text is rejected;
			// only from 1.21.5 onwards is the tag stored as an NBT compound.
			Component comp = Component.literal(text).withStyle(color);
			String textJson = Component.Serializer.toJson(comp);

			CompoundTag tag = new CompoundTag();
			tag.putString("id", "minecraft:text_display");
			tag.putString("text", textJson);
			// Always face the player, in every axis of rotation.
			tag.putString("billboard", "center");
			// Smooths the per-tick repositioning that follows the villager (ignored before 1.20.2).
			tag.putInt("teleport_duration", 2);
			ListTag tags = new ListTag();
			tags.add(StringTag.valueOf(BUBBLE_TAG));
			tag.put("Tags", tags);

			double x = villager.getX();
			double y = villager.getY() + villager.getBbHeight() + 0.4;
			double z = villager.getZ();

			Entity created = EntityType.loadEntityRecursive(tag, level, spawned -> {
				spawned.moveTo(x, y, z, 0.0f, 0.0f);
				return spawned;
			});
			if (created == null) {
				return;
			}

			UUID id = created.getUUID();
			liveBubbleIds.add(id);
			if (level.addFreshEntity(created)) {
				ActiveBubble bubble = new ActiveBubble();
				bubble.id = id;
				bubble.owner = villager;
				bubble.entity = created;
				bubble.removeTick = now + Math.max(1, config.bubbleLifetimeTicks);
				activeBubbles.add(bubble);
			} else {
				liveBubbleIds.remove(id);
				created.discard();
			}
		} catch (Exception ex) {
			LOGGER.error("[Chatting Villagers] Failed to create speech bubble.", ex);
		}
	}

	private void tickBubbles(long now) {
		if (activeBubbles.isEmpty()) {
			return;
		}
		Iterator<ActiveBubble> it = activeBubbles.iterator();
		while (it.hasNext()) {
			ActiveBubble b = it.next();
			boolean entityGone = b.entity == null || b.entity.isRemoved();
			boolean ownerGone = b.owner == null || !b.owner.isAlive();
			if (now >= b.removeTick || entityGone || ownerGone) {
				if (b.entity != null) {
					b.entity.discard();
				}
				liveBubbleIds.remove(b.id);
				it.remove();
				continue;
			}
			double x = b.owner.getX();
			double y = b.owner.getY() + b.owner.getBbHeight() + 0.4;
			double z = b.owner.getZ();
			b.entity.setPos(x, y, z);
		}
	}

	private boolean validParticipant(AbstractVillager v) {
		return v != null && v.isAlive() && !isExcluded(v);
	}

	private boolean isExcluded(AbstractVillager villager) {
		return !villager.isAlive() || villager.isBaby() || villager.isSleeping();
	}

	private boolean isRateLimited(UUID playerId, long now) {
		Deque<Long> times = playerMessageTimes.get(playerId);
		if (times == null) {
			return false;
		}
		while (!times.isEmpty() && now - times.peekFirst() >= TICKS_PER_MINUTE) {
			times.pollFirst();
		}
		return times.size() >= Math.max(1, config.maxMessagesPerPlayerPerMinute);
	}

	private void recordMessage(UUID playerId, long now) {
		playerMessageTimes.computeIfAbsent(playerId, k -> new ArrayDeque<>()).addLast(now);
	}

	private static String pairKey(UUID x, UUID y) {
		return (x.compareTo(y) <= 0) ? (x + "|" + y) : (y + "|" + x);
	}

	// Replaces name placeholders in conversation lines. {self}/{other} depend on the speaker,
	// while {a}/{b} and {A}/{B} are bound to a fixed participant.
	private static String applyNames(String text, String selfName, String otherName, String nameA, String nameB) {
		if (text == null) {
			return "";
		}
		if (text.indexOf('{') < 0) {
			return text; // fast path: no placeholders
		}
		return text
				.replace("{self}", selfName)
				.replace("{other}", otherName)
				.replace("{a}", nameA)
				.replace("{b}", nameB)
				.replace("{A}", nameA)
				.replace("{B}", nameB);
	}

	/** Called by the loader's command registration event. */
	public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
						Commands.literal("chattingvillagers")
								.then(Commands.literal("on")
										.executes(ctx -> setPlayerEnabled(ctx.getSource(), Boolean.TRUE)))
								.then(Commands.literal("off")
										.executes(ctx -> setPlayerEnabled(ctx.getSource(), Boolean.FALSE)))
								.then(Commands.literal("toggle")
										.executes(ctx -> setPlayerEnabled(ctx.getSource(), null)))
								.then(Commands.literal("status")
										.executes(ctx -> showPlayerStatus(ctx.getSource())))
								.then(Commands.literal("bubbles")
										.requires(source -> source.hasPermission(2))
										.then(Commands.literal("on").executes(ctx -> setBubbles(ctx.getSource(), true)))
										.then(Commands.literal("off").executes(ctx -> setBubbles(ctx.getSource(), false))))
								.then(Commands.literal("chat")
										.requires(source -> source.hasPermission(2))
										.then(Commands.literal("on").executes(ctx -> setChatOutput(ctx.getSource(), true)))
										.then(Commands.literal("off").executes(ctx -> setChatOutput(ctx.getSource(), false))))
								.then(Commands.literal("frequency")
										.requires(source -> source.hasPermission(2))
										.then(Commands.literal("quiet").executes(ctx -> setFrequency(ctx.getSource(), "quiet")))
										.then(Commands.literal("normal").executes(ctx -> setFrequency(ctx.getSource(), "normal")))
										.then(Commands.literal("busy").executes(ctx -> setFrequency(ctx.getSource(), "busy"))))
								.then(Commands.literal("reload")
										.requires(source -> source.hasPermission(2))
										.executes(ctx -> {
											config = ModConfig.load();
											dialogueManager.reload(config);
											ctx.getSource().sendSuccess(() -> prefixed(dialogueManager.message("reloaded")
													.with("solo", dialogueManager.getSoloCount())
													.with("reactions", dialogueManager.getReactionCount())
													.with("conversations", dialogueManager.getConversationCount())
													.with("language", config.language)
													.toString()), true);
											return 1;
										}))
								.then(Commands.literal("debug")
										.requires(source -> source.hasPermission(2))
										.executes(ctx -> runDebug(ctx.getSource())))
		);
	}

	private int setPlayerEnabled(CommandSourceStack source, Boolean explicit) {
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception e) {
			source.sendFailure(Component.literal(txt("playerOnly")));
			return 0;
		}

		PlayerPrefs prefs = PlayerPrefs.get(source.getServer());
		final boolean enabled;
		if (explicit == null) {
			enabled = prefs.toggle(player.getUUID());
		} else {
			prefs.setEnabled(player.getUUID(), explicit);
			enabled = explicit;
		}

		source.sendSuccess(() -> prefixed(txt(enabled ? "selfOn" : "selfOff")), false);
		return 1;
	}

	/** Read-only: reports the state without changing it. Deliberately open to every player. */
	private int showPlayerStatus(CommandSourceStack source) {
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception e) {
			source.sendFailure(Component.literal(txt("playerOnly")));
			return 0;
		}

		boolean enabled = PlayerPrefs.get(source.getServer()).isEnabled(player.getUUID());
		source.sendSuccess(() -> prefixed(txt(enabled ? "statusOn" : "statusOff")), false);
		return 1;
	}

	private int setBubbles(CommandSourceStack source, boolean value) {
		config.bubblesEnabled = value;
		config.save();
		if (!value) {
			for (ActiveBubble b : activeBubbles) {
				if (b.entity != null) {
					b.entity.discard();
				}
			}
			activeBubbles.clear();
			liveBubbleIds.clear();
		}
		source.sendSuccess(() -> prefixed(txt(value ? "bubblesOn" : "bubblesOff")), true);
		return 1;
	}

	private int setChatOutput(CommandSourceStack source, boolean value) {
		config.chatMessagesEnabled = value;
		config.save();
		source.sendSuccess(() -> prefixed(txt(value ? "chatOn" : "chatOff")), true);
		return 1;
	}

	private int setFrequency(CommandSourceStack source, String preset) {
		if (!config.applyFrequencyPreset(preset)) {
			source.sendFailure(prefixed(txt("presetUnknown")));
			return 0;
		}
		config.save();

		// Command syntax stays English (like vanilla); only the response below is localised.
		String label = switch (config.frequencyPreset) {
			case "quiet" -> txt("presetQuiet");
			case "busy" -> txt("presetBusy");
			default -> txt("presetNormal");
		};
		source.sendSuccess(() -> prefixed(dialogueManager.message("frequencySet")
				.with("preset", label)
				.with("chance", config.speakChance)
				.with("cooldown", config.villagerCooldownSeconds)
				.with("limit", config.maxMessagesPerPlayerPerMinute)
				.toString()), true);
		return 1;
	}

	private int runDebug(CommandSourceStack source) {
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception e) {
			source.sendFailure(Component.literal(txt("playerOnly")));
			return 0;
		}

		ServerLevel level = player.serverLevel();
		AABB area = player.getBoundingBox().inflate(config.radius);
		List<AbstractVillager> nearby = level.getEntitiesOfClass(AbstractVillager.class, area);
		if (nearby.isEmpty()) {
			source.sendSuccess(() -> Component.literal(dialogueManager.message("noVillagerInRange")
					.with("radius", config.radius).toString()), false);
			return 0;
		}

		AbstractVillager villager = nearby.get(0);
		VillagerContext vctx = VillagerContext.read(level, villager, config.portalScanRadius);
		int matching = dialogueManager.countMatching(vctx);
		int matchingConv = dialogueManager.countMatchingConversations(vctx);

		long now = source.getServer().getTickCount();
		VillagerMemory mem = memories.get(villager.getUUID());
		long cooldownTicks = Math.max(0, config.villagerCooldownSeconds) * 20L;
		long cooldownLeft = mem == null ? 0 : Math.max(0, cooldownTicks - (now - mem.lastSpokeTick));

		boolean selfEnabled = PlayerPrefs.get(source.getServer()).isEnabled(player.getUUID());

		String info = dialogueManager.message("debug")
				.with("time", vctx.timeOfDay)
				.with("weather", vctx.weather)
				.with("profession", vctx.profession)
				.with("biome", vctx.biome)
				.with("states", vctx.states)
				.with("name", villagerName(villager))
				.with("solo", matching)
				.with("conversations", matchingConv)
				.with("activeConversations", activeConversations.size())
				.with("activeBubbles", activeBubbles.size())
				.with("bubbles", txt(config.bubblesEnabled ? "wordEnabled" : "wordDisabled"))
				.with("chat", txt(config.chatMessagesEnabled ? "wordEnabled" : "wordDisabled"))
				.with("cooldown", cooldownLeft / 20)
				.with("self", txt(selfEnabled ? "wordOn" : "wordOff"))
				.with("preset", config.frequencyPreset)
				.with("factor", config.chanceFactor(vctx.timeOfDay))
				.toString();
		source.sendSuccess(() -> Component.literal(info), false);
		return 1;
	}

	private static class VillagerMemory {
		long lastSpokeTick = -100000L;
		String lastLineId = null;
	}

	private static class ActiveConversation {
		AbstractVillager a;
		AbstractVillager b;
		ServerLevel level;
		final List<Step> steps = new ArrayList<>();
		int index = 0;
	}

	private static class Step {
		boolean speakerIsA;
		String text;
		long fireTick;
	}

	private static class ActiveBubble {
		UUID id;
		AbstractVillager owner;
		Entity entity;
		long removeTick;
	}
}