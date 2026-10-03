package io.github.draysus.chattingvillagers;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge entry point. Only wires NeoForge events to the loader-independent core.
 */
@Mod(Constants.MOD_ID)
public class ChattingVillagersNeoForge {
	public static final ChattingVillagers CORE = new ChattingVillagers();

	public ChattingVillagersNeoForge(IEventBus modEventBus) {
		CORE.init();

		NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
				CORE.registerCommands(event.getDispatcher()));

		NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) ->
				CORE.tickSpeech(event.getServer()));

		// Right-click reactions. The core always returns PASS, so the result is not needed;
		// the hit result is unused by the core, hence null.
		NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) ->
				CORE.onUseEntity(event.getEntity(), event.getLevel(), event.getHand(), event.getTarget(), null));

		// Unlike Fabric's ENTITY_LOAD, this event also fires on the client: only act on the server.
		NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent event) -> {
			if (!event.getLevel().isClientSide()) {
				CORE.onEntityLoad(event.getEntity());
			}
		});

		NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> CORE.onServerStopping());
	}
}