package com.terfrecipes.client;

import com.terfrecipes.TERFRecipes;
import com.terfrecipes.data.TerfData;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class TERFRecipesClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal("terfrecipes")
						.executes(ctx -> status(ctx.getSource()))
						.then(ClientCommands.literal("reload").executes(ctx -> reload(ctx.getSource())))));
		TERFRecipes.LOGGER.info("[TERF Recipes] Client initialized (config folder: {})", TerfDataManager.configDir());
	}

	private static int status(FabricClientCommandSource source) {
		TerfData data = TerfDataManager.data();
		if (data.isEmpty()) {
			source.sendError(Component.literal("[TERF Recipes] No TERF datapack found. Put the datapack zip (or startup.mcfunction) in "
					+ TerfDataManager.configDir() + " then run /terfrecipes reload"));
			return 0;
		}
		source.sendFeedback(Component.literal("[TERF Recipes] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(data.recipeCount() + " recipes, " + data.materials().size()
						+ " custom items, " + data.recipesByMachine().size() + " machines").withStyle(ChatFormatting.WHITE)));
		source.sendFeedback(Component.literal("Source: " + data.sourceDescription()).withStyle(ChatFormatting.GRAY));
		if (data.hiddenRecipeCount() > 0) {
			source.sendFeedback(Component.literal(data.hiddenRecipeCount() + " recipe(s) hidden by config/terf-recipes/hidden.txt")
					.withStyle(ChatFormatting.GRAY));
		}
		if (!data.errors().isEmpty()) {
			source.sendFeedback(Component.literal(data.errors().size() + " line(s) could not be read, see the log")
					.withStyle(ChatFormatting.YELLOW));
		}
		return 1;
	}

	private static int reload(FabricClientCommandSource source) {
		TerfData data = TerfDataManager.reload();
		if (FabricLoader.getInstance().isModLoaded("jei")) {
			// separate class so JEI classes are only loaded when JEI is installed
			int missing = com.terfrecipes.client.jei.TerfJeiPlugin.applyReload(data);
			if (missing > 0) {
				source.sendFeedback(Component.literal(missing + " new machine(s): rejoin the world to see their JEI tab")
						.withStyle(ChatFormatting.YELLOW));
			}
		}
		return status(source);
	}
}
