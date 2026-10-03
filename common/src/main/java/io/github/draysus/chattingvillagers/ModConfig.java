package io.github.draysus.chattingvillagers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.github.draysus.chattingvillagers.platform.Services;
import net.minecraft.ChatFormatting;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH =
			Services.PLATFORM.getConfigDir().resolve("chattingvillagers.json"); // GEÄNDERT

	public boolean masterEnabled = true;
	public String language = "en_us";
	public String chatColor = "white";               // colour of the spoken text
	public String nameColor = "gold";                // colour of the villager name
	public double radius = 16.0;
	public int speakIntervalTicks = 100;

	// Radius in blocks for portal detection (near_nether_portal / near_end_portal).
	// 0 = off. Only evaluated for villagers about to speak, so it is cheap; keep it small.
	public int portalScanRadius = 4;

	// Currently selected frequency preset ("quiet" / "normal" / "busy").
	// Display only (e.g. in /chattingvillagers debug) - what counts are the individual values below,
	// which /chattingvillagers frequency <preset> sets together.
	public String frequencyPreset = "normal";

	// --- Anti-spam ---
	public int villagerCooldownSeconds = 45;
	public double speakChance = 0.35;
	public int maxMessagesPerPlayerPerMinute = 6;

	// --- Time-of-day factors ---
	// Multiplier applied to speakChance AND conversationChance per time of day.
	// 1.0 = unchanged, 0.3 = only 30 % of the normal chance, 0.0 = completely silent.
	// Applies on top of the preset, so it survives /chattingvillagers frequency.
	public double chanceFactorMorning = 1.0;
	public double chanceFactorDay = 1.0;
	public double chanceFactorEvening = 1.0;
	public double chanceFactorNight = 0.3;

	// --- Villager conversations ---
	public boolean conversationsEnabled = true;
	public double conversationChance = 0.15;
	public double conversationRadius = 5.0;
	public int pairCooldownSeconds = 120;
	public int conversationLineDelayTicks = 40;

	// --- Speech bubbles (global) ---
	public boolean bubblesEnabled = true;
	public int bubbleLifetimeTicks = 60;

	// --- Output & reactions ---
	public boolean chatMessagesEnabled = true;       // off => villager lines appear ONLY in speech bubbles
	public boolean reactionsEnabled = true;          // reaction when right-clicking head-shaking villagers
	public int reactionCooldownTicks = 30;           // minimum gap between reactions from the same villager

	/** Factor for the given time of day; unknown or nonsensical values fall back to 1.0. */
	public double chanceFactor(String timeOfDay) {
		double f = switch (timeOfDay == null ? "" : timeOfDay) {
			case "morning" -> chanceFactorMorning;
			case "day" -> chanceFactorDay;
			case "evening" -> chanceFactorEvening;
			case "night" -> chanceFactorNight;
			default -> 1.0;
		};
		return (!Double.isFinite(f) || f < 0.0) ? 1.0 : f;
	}

	/** Base chance times the time-of-day factor, safely clamped to 0..1. */
	public double effectiveChance(double base, String timeOfDay) {
		return Math.max(0.0, Math.min(1.0, base * chanceFactor(timeOfDay)));
	}

	/**
	 * Sets interval, cooldown, speak chance, rate limit and conversation frequency
	 * together to one of three sensible presets. Does NOT save by itself -
	 * the caller is expected to call save() afterwards.
	 *
	 * @return true for a known preset name, otherwise false (config stays unchanged).
	 */
	public boolean applyFrequencyPreset(String name) {
		if (name == null) {
			return false;
		}
		switch (name.toLowerCase(java.util.Locale.ROOT)) {
			case "quiet", "low", "ruhig" -> {
				frequencyPreset = "quiet";
				speakIntervalTicks = 140;
				villagerCooldownSeconds = 90;
				speakChance = 0.15;
				maxMessagesPerPlayerPerMinute = 3;
				conversationChance = 0.08;
				pairCooldownSeconds = 200;
				return true;
			}
			case "normal", "medium", "standard" -> {
				frequencyPreset = "normal";
				speakIntervalTicks = 100;
				villagerCooldownSeconds = 45;
				speakChance = 0.35;
				maxMessagesPerPlayerPerMinute = 6;
				conversationChance = 0.15;
				pairCooldownSeconds = 120;
				return true;
			}
			case "busy", "high", "lebhaft" -> {
				frequencyPreset = "busy";
				speakIntervalTicks = 60;
				villagerCooldownSeconds = 25;
				speakChance = 0.55;
				maxMessagesPerPlayerPerMinute = 10;
				conversationChance = 0.25;
				pairCooldownSeconds = 70;
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	public static ModConfig load() {
		try {
			if (Files.notExists(CONFIG_PATH)) {
				ModConfig defaults = new ModConfig();
				defaults.save();
				return defaults;
			}
			try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
				ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
				return loaded != null ? loaded : new ModConfig();
			}
		} catch (Exception e) {
			ChattingVillagers.LOGGER.error("[Chatting Villagers] Could not read config, using defaults.", e);
			return new ModConfig();
		}
	}

	public void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			ChattingVillagers.LOGGER.error("[Chatting Villagers] Could not save config.", e);
		}
	}

	public ChatFormatting resolveChatColor() {
		ChatFormatting color = ChatFormatting.getByName(chatColor);
		if (color == null || !color.isColor()) {
			return ChatFormatting.WHITE;
		}
		return color;
	}

	public ChatFormatting resolveNameColor() {
		ChatFormatting color = ChatFormatting.getByName(nameColor);
		if (color == null || !color.isColor()) {
			return ChatFormatting.GOLD;
		}
		return color;
	}
}