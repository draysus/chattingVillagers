package io.github.draysus.chattingvillagers;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

/**
 * Fabric entry point. Only wires Fabric events to the loader-independent core.
 */
public class ChattingVillagersFabric implements ModInitializer {
	public static final ChattingVillagers CORE = new ChattingVillagers();

	@Override
	public void onInitialize() {
		CORE.init();

		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> CORE.registerCommands(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(CORE::tickSpeech);

		// Right-click reactions.
		UseEntityCallback.EVENT.register(CORE::onUseEntity);

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> CORE.onEntityLoad(entity));

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> CORE.onServerStopping());
	}
}