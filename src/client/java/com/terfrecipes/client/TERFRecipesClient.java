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
						.then(ClientCommands.literal("update").executes(ctx -> update(ctx.getSource()))
								.then(ClientCommands.literal("confirm").executes(ctx -> updateConfirm(ctx.getSource())))
								.then(ClientCommands.literal("disable").executes(ctx -> updateDisable(ctx.getSource())))
								.then(ClientCommands.literal("startup")
										.then(ClientCommands.literal("on").executes(ctx -> updateStartup(ctx.getSource(), true)))
										.then(ClientCommands.literal("off").executes(ctx -> updateStartup(ctx.getSource(), false)))))
						.then(ClientCommands.literal("hologram").then(ClientCommands.literal("clear").executes(ctx -> {
							com.terfrecipes.client.render.Hologram.clear();
							return 1;
						})))));
		// "show in world" ghost structures
		com.terfrecipes.client.render.Hologram.init();
		// newer datapack on GitHub? Only when the player turned it on (/terfrecipes update startup on),
		// and it only asks for the latest commit: the download itself always waits for a click
		if (GithubUpdater.settings().notifyOnStartup) {
			GithubUpdater.checkOnlyAsync().thenAccept(result -> {
				TERFRecipes.LOGGER.info("[TERF Recipes] GitHub: {}", result.message());
				if (result.status() == GithubUpdater.Status.UPDATE_AVAILABLE) pendingUpdateNotice = result.message();
			});
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
				String notice = pendingUpdateNotice;
				if (notice == null || mc.player == null) return;
				pendingUpdateNotice = null;
				mc.player.sendSystemMessage(Component.literal("[TERF Recipes] ").withStyle(ChatFormatting.GOLD)
						.append(Component.literal(notice + " ").withStyle(ChatFormatting.WHITE))
						.append(button("[Download]", "/terfrecipes update confirm", "Download the datapack from " + GithubUpdater.repoUrl())));
			});
		}
		// 3D view of the multiblocks in JEI
		net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(
				ctx -> new com.terfrecipes.client.render.StructureRenderer());
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

	private static volatile String pendingUpdateNotice;

	/** Clickable chat text running a command. */
	private static Component button(String text, String command, String hover) {
		return Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.GREEN).withUnderlined(true)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(command))
				.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(hover))));
	}

	/** First use: say exactly what would be downloaded, and wait for the player to confirm. */
	private static int update(FabricClientCommandSource source) {
		if (!GithubUpdater.settings().allowDownloads) {
			source.sendFeedback(Component.literal("[TERF Recipes] Download the latest TERF datapack from GitHub?").withStyle(ChatFormatting.GOLD));
			source.sendFeedback(Component.literal("- Source: " + GithubUpdater.repoUrl()).withStyle(ChatFormatting.GRAY));
			source.sendFeedback(Component.literal("- About 1-2 MB. Only the datapack's data files (.json, .mcfunction) are kept, in "
					+ TerfDataManager.configDir().resolve("github")).withStyle(ChatFormatting.GRAY));
			source.sendFeedback(Component.literal("- Nothing is run: the files are only read to show the recipes. The world / server datapack still wins.")
					.withStyle(ChatFormatting.GRAY));
			source.sendFeedback(Component.literal("- Later updates are only downloaded when you run /terfrecipes update. "
					+ "/terfrecipes update disable turns it off again.").withStyle(ChatFormatting.GRAY));
			source.sendFeedback(button("[Download now]", "/terfrecipes update confirm", "Download from " + GithubUpdater.repoUrl()));
			return 1;
		}
		return download(source);
	}

	private static int updateConfirm(FabricClientCommandSource source) {
		GithubUpdater.Settings settings = GithubUpdater.settings();
		if (!settings.allowDownloads) {
			settings.allowDownloads = true;
			GithubUpdater.save(settings);
		}
		return download(source);
	}

	private static int updateDisable(FabricClientCommandSource source) {
		GithubUpdater.Settings settings = GithubUpdater.settings();
		settings.allowDownloads = false;
		settings.notifyOnStartup = false;
		GithubUpdater.save(settings);
		source.sendFeedback(Component.literal("[TERF Recipes] GitHub downloads and startup check turned off. "
				+ "The copy already downloaded stays in use (delete config/terf-recipes/github/ to remove it).").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int updateStartup(FabricClientCommandSource source, boolean on) {
		GithubUpdater.Settings settings = GithubUpdater.settings();
		settings.notifyOnStartup = on;
		GithubUpdater.save(settings);
		source.sendFeedback(Component.literal(on
				? "[TERF Recipes] When the game starts, GitHub is asked for the latest version and the chat tells you when a newer one exists. "
				+ "Nothing is downloaded until you click [Download]."
				: "[TERF Recipes] No more GitHub check at startup.").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int download(FabricClientCommandSource source) {
		source.sendFeedback(Component.literal("[TERF Recipes] Checking GitHub...").withStyle(ChatFormatting.GRAY));
		GithubUpdater.downloadAsync().thenAccept(result -> source.getClient().execute(() -> {
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
