package io.github.draysus.chattingvillagers;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent per-player setting: who has switched the village chat off (opt-out).
 * The default is on, so only the set of players who opted out is stored.
 * Lives in the world data (data/chattingvillagers_prefs.dat) and behaves the same on any server.
 *
 * Minecraft 1.20.1 version: computeIfAbsent(load, create, name) and save(CompoundTag).
 * The file layout ({"disabled": ["uuid", ...]}) is the same in every version.
 */
public class PlayerPrefs extends SavedData {

	private static final String DATA_NAME = "chattingvillagers_prefs";
	private static final String KEY_DISABLED = "disabled";

	// UUIDs of players who do NOT want villager messages.
	private final Set<UUID> disabled = new HashSet<>();

	public PlayerPrefs() {
	}

	/** Fetches (or creates) the settings from the overworld's world data. */
	public static PlayerPrefs get(MinecraftServer server) {
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		if (overworld == null) {
			// Fallback (should not happen in a running game): a throwaway instance.
			return new PlayerPrefs();
		}
		return overworld.getDataStorage().computeIfAbsent(PlayerPrefs::load, PlayerPrefs::new, DATA_NAME);
	}

	public boolean isEnabled(UUID id) {
		return !disabled.contains(id);
	}

	/** Sets the state explicitly. */
	public void setEnabled(UUID id, boolean enabled) {
		boolean changed = enabled ? disabled.remove(id) : disabled.add(id);
		if (changed) {
			setDirty();
		}
	}

	/** Flips the state and returns the new one (true = on). */
	public boolean toggle(UUID id) {
		boolean nowEnabled;
		if (disabled.contains(id)) {
			disabled.remove(id);
			nowEnabled = true;
		} else {
			disabled.add(id);
			nowEnabled = false;
		}
		setDirty();
		return nowEnabled;
	}

	// --- Serialisation (1.20.1: save(CompoundTag) and a static load(CompoundTag)) ---

	@Override
	public CompoundTag save(CompoundTag tag) {
		ListTag list = new ListTag();
		for (UUID id : disabled) {
			list.add(StringTag.valueOf(id.toString()));
		}
		tag.put(KEY_DISABLED, list);
		return tag;
	}

	public static PlayerPrefs load(CompoundTag tag) {
		PlayerPrefs data = new PlayerPrefs();
		ListTag list = tag.getList(KEY_DISABLED, Tag.TAG_STRING);
		for (int i = 0; i < list.size(); i++) {
			try {
				data.disabled.add(UUID.fromString(list.getString(i)));
			} catch (IllegalArgumentException ignored) {
				// skip a corrupted entry
			}
		}
		return data;
	}
}