package com.terfrecipes.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebuilds the multiblock structures from the datapack.
 * <p>
 * TERF has no structure files: {@code storage terf:constants mb_setup_functions} lists, for each
 * machine, the block the core must be placed in ({@code checks}) and its setup function. The
 * shape itself only exists as {@code if block ^x ^y ^z <block>} conditions spread over the
 * machine's setup / tick / checks functions. This class follows those functions and collects the
 * conditions, keeping track of {@code positioned} offsets.
 */
public final class StructureExtractor {

    /** Structures bigger than this are shown as a block list instead of layers. */
    public static final int MAX_LAYER_BLOCKS = 80;
    public static final int MAX_LAYER_SIZE = 9;
    public static final int MAX_LAYER_HEIGHT = 8;

    private static final int MAX_DEPTH = 6;
    private static final Pattern MACHINE_FN = Pattern.compile("terf:entity/machines/([a-z0-9_]+)/([a-z0-9_/]+)");
    private static final Pattern QUOTED_CHECKS = Pattern.compile("checks:(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')");
    private static final Pattern MB_FUNCTION = Pattern.compile("multiblock_function set value (\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')");
    /** Functions that never describe the structure (effects, states, explosions...). */
    private static final Pattern IGNORED_FN = Pattern.compile(
            "(^|/)(operation|complete|explosion|explode|states|visuals|particle|sound|destroy|kill|stop|shoot|finishing|reboot\\w*|ring|meltdown|detonation|emergency_controls|calculations|test)(/|$|_)");
    private static final String FLUID_PORT_FN = "terf:require/observer_fluid_checks";

    private final Function<String, String> functionReader;
    private final Function<String, String> dataReader;
    private final MachineDefs defs;
    private final Map<String, List<String>> tagCache = new LinkedHashMap<>();

    public StructureExtractor(Function<String, String> functionReader, Function<String, String> dataReader, MachineDefs defs) {
        this.functionReader = functionReader == null ? id -> null : functionReader;
        this.dataReader = dataReader == null ? p -> null : dataReader;
        this.defs = defs;
    }

    /** @param setupTable content of {@code storage terf:constants mb_setup_functions} */
    @SuppressWarnings("unchecked")
    public List<Multiblock> extract(Object setupTable) {
        List<Multiblock> out = new ArrayList<>();
        if (!(setupTable instanceof List<?> list)) return out;
        Set<String> usedIds = new HashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> entry = (Map<String, Object>) m;
            String checks = Snbt.asString(entry.get("checks"));
            String function = Snbt.asString(entry.get("function"));
            if (checks == null || function == null) continue;
            Multiblock mb = extractOne(checks, function, usedIds);
            if (mb != null) out.add(mb);
        }
        return out;
    }

    private Multiblock extractOne(String checks, String function, Set<String> usedIds) {
        Matcher mm = MACHINE_FN.matcher(function);
        if (!mm.find()) return null; // e.g. the "fire" entry that only adds a tag
        String machine = mm.group(1);
        String entryFn = mm.group(2);

        String variant = null;
        if (entryFn.startsWith("setup_")) variant = MachineDefs.prettify(entryFn.substring(6));
        String id = machine + (variant != null ? "_" + variant.toLowerCase(Locale.ROOT).replace(' ', '_') : "");
        if (!usedIds.add(id)) return null;

        Collector c = new Collector(machine);
        // 1. core block (+ the extra conditions of the setup table)
        c.chain(checks, 0, 0, 0, Multiblock.Role.CORE, 0);
        // 2. setup function (or inline multiblock_function)
        if (function.startsWith("function ")) {
            c.chain(function, 0, 0, 0, Multiblock.Role.NORMAL, 0);
        } else {
            c.line(function, 0);
        }

        if (c.blocks.isEmpty()) return null;
        MachineDefs.MachineDef def = defs.get(machine);
        String name = def.name != null ? def.name : MachineDefs.prettify(machine);
        if (variant != null) name += " (" + variant + ")";

        List<String> notes = new ArrayList<>(c.notes);
        Multiblock tmp = new Multiblock(id, machine, name, c.blocks, notes, false);
        int dx = tmp.maxX() - tmp.minX() + 1;
        int dy = tmp.maxY() - tmp.minY() + 1;
        int dz = tmp.maxZ() - tmp.minZ() + 1;
        boolean complex = c.blocks.size() > MAX_LAYER_BLOCKS || dx > MAX_LAYER_SIZE || dz > MAX_LAYER_SIZE
                || dy > MAX_LAYER_HEIGHT || c.dynamic;
        if ("list".equals(def.structure)) complex = true;
        else if ("layers".equals(def.structure)) complex = false;
        if ("hidden".equals(def.structure)) return null;
        if (c.dynamic) notes.add("Size or shape is variable: only the checked blocks are listed");
        return new Multiblock(id, machine, name, Collections.unmodifiableMap(c.blocks),
                Collections.unmodifiableList(notes), complex);
    }

    // ------------------------------------------------------------------ collecting

    private final class Collector {
        final String machine;
        final Map<Multiblock.Pos, Multiblock.BlockSpec> blocks = new LinkedHashMap<>();
        final Set<String> visited = new HashSet<>();
        final List<String> notes = new ArrayList<>();
        boolean dynamic;
        boolean fluidNote;

        Collector(String machine) {
            this.machine = machine;
        }

        void function(String fnId, int ox, int oy, int oz, int depth) {
            String fn = fnId.contains(":") ? fnId : "minecraft:" + fnId;
            if (fn.equals(FLUID_PORT_FN)) {
                put(new Multiblock.Pos(ox, oy, oz), spec("observer", Multiblock.Role.FLUID));
                if (!fluidNote) {
                    notes.add("Fluid ports: observer facing outwards with red glazed terracotta behind it");
                    fluidNote = true;
                }
                return;
            }
            Matcher m = MACHINE_FN.matcher(fn);
            if (!m.matches() || !m.group(1).equals(machine)) return;
            if (IGNORED_FN.matcher(m.group(2)).find() || depth > MAX_DEPTH) return;
            if (!visited.add(fn + "@" + ox + "," + oy + "," + oz)) return;
            String body = functionReader.apply(fn);
            if (body == null) return;
            for (String line : StorageEmulator.joinContinuations(body)) {
                if (line.startsWith("$")) continue; // macro: runtime values
                line(line, depth, ox, oy, oz);
            }
        }

        void line(String line, int depth) {
            line(line, depth, 0, 0, 0);
        }

        void line(String line, int depth, int ox, int oy, int oz) {
            // strings holding execute chains: multiblock_function and power / fluid "checks"
            Matcher mb = MB_FUNCTION.matcher(line);
            if (mb.find()) chain(unquote(mb.group(1)), ox, oy, oz, Multiblock.Role.NORMAL, depth);
            Matcher ck = QUOTED_CHECKS.matcher(line);
            while (ck.find()) {
                Multiblock.Role role = line.contains("data.power") ? Multiblock.Role.POWER
                        : line.contains("data.fluids") ? Multiblock.Role.FLUID : Multiblock.Role.NORMAL;
                chain(unquote(ck.group(1)), ox, oy, oz, role, depth);
            }
            if (mb.find(0) || line.contains("checks:")) return;
            if (line.startsWith("execute ") || line.startsWith("function ") || line.startsWith("return run ")) {
                chain(line, ox, oy, oz, Multiblock.Role.NORMAL, depth);
            }
        }

        /** Walks an execute chain (with or without the leading "execute"). */
        void chain(String text, int ox, int oy, int oz, Multiblock.Role role, int depth) {
            List<String> t = tokenize(text);
            int x = ox, y = oy, z = oz;
            // "execute unless block X run return fail" means X is REQUIRED
            // (also "... run return run <error message>")
            boolean guard = isGuard(text);
            for (int i = 0; i < t.size(); i++) {
                String tok = t.get(i);
                switch (tok) {
                    case "positioned" -> {
                        if (i + 3 < t.size() && isCoord(t.get(i + 1))) {
                            Integer a = coord(t.get(i + 1)), b = coord(t.get(i + 2)), cc = coord(t.get(i + 3));
                            if (a == null || b == null || cc == null) return; // absolute position
                            x += a;
                            y += b;
                            z += cc;
                            i += 3;
                        } else {
                            return; // positioned as <entity>
                        }
                    }
                    case "at" -> {
                        if (i + 1 < t.size() && t.get(i + 1).startsWith("@s")) {
                            x = ox;
                            y = oy;
                            z = oz;
                            i++;
                        } else {
                            return;
                        }
                    }
                    case "rotated", "facing", "anchored", "align" -> {
                        return; // orientation changes: local coordinates no longer comparable
                    }
                    case "if", "unless" -> {
                        boolean negate = tok.equals("unless") != guard;
                        if (i + 1 >= t.size()) return;
                        String kind = t.get(i + 1);
                        if (kind.equals("block") && i + 5 < t.size()) {
                            Integer a = coord(t.get(i + 2)), b = coord(t.get(i + 3)), cc = coord(t.get(i + 4));
                            String pred = t.get(i + 5);
                            if (!negate && a != null && b != null && cc != null) {
                                Multiblock.Role r = role;
                                Multiblock.Pos pos = new Multiblock.Pos(x + a, y + b, z + cc);
                                if (role == Multiblock.Role.CORE && !(a == 0 && b == 0 && cc == 0)) r = Multiblock.Role.NORMAL;
                                put(pos, spec(pred, r));
                            }
                            i += 5;
                        } else if (kind.equals("blocks")) {
                            dynamic = true;
                            i += 1;
                        } else if (kind.equals("function") && i + 2 < t.size()) {
                            if (!negate) function(t.get(i + 2), x, y, z, depth + 1);
                            if (guard && !negate) return; // "if function X run return fail": X describes what must NOT be there
                            i += 2;
                        } else {
                            i += 1; // entity / score / data / predicate...: arguments are skipped by the loop
                        }
                    }
                    case "run" -> {
                        // continue with the command that is run
                    }
                    case "function" -> {
                        if (i + 1 < t.size()) {
                            function(t.get(i + 1), x, y, z, depth + 1);
                        }
                        return;
                    }
                    case "fill", "setblock", "clone", "summon", "tp", "teleport", "data", "scoreboard", "tag", "say",
                         "tellraw", "playsound", "particle", "kill", "item", "loot", "give" -> {
                        return; // a plain command: nothing more to collect in this chain
                    }
                    default -> {
                        // arguments of skipped sub-commands, "execute", "return"...
                    }
                }
            }
        }

        void put(Multiblock.Pos pos, Multiblock.BlockSpec spec) {
            Multiblock.BlockSpec existing = blocks.get(pos);
            if (existing == null) {
                blocks.put(pos, spec);
            } else if (existing.role() == Multiblock.Role.NORMAL && spec.role() != Multiblock.Role.NORMAL
                    && existing.id().equals(spec.id())) {
                blocks.put(pos, existing.withRole(spec.role()));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    Multiblock.BlockSpec spec(String predicate, Multiblock.Role role) {
        String raw = predicate.replaceAll("['\"}]+$", "").trim();
        String id = raw;
        String states = "";
        int bracket = raw.indexOf('[');
        int brace = raw.indexOf('{');
        int cut = bracket >= 0 ? bracket : brace;
        if (cut >= 0) {
            id = raw.substring(0, cut);
            if (bracket >= 0) {
                int end = raw.indexOf(']', bracket);
                states = raw.substring(bracket + 1, end < 0 ? raw.length() : end);
            }
        }
        boolean tag = id.startsWith("#");
        String bare = tag ? id.substring(1) : id;
        if (!bare.contains(":")) bare = "minecraft:" + bare;
        id = tag ? "#" + bare : bare;
        String display = id.replace("minecraft:", "") + (states.isEmpty() ? "" : "[" + states + "]");
        List<String> alternatives = tag ? resolveTag(bare) : List.of(bare);
        return new Multiblock.BlockSpec(display, id, states, alternatives, role);
    }

    /** Resolves a datapack block tag (recursively). Unknown tags are kept as "#ns:path". */
    List<String> resolveTag(String tagId) {
        List<String> cached = tagCache.get(tagId);
        if (cached != null) return cached;
        tagCache.put(tagId, List.of("#" + tagId)); // cycle guard
        int colon = tagId.indexOf(':');
        String path = "data/" + tagId.substring(0, colon) + "/tags/block/" + tagId.substring(colon + 1) + ".json";
        String json = dataReader.apply(path);
        List<String> out = new ArrayList<>();
        if (json == null) {
            out.add("#" + tagId); // vanilla tag: resolved on the client with the registry
        } else {
            try {
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                JsonArray values = obj.getAsJsonArray("values");
                for (JsonElement e : values) {
                    String v = e.isJsonObject() ? e.getAsJsonObject().get("id").getAsString() : e.getAsString();
                    if (v.startsWith("#")) {
                        String inner = v.substring(1);
                        if (!inner.contains(":")) inner = "minecraft:" + inner;
                        out.addAll(resolveTag(inner));
                    } else {
                        out.add(v.contains(":") ? v : "minecraft:" + v);
                    }
                }
            } catch (RuntimeException ex) {
                out.add("#" + tagId);
            }
        }
        List<String> result = List.copyOf(new java.util.LinkedHashSet<>(out));
        tagCache.put(tagId, result);
        return result;
    }

    /** A chain that aborts when its conditions pass: its "unless" conditions are requirements. */
    static boolean isGuard(String chain) {
        int run = chain.indexOf(" run ");
        if (run < 0) return false;
        String cmd = chain.substring(run + 5).strip();
        if (cmd.equals("return fail") || cmd.equals("return 0")) return true;
        return cmd.startsWith("return run ") && !cmd.startsWith("return run function") && !cmd.startsWith("return run execute");
    }

    private static boolean isCoord(String s) {
        return !s.isEmpty() && (s.charAt(0) == '^' || s.charAt(0) == '~' || s.charAt(0) == '-' || Character.isDigit(s.charAt(0)));
    }

    /** Relative coordinate -> block offset; {@code null} for absolute coordinates. */
    private static Integer coord(String s) {
        if (s.isEmpty() || (s.charAt(0) != '^' && s.charAt(0) != '~')) return null;
        String n = s.substring(1);
        if (n.isEmpty()) return 0;
        try {
            return (int) Math.round(Double.parseDouble(n));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String unquote(String quoted) {
        Object v = Snbt.parse(quoted);
        return v instanceof String s ? s : quoted;
    }

    /** Splits on spaces, keeping [...], {...} and quoted strings in one token. */
    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && i + 1 < s.length()) cur.append(s.charAt(++i));
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
            } else if (c == '[' || c == '{' || c == '(') {
                depth++;
                cur.append(c);
            } else if (c == ']' || c == '}' || c == ')') {
                depth = Math.max(0, depth - 1);
                cur.append(c);
            } else if (Character.isWhitespace(c) && depth == 0) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }
}
