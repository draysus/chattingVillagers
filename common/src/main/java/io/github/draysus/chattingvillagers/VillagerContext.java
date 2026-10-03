package io.github.draysus.chattingvillagers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashSet;
import java.util.Set;

/** Snapshot of a villager's or trader's situation - the basis for context filtering. */
public class VillagerContext {
	public final String timeOfDay;   // morning / day / evening / night
	public final String weather;     // clear / rain / thunder
	public final String profession;  // farmer / librarian / wandering_trader / none / nitwit / ...
	public final String biome;       // registry path, e.g. "desert", "plains", "snowy_taiga"
	public final Set<String> states; // baby, low_health, trading, hurt, sleeping, bell_alert, dimension/place/time tokens (several possible)

	private VillagerContext(String timeOfDay, String weather, String profession, String biome, Set<String> states) {
		this.timeOfDay = timeOfDay;
		this.weather = weather;
		this.profession = profession;
		this.biome = biome;
		this.states = states;
	}

	/**
	 * Time-of-day bucket derived from the world time alone. Kept separate from read() so the
	 * time of day can be queried without building the full context (including the portal scan).
	 */
	public static String timeOfDay(ServerLevel level) {
		return bucketForDayTime(Math.floorMod(level.getDayTime(), 24000L));
	}

	private static String bucketForDayTime(long dayTime) {
		if (dayTime < 3000L) {
			return "morning";
		}
		if (dayTime < 9000L) {
			return "day";
		}
		if (dayTime < 13000L) {
			return "evening";
		}
		return "night";
	}

	public static VillagerContext read(ServerLevel level, AbstractVillager villager, int portalScanRadius) {
		BlockPos pos = villager.blockPosition();

		// --- Time of day (world time 0..23999) ---
		long dayTime = Math.floorMod(level.getDayTime(), 24000L);
		String timeOfDay = bucketForDayTime(dayTime);

		// --- Weather at this position ---
		String weather;
		if (level.isThundering() && level.isRainingAt(pos)) {
			weather = "thunder";
		} else if (level.isRainingAt(pos)) {
			weather = "rain";
		} else {
			weather = "clear";
		}

		// --- Profession: villagers via the registry, wandering trader as a special case ---
		String profession = "none";
		if (villager instanceof Villager v) {
			VillagerProfession prof = v.getVillagerData().getProfession();
			ResourceLocation profId = BuiltInRegistries.VILLAGER_PROFESSION.getKey(prof);
			if (profId != null) {
				profession = profId.getPath();
			}
		} else if (villager instanceof WanderingTrader) {
			profession = "wandering_trader";
		}

		// --- Biome (dynamic registry -> resolved through the holder) ---
		Holder<Biome> biomeHolder = level.getBiome(pos);
		String biome = biomeHolder.unwrapKey().map(key -> key.location().getPath()).orElse("unknown");

		// --- States ---
		Set<String> states = new HashSet<>();
		if (villager.isBaby()) {
			states.add("baby");
		}
		if (villager.getHealth() < villager.getMaxHealth() * 0.5f) {
			states.add("low_health");
		}
		if (villager.isTrading()) {
			states.add("trading");
		}
		if (villager.hurtTime > 0) {
			states.add("hurt");
		}
		if (villager.isSleeping()) {
			states.add("sleeping");
		}

		// --- Bell alert: villager brain still remembers hearing a bell ring recently ---
		// (vanilla sets MemoryModuleType.HEARD_BELL_TIME when a bell rings nearby; this is
		// what drives the "run inside" panic behaviour, and expires again after a short time)
		if (villager instanceof Villager v && v.getBrain().hasMemoryValue(MemoryModuleType.HEARD_BELL_TIME)) {
			states.add("bell_alert");
		}

		// --- Dimension as a state token (overworld / nether / end) ---
		ResourceKey<Level> dim = level.dimension();
		boolean overworld = Level.OVERWORLD.equals(dim);
		if (Level.NETHER.equals(dim)) {
			states.add("nether");
		} else if (Level.END.equals(dim)) {
			states.add("end");
		} else if (overworld) {
			states.add("overworld");
		}

		// --- Cave vs. surface (only meaningful in the overworld) ---
		// "cave" when the villager stands well below the terrain height AND cannot see the sky.
		// That way villagers indoors (under a roof but at ground level) still count as "surface".
		if (overworld) {
			int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
			boolean underground = pos.getY() < surfaceY - 6 && !level.canSeeSky(pos);
			states.add(underground ? "cave" : "surface");
		}

		// --- Finer time markers around bedtime ---
		// wakeup: dawn (late night until shortly after sunrise)
		if (dayTime >= 23000L || dayTime < 1500L) {
			states.add("wakeup");
		}
		// bedtime: dusk, when villagers head to bed
		if (dayTime >= 11000L && dayTime < 13500L) {
			states.add("bedtime");
		}

		// --- Nearby portals (optional, bounded scan) ---
		if (portalScanRadius > 0) {
			addPortalStates(level, pos, portalScanRadius, states);
		}

		return new VillagerContext(timeOfDay, weather, profession, biome, states);
	}

	/**
	 * Scans a bounded cube around the villager for active portal blocks.
	 * Bails out early once both are found and skips unloaded positions, so no chunks are
	 * pulled in. Only called for villagers that are about to speak (or on request), so
	 * it runs rarely.
	 */
	private static void addPortalStates(ServerLevel level, BlockPos center, int r, Set<String> states) {
		boolean nether = false;
		boolean end = false;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		outer:
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (!level.isLoaded(m)) {
						continue;
					}
					BlockState bs = level.getBlockState(m);
					if (!nether && bs.is(Blocks.NETHER_PORTAL)) {
						nether = true;
					} else if (!end && bs.is(Blocks.END_PORTAL)) {
						end = true;
					}
					if (nether && end) {
						break outer;
					}
				}
			}
		}
		if (nether) {
			states.add("near_nether_portal");
		}
		if (end) {
			states.add("near_end_portal");
		}
	}
}