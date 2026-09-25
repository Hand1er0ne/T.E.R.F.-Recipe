package com.terfrecipes.client;

import com.terfrecipes.TERFRecipes;
import com.terfrecipes.data.HiddenFilter;
import com.terfrecipes.data.MachineDefs;
import com.terfrecipes.data.TerfData;
import com.terfrecipes.data.source.DatapackSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds the datapack, replays its startup function and keeps the resulting {@link TerfData}.
 * <p>
 * Sources, first match wins:
 * <ol>
 *     <li>singleplayer: the world's loaded datapacks (read through the integrated server)</li>
 *     <li>{@code config/terf-recipes/}: a startup.mcfunction, a datapack zip or a datapack folder</li>
 *     <li>resource packs downloaded from the current server, when the server ships the combined
 *     TERF zip (datapack + resource pack) as its resource pack</li>
 *     <li>the copy bundled in the mod jar at build time</li>
 * </ol>
 */
public final class TerfDataManager {

    private static volatile TerfData data = TerfData.empty();
    private static volatile MachineDefs defs;

    private TerfDataManager() {
    }

    public static TerfData data() {
        return data;
    }

    public static MachineDefs defs() {
        if (defs == null) defs = loadDefs();
        return defs;
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(TERFRecipes.MOD_ID);
    }

    /** Reloads everything; never throws. */
    public static synchronized TerfData reload() {
        defs = loadDefs();
        List<DatapackSource> candidates = new ArrayList<>();
        try {
            candidates.addAll(findSources());
        } catch (RuntimeException e) {
            TERFRecipes.LOGGER.error("[TERF Recipes] Error while looking for the datapack", e);
        }
        candidates.add(new DatapackSource.Bundled(TerfDataManager.class.getClassLoader()));

        for (DatapackSource source : candidates) {
            String startup;
            try {
                startup = source.readFunction(DatapackSource.STARTUP);
            } catch (RuntimeException e) {
                continue;
            }
            if (startup == null) continue;
            try {
                long start = System.nanoTime();
                // files missing from the source (e.g. a lone startup.mcfunction in config/) are
                // taken from the bundled copy, so structures and random outputs still work
                DatapackSource bundled = new DatapackSource.Bundled(TerfDataManager.class.getClassLoader());
                java.util.function.Function<String, String> dataReader = path -> {
                    String v = source.readData(path);
                    return v != null || source instanceof DatapackSource.Bundled ? v : bundled.readData(path);
                };
                java.util.function.Function<String, String> functions = id -> dataReader.apply(DatapackSource.functionPath(id));
                TerfData built = TerfData.build(startup, functions, dataReader, defs, source.describe())
                        .filtered(loadHiddenFilter());
                TERFRecipes.LOGGER.info("[TERF Recipes] Loaded {} recipes ({} hidden) / {} custom items / {} multiblocks from {} in {} ms ({} warnings)",
                        built.recipeCount(), built.hiddenRecipeCount(), built.materials().size(), built.multiblocks().size(), source.describe(),
                        (System.nanoTime() - start) / 1_000_000, built.errors().size());
                for (String err : built.errors().subList(0, Math.min(10, built.errors().size()))) {
                    TERFRecipes.LOGGER.warn("[TERF Recipes]   {}", err);
                }
                data = built;
                ItemResolver.clearCache();
                return built;
            } catch (RuntimeException e) {
                TERFRecipes.LOGGER.error("[TERF Recipes] Could not parse the datapack from {}", source.describe(), e);
            }
        }
        TERFRecipes.LOGGER.warn("[TERF Recipes] No TERF datapack found, no recipe will be shown");
        data = TerfData.empty();
        ItemResolver.clearCache();
        return data;
    }

    // ------------------------------------------------------------------ sources

    private static List<DatapackSource> findSources() {
        List<DatapackSource> out = new ArrayList<>();

        // 1. singleplayer world
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) out.add(new ServerResourcesSource(server));

        // 2. config folder
        Path config = configDir();
        try {
            Files.createDirectories(config);
        } catch (IOException ignored) {
            // read-only config dir, we can still try to read
        }
        Path loose = config.resolve("startup.mcfunction");
        if (Files.isRegularFile(loose)) out.add(new DatapackSource.SingleFile(loose, "config file"));
        if (Files.isDirectory(config)) {
            try (Stream<Path> children = Files.list(config)) {
                children.sorted(newestFirst()).forEach(p -> {
                    if (Files.isDirectory(p)) {
                        DatapackSource dir = new DatapackSource.Directory(p, "config datapack folder");
                        if (dir.hasStartup()) out.add(dir);
                    } else if (p.getFileName().toString().toLowerCase().endsWith(".zip")) {
                        DatapackSource zip = DatapackSource.Zip.open(p, "config datapack zip");
                        if (zip != null) out.add(zip);
                    }
                });
            } catch (IOException ignored) {
                // nothing
            }
        }

        // 3. resource packs downloaded from servers (only useful when they contain data/ too)
        Path downloads = mc.gameDirectory.toPath().resolve("downloads");
        if (Files.isDirectory(downloads)) {
            try (Stream<Path> files = Files.walk(downloads, 3)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().endsWith(".json"))
                        .sorted(newestFirst())
                        .limit(20)
                        .forEach(p -> {
                            DatapackSource zip = DatapackSource.Zip.open(p, "server resource pack");
                            if (zip != null) out.add(zip);
                        });
            } catch (IOException ignored) {
                // nothing
            }
        }
        return out;
    }

    private static Comparator<Path> newestFirst() {
        return Comparator.comparingLong((Path p) -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }).reversed();
    }

    /** Reads config/terf-recipes/hidden.txt, creating a commented example the first time. */
    private static HiddenFilter loadHiddenFilter() {
        Path file = configDir().resolve("hidden.txt");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, HiddenFilter.DEFAULT_FILE, StandardCharsets.UTF_8);
            }
            HiddenFilter filter = HiddenFilter.parse(Files.readString(file, StandardCharsets.UTF_8));
            for (String bad : filter.invalidLines()) {
                TERFRecipes.LOGGER.warn("[TERF Recipes] Ignored line in hidden.txt: {}", bad);
            }
            return filter;
        } catch (IOException e) {
            TERFRecipes.LOGGER.warn("[TERF Recipes] Could not read {}", file, e);
            return HiddenFilter.parse(null);
        }
    }

    private static MachineDefs loadDefs() {
        InputStream bundled = TerfDataManager.class.getClassLoader().getResourceAsStream("terf-recipes/machines.json");
        Path overridePath = configDir().resolve("machines.json");
        try (Reader override = Files.isRegularFile(overridePath)
                ? Files.newBufferedReader(overridePath, StandardCharsets.UTF_8) : null) {
            return MachineDefs.load(bundled, override);
        } catch (IOException | RuntimeException e) {
            TERFRecipes.LOGGER.error("[TERF Recipes] Invalid machines.json, using bundled defaults", e);
            return MachineDefs.load(TerfDataManager.class.getClassLoader().getResourceAsStream("terf-recipes/machines.json"), null);
        }
    }

    /** Reads functions from the datapacks enabled in the singleplayer world. */
    private record ServerResourcesSource(IntegratedServer server) implements DatapackSource {
        @Override
        public String describe() {
            return "world datapacks (singleplayer)";
        }

        @Override
        public String readData(String path) {
            // "data/<ns>/<rest>" -> <ns>:<rest>
            if (!path.startsWith("data/")) return null;
            String rest = path.substring(5);
            int slash = rest.indexOf('/');
            if (slash <= 0) return null;
            Identifier file = Identifier.tryBuild(rest.substring(0, slash), rest.substring(slash + 1));
            if (file == null) return null;
            Optional<Resource> resource = server.getResourceManager().getResource(file);
            if (resource.isEmpty()) return null;
            try (InputStream in = resource.get().open()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }
    }
}
