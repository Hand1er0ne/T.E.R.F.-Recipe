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
						.then(ClientCommands.literal("reload").executes(ctx -> reload(ctx.getSource())))
						.then(ClientCommands.literal("update").executes(ctx -> update(ctx.getSource())))
						.then(ClientCommands.literal("hologram").then(ClientCommands.literal("clear").executes(ctx -> {
							com.terfrecipes.client.render.Hologram.clear();
							return 1;
						})))));
		// "show in world" ghost structures
		com.terfrecipes.client.render.Hologram.init();
		// newer datapack on GitHub? (background, never blocks the game)
		if (GithubUpdater.settings().checkOnStartup) {
			GithubUpdater.checkAsync().thenAccept(result -> {
				TERFRecipes.LOGGER.info("[TERF Recipes] GitHub: {}", result.message());
				if (result.status() == GithubUpdater.Status.UPDATED) {
					net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
					mc.execute(() -> {
						// only matters when a world is open and shows a fallback copy
						if (mc.level != null && TerfDataManager.usesFallbackCopy()) applyReload(null);
					});
				}
			});
		}
		// 3D view of the multiblocks in JEI
		net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(
				ctx -> new com.terfrecipes.client.render.StructureRenderer(ctx.bufferSource()));
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
		applyReload(source);
		return status(source);
	}

	/** Reloads the data and swaps what JEI shows. */
	private static void applyReload(FabricClientCommandSource source) {
		TerfData data = TerfDataManager.reload();
		if (FabricLoader.getInstance().isModLoaded("jei")) {
			// separate class so JEI classes are only loaded when JEI is installed
			int missing = com.terfrecipes.client.jei.TerfJeiPlugin.applyReload(data);
			if (missing > 0 && source != null) {
				source.sendFeedback(Component.literal(missing + " new machine(s): rejoin the world to see their JEI tab")
						.withStyle(ChatFormatting.YELLOW));
			}
		}
	}

	private static int update(FabricClientCommandSource source) {
		source.sendFeedback(Component.literal("[TERF Recipes] Checking GitHub...").withStyle(ChatFormatting.GRAY));
		GithubUpdater.checkAsync().thenAccept(result -> source.getClient().execute(() -> {
			ChatFormatting color = switch (result.status()) {
				case UPDATED -> ChatFormatting.GREEN;
				case FAILED -> ChatFormatting.RED;
				default -> ChatFormatting.GRAY;
			};
			source.sendFeedback(Component.literal("[TERF Recipes] " + result.message()).withStyle(color));
			if (result.status() == GithubUpdater.Status.UPDATED) {
				if (TerfDataManager.usesFallbackCopy()) {
					applyReload(source);
					status(source);
				} else {
					source.sendFeedback(Component.literal("This world has its own datapack: it stays in use. "
							+ "The GitHub copy is used where the datapack is missing.").withStyle(ChatFormatting.GRAY));
				}
			}
		}));
		return 1;
	}
}
