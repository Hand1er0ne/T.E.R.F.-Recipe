package com.terfrecipes.data.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Somewhere we can read the datapack's function files from. */
public interface DatapackSource {

    String STARTUP = "terf:startup";

    /** Human readable description (shown by /terfrecipes). */
    String describe();

    /** Reads a file of the datapack by relative path ({@code data/ns/...}), or returns {@code null}. */
    String readData(String path);

    /** Reads a function by id ({@code namespace:path}), or returns {@code null}. */
    default String readFunction(String functionId) {
        return readData(functionPath(functionId));
    }

    default boolean hasStartup() {
        return readFunction(STARTUP) != null;
    }

    static String functionPath(String functionId) {
        int colon = functionId.indexOf(':');
        String ns = colon < 0 ? "minecraft" : functionId.substring(0, colon);
        String path = colon < 0 ? functionId : functionId.substring(colon + 1);
        return "data/" + ns + "/function/" + path + ".mcfunction";
    }

    // ------------------------------------------------------------------ implementations

    /** An unzipped datapack folder (the one containing {@code data/}). */
    record Directory(Path root, String label) implements DatapackSource {
        @Override
        public String describe() {
            return label + " (" + root + ")";
        }

        @Override
        public String readData(String path) {
            Path p = root.resolve(path);
            try {
                return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** A zipped datapack; the {@code data/} folder may be nested one directory deep. */
    final class Zip implements DatapackSource {
        private final Path file;
        private final String label;
        private final String prefix;

        private Zip(Path file, String label, String prefix) {
            this.file = file;
            this.label = label;
            this.prefix = prefix;
        }

        /** Returns a source if the zip contains the TERF startup function, else {@code null}. */
        public static Zip open(Path file, String label) {
            String wanted = functionPath(STARTUP);
            try (ZipFile zip = new ZipFile(file.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(wanted)) return new Zip(file, label, name.substring(0, name.length() - wanted.length()));
                }
            } catch (IOException | RuntimeException ignored) {
                // not a zip, or unreadable
            }
            return null;
        }

        @Override
        public String describe() {
            return label + " (" + file.getFileName() + ")";
        }

        /** data/ files, read once (the structure scan reads hundreds of functions). */
        private java.util.Map<String, String> dataFiles;

        @Override
        public synchronized String readData(String path) {
            if (dataFiles == null) {
                dataFiles = new java.util.HashMap<>();
                String root = prefix + "data/";
                try (ZipFile zip = new ZipFile(file.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        String name = e.getName();
                        if (e.isDirectory() || !name.startsWith(root)) continue;
                        if (!(name.endsWith(".mcfunction") || name.endsWith(".json"))) continue;
                        try (InputStream in = zip.getInputStream(e)) {
                            dataFiles.put(name.substring(prefix.length()), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                } catch (IOException e) {
                    // keep what was read
                }
            }
            return dataFiles.get(path);
        }
    }

    /** A single startup.mcfunction file dropped somewhere (no other function available). */
    record SingleFile(Path file, String label) implements DatapackSource {
        @Override
        public String describe() {
            return label + " (" + file.getFileName() + ")";
        }

        @Override
        public String readData(String path) {
            if (!functionPath(STARTUP).equals(path)) return null;
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** The copy of the datapack's functions bundled in the mod jar at build time. */
    record Bundled(ClassLoader loader) implements DatapackSource {
        static final String ROOT = "terf-recipes/datapack/";

        @Override
        public String describe() {
            return "copy bundled in the mod";
        }

        @Override
        public String readData(String path) {
            try (InputStream in = loader.getResourceAsStream(ROOT + path)) {
                return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }
    }
}
