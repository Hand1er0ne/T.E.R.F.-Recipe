package com.terfrecipes.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes the {@code data modify storage ...} / {@code data remove storage ...} commands of a
 * .mcfunction file against an in-memory copy of command storage. Every other command is ignored.
 * <p>
 * This is what makes the mod "dynamic": we don't hardcode any recipe, we replay the datapack's own
 * startup function and then read what it stored.
 */
public final class StorageEmulator {

    private static final Pattern MODIFY = Pattern.compile("^data\\s+modify\\s+storage\\s+(\\S+)\\s+");
    private static final Pattern REMOVE = Pattern.compile("^data\\s+remove\\s+storage\\s+(\\S+)\\s+");
    private static final Pattern EXECUTE_DATA = Pattern.compile(
            "^execute\\s+(if|unless)\\s+data\\s+storage\\s+(\\S+)\\s+");

    private final Map<String, Map<String, Object>> storages = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private int appliedCommands;

    public Map<String, Object> storage(String id) {
        return storages.computeIfAbsent(normalizeId(id), k -> new LinkedHashMap<>());
    }

    public List<String> errors() {
        return errors;
    }

    public int appliedCommands() {
        return appliedCommands;
    }

    private static String normalizeId(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /** Runs a whole function file. */
    public void run(String functionText) {
        for (String line : joinContinuations(functionText)) {
            try {
                execute(line.trim());
            } catch (RuntimeException e) {
                if (errors.size() < 200) errors.add(e.getMessage() + " <- " + abbreviate(line));
            }
        }
    }

    /** Joins lines ending with a backslash (1.20.2+ syntax) and drops comments/blank lines. */
    public static List<String> joinContinuations(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = null;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (current == null && (line.isEmpty() || line.startsWith("#"))) continue;
            if (line.endsWith("\\")) {
                if (current == null) current = new StringBuilder();
                current.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            if (current != null) {
                current.append(line);
                out.add(current.toString());
                current = null;
            } else {
                out.add(line);
            }
        }
        if (current != null) out.add(current.toString());
        return out;
    }

    private void execute(String line) {
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("$")) return; // macro lines need runtime args
        if (line.startsWith("execute ")) {
            executeConditional(line);
            return;
        }
        Matcher m = MODIFY.matcher(line);
        if (m.find()) {
            modify(storage(m.group(1)), line, m.end());
            return;
        }
        m = REMOVE.matcher(line);
        if (m.find()) {
            NbtPath.parse(line, m.end()).path().remove(storage(m.group(1)));
            appliedCommands++;
        }
    }

    /** Supports {@code execute (if|unless) data storage <id> <path> run <command>}. */
    private void executeConditional(String line) {
        Matcher m = EXECUTE_DATA.matcher(line);
        if (!m.find()) return; // other execute forms depend on the world
        NbtPath.ParseResult pr = NbtPath.parse(line, m.end());
        boolean exists = !pr.path().get(storage(m.group(2))).isEmpty();
        boolean pass = m.group(1).equals("if") == exists;
        String rest = line.substring(pr.end()).trim();
        if (!rest.startsWith("run ")) return;
        if (pass) execute(rest.substring(4).trim());
    }

    @SuppressWarnings("unchecked")
    private void modify(Map<String, Object> root, String line, int pathStart) {
        NbtPath.ParseResult pr = NbtPath.parse(line, pathStart);
        NbtPath path = pr.path();
        String rest = line.substring(pr.end()).trim();

        String op;
        int index = 0;
        if (rest.startsWith("set ")) op = "set";
        else if (rest.startsWith("merge ")) op = "merge";
        else if (rest.startsWith("append ")) {
            op = "insert";
            index = -1;
        } else if (rest.startsWith("prepend ")) op = "insert";
        else if (rest.startsWith("insert ")) {
            String[] parts = rest.split("\\s+", 3);
            op = "insert";
            index = Integer.parseInt(parts[1]);
            rest = "insert " + parts[2];
        } else return;

        rest = rest.substring(rest.indexOf(' ') + 1).trim();
        Object value;
        if (rest.startsWith("value ")) {
            value = Snbt.parse(rest.substring(6).trim());
        } else if (rest.startsWith("from storage ")) {
            String[] parts = rest.split("\\s+", 4);
            List<Object> src = NbtPath.parse(parts[3], 0).path().get(storage(parts[2]));
            if (src.isEmpty()) return;
            value = Snbt.copy(src.get(0));
        } else {
            return; // from block/entity, string ..., etc. depend on the world
        }

        switch (op) {
            case "set" -> path.set(root, value);
            case "merge" -> {
                if (value instanceof Map<?, ?> map) path.merge(root, (Map<String, Object>) map);
            }
            default -> path.insert(root, index, value);
        }
        appliedCommands++;
    }

    private static String abbreviate(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}
