package com.terfrecipes.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.terfrecipes.TERFRecipes;
import com.terfrecipes.data.source.DatapackSource;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Keeps a copy of the datapack from its GitHub repository in {@code config/terf-recipes/github/}.
 * <p>
 * Nothing is downloaded without the player's consent: the first {@code /terfrecipes update} only
 * explains what would be downloaded, and the download starts when the player confirms it
 * ({@code allowDownloads} in github.json). The optional startup check only asks GitHub for the
 * latest commit and tells the player in the chat; it never downloads by itself.
 * <p>
 * The latest commit of the branch is compared with the cached one; when it changed, the branch is
 * downloaded as a zip and only its {@code data/} files are kept. That copy replaces the one bundled
 * in the jar, but the datapack of the world / server (and files put in the config folder) still win.
 * Settings: {@code config/terf-recipes/github.json}.
 */
public final class GithubUpdater {

    public enum Status { UP_TO_DATE, UPDATE_AVAILABLE, UPDATED, NOT_ALLOWED, DISABLED, FAILED }

    public record Result(Status status, String message) {
    }

    /** github.json */
    public static final class Settings {
        public boolean enabled = true;
        /** Set when the player agreed to download the datapack (/terfrecipes update confirm). */
        public boolean allowDownloads = false;
        public String repo = "jona23EE/TERF_datapack";
        public String branch = "main";
        /** Sub folder of the repository holding the datapack ("" = root). */
        public String path = "";
        /** Ask GitHub for the latest commit when the game starts and say so in the chat (never downloads). */
        public boolean notifyOnStartup = false;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile @Nullable CompletableFuture<Result> running;

    private GithubUpdater() {
    }

    private static Path dir() {
        return TerfDataManager.configDir().resolve("github");
    }

    private static Path zipFile() {
        return dir().resolve("datapack.zip");
    }

    private static Path versionFile() {
        return dir().resolve("version.txt");
    }

    public static Settings settings() {
        Path file = TerfDataManager.configDir().resolve("github.json");
        try {
            if (Files.isRegularFile(file)) {
                Settings s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
                if (s != null) return s;
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(new Settings()), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            TERFRecipes.LOGGER.warn("[TERF Recipes] Could not read {}: {}", file, e.toString());
        }
        return new Settings();
    }

    public static void save(Settings settings) {
        Path file = TerfDataManager.configDir().resolve("github.json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(settings), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TERFRecipes.LOGGER.warn("[TERF Recipes] Could not write {}: {}", file, e.toString());
        }
    }

    /** Page of the repository, shown to the player before any download. */
    public static String repoUrl() {
        Settings s = settings();
        return "https://github.com/" + s.repo + (s.branch == null || s.branch.isBlank() ? "" : " (branch " + s.branch + ")");
    }

    /** Commit of the cached copy, or null. */
    public static @Nullable String cachedCommit() {
        try {
            if (!Files.isRegularFile(versionFile()) || !Files.isRegularFile(zipFile())) return null;
            String v = Files.readString(versionFile(), StandardCharsets.UTF_8).strip();
            return v.isEmpty() ? null : v;
        } catch (IOException e) {
            return null;
        }
    }

    /** The cached copy as a datapack source (null when never downloaded or disabled). */
    public static @Nullable DatapackSource source() {
        Settings s = settings();
        String commit = cachedCommit();
        if (!s.enabled || commit == null) return null;
        String shortSha = commit.length() > 7 ? commit.substring(0, 7) : commit;
        return DatapackSource.Zip.open(zipFile(), "GitHub " + s.repo + "@" + shortSha);
    }

    /**
     * Downloads the latest datapack when it changed. Only after the player's consent
     * ({@code allowDownloads}), otherwise returns NOT_ALLOWED without any network access.
     */
    public static synchronized CompletableFuture<Result> downloadAsync() {
        CompletableFuture<Result> current = running;
        if (current != null && !current.isDone()) return current;
        CompletableFuture<Result> f = CompletableFuture.supplyAsync(() -> check(true));
        running = f;
        return f;
    }

    /** Only asks GitHub for the latest commit (no file is downloaded): UP_TO_DATE or UPDATE_AVAILABLE. */
    public static CompletableFuture<Result> checkOnlyAsync() {
        return CompletableFuture.supplyAsync(() -> check(false));
    }

    private static Result check(boolean doDownload) {
        Settings s = settings();
        if (!s.enabled || s.repo == null || s.repo.isBlank()) return new Result(Status.DISABLED, "GitHub update disabled in github.json");
        if (doDownload && !s.allowDownloads) return new Result(Status.NOT_ALLOWED, "Download not confirmed");
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            // 1. latest commit of the branch ("application/vnd.github.sha" returns just the sha)
            HttpRequest shaRequest = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + s.repo + "/commits/" + s.branch))
                    .header("Accept", "application/vnd.github.sha")
                    .header("User-Agent", "terf-recipes")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> shaResponse = http.send(shaRequest, HttpResponse.BodyHandlers.ofString());
            if (shaResponse.statusCode() != 200) {
                return new Result(Status.FAILED, "GitHub answered " + shaResponse.statusCode() + " for " + s.repo);
            }
            String latest = shaResponse.body().strip();
            if (latest.equals(cachedCommit())) return new Result(Status.UP_TO_DATE, "Already up to date (" + shortSha(latest) + ")");
            if (!doDownload) return new Result(Status.UPDATE_AVAILABLE, "A newer TERF datapack is on GitHub (" + shortSha(latest) + ")");

            // 2. download the branch, keep only data/ (the resource pack part is not needed)
            HttpRequest zipRequest = HttpRequest.newBuilder(URI.create("https://codeload.github.com/" + s.repo + "/zip/" + latest))
                    .header("User-Agent", "terf-recipes")
                    .timeout(Duration.ofMinutes(3))
                    .GET().build();
            Files.createDirectories(dir());
            Path download = dir().resolve("download.tmp");
            HttpResponse<Path> zipResponse = http.send(zipRequest, HttpResponse.BodyHandlers.ofFile(download));
            if (zipResponse.statusCode() != 200) {
                Files.deleteIfExists(download);
                return new Result(Status.FAILED, "Download failed (" + zipResponse.statusCode() + ")");
            }
            Path filtered = dir().resolve("datapack.zip.tmp");
            int kept = keepDataFiles(download, filtered, s.path);
            Files.deleteIfExists(download);
            if (kept == 0 || DatapackSource.Zip.open(filtered, "check") == null) {
                Files.deleteIfExists(filtered);
                return new Result(Status.FAILED, "No TERF datapack (data/terf/function/startup.mcfunction) in " + s.repo);
            }
            Files.move(filtered, zipFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(versionFile(), latest, StandardCharsets.UTF_8);
            return new Result(Status.UPDATED, "Downloaded " + s.repo + "@" + shortSha(latest) + " (" + kept + " files)");
        } catch (IOException | RuntimeException e) {
            return new Result(Status.FAILED, "Could not reach GitHub: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Status.FAILED, "Interrupted");
        }
    }

    /**
     * Copies the entries under {@code <top folder>/<sub path>/data/} to a new zip, rooted at data/.
     *
     * @return number of files kept
     */
    static int keepDataFiles(Path source, Path target, String subPath) throws IOException {
        String sub = subPath == null ? "" : subPath.replace('\\', '/').replaceAll("^/+|/+$", "");
        int kept = 0;
        try (InputStream raw = Files.newInputStream(source);
             ZipInputStream in = new ZipInputStream(raw);
             OutputStream rawOut = Files.newOutputStream(target);
             ZipOutputStream out = new ZipOutputStream(rawOut)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue;
                String rel = name.substring(slash + 1); // drop "TERF_datapack-<sha>/"
                if (!sub.isEmpty()) {
                    if (!rel.startsWith(sub + "/")) continue;
                    rel = rel.substring(sub.length() + 1);
                }
                if (!rel.startsWith("data/")) continue;
                if (!(rel.endsWith(".mcfunction") || rel.endsWith(".json"))) continue;
                out.putNextEntry(new ZipEntry(rel));
                in.transferTo(out);
                out.closeEntry();
                kept++;
            }
        }
        return kept;
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
