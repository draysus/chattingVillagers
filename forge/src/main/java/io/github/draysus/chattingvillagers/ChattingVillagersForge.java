package io.github.draysus.chattingvillagers;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge entry point. Only wires Forge events to the loader-independent core.
 */
@Mod(Constants.MOD_ID)
public class ChattingVillagersForge {
	public static final ChattingVillagers CORE = new ChattingVillagers();

	public ChattingVillagersForge() {
		CORE.init();

		MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
				CORE.registerCommands(event.getDispatcher()));

		// Forge fires the server tick event twice per tick (START and END); only react at the end,
		// like Fabric's END_SERVER_TICK and NeoForge's ServerTickEvent.Post.
		MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
			if (event.phase == TickEvent.Phase.END) {
				CORE.tickSpeech(event.getServer());
			}
		});

		// Right-click reactions. The core always returns PASS, so the result is not needed;
		// the hit result is unused by the core, hence null.
		MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) ->
				CORE.onUseEntity(event.getEntity(), event.getLevel(), event.getHand(), event.getTarget(), null));

		// This event also fires on the client: only act on the server.
		MinecraftForge.EVENT_BUS.addListener((EntityJoinLevelEvent event) -> {
			if (!event.getLevel().isClientSide()) {
				CORE.onEntityLoad(event.getEntity());
			}
		});

		MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> CORE.onServerStopping());
	}
}