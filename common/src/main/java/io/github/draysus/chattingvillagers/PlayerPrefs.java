package io.github.draysus.chattingvillagers;

import com.mojang.serialization.Codec;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent per-player setting: who has switched the village chat off (opt-out).
 * The default is on, so only the set of players who opted out is stored.
 * Lives in the world data (data/chattingvillagers_prefs.dat) and behaves the same on any server.
 *
 * File layout is identical to the 1.21.1 version ({"disabled": ["uuid", ...]}),
 * so worlds updated from 1.21.1 keep their settings.
 */
public class PlayerPrefs extends SavedData {

	private static final String DATA_NAME = "chattingvillagers_prefs";
	private static final String KEY_DISABLED = "disabled";

	// UUIDs of players who do NOT want villager messages.
	private final Set<UUID> disabled = new HashSet<>();

	public PlayerPrefs() {
	}

	// --- Serialisation (since 1.21.5: a Codec instead of save/load methods) ---

	private static final Codec<PlayerPrefs> CODEC = Codec.STRING.listOf()
			.optionalFieldOf(KEY_DISABLED, List.of())
			.xmap(PlayerPrefs::fromStrings, PlayerPrefs::toStrings)
			.codec();

	private static final SavedDataType<PlayerPrefs> TYPE = new SavedDataType<>(
			DATA_NAME,          // file name: data/chattingvillagers_prefs.dat
			PlayerPrefs::new,   // new, empty instance when no file exists yet
			CODEC,              // reads and writes the data
			null                // no data fixer needed
	);

	private static PlayerPrefs fromStrings(List<String> ids) {
		PlayerPrefs data = new PlayerPrefs();
		for (String id : ids) {
			try {
				data.disabled.add(UUID.fromString(id));
			} catch (IllegalArgumentException ignored) {
				// skip a corrupted entry
			}
		}
		return data;
	}

	private List<String> toStrings() {
		List<String> out = new ArrayList<>(disabled.size());
		for (UUID id : disabled) {
			out.add(id.toString());
		}
		return out;
	}

	/** Fetches (or creates) the settings from the overworld's world data. */
	public static PlayerPrefs get(MinecraftServer server) {
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		if (overworld == null) {
			// Fallback (should not happen in a running game): a throwaway instance.
			return new PlayerPrefs();
		}
		return overworld.getDataStorage().computeIfAbsent(TYPE);
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
}