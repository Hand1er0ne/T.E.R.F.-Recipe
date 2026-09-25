package com.terfrecipes.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the command a machine runs on completion into displayable outputs.
 * <p>
 * Understood forms:
 * <ul>
 *     <li>{@code summon item ~ ~ ~ {Item:{id:..,count:..,components:{..}}}}</li>
 *     <li>{@code function terf:require/custom_item_summon {count:4,id:copper_foil}}</li>
 *     <li>any {@code function ...fluid... {amount:..,id:"terf.x"}} (fluid outputs)</li>
 *     <li>{@code setblock ~ ~ ~ minecraft:diamond_ore}</li>
 *     <li>{@code function x:y} without arguments: the function is read (when the source has it) and
 *     its outputs are collected, with {@code datapipes_lib:chances/N} predicates turned into %</li>
 * </ul>
 * Anything else becomes a {@link TerfRecipe.OutputKind#SPECIAL} text output.
 */
public final class OutputParser {

    private static final Pattern SUMMON_ITEM = Pattern.compile("^summon\\s+(?:minecraft:)?item\\s+\\S+\\s+\\S+\\s+\\S+\\s+");
    private static final Pattern FUNCTION = Pattern.compile("^function\\s+(\\S+)\\s*(.*)$");
    private static final Pattern SETBLOCK = Pattern.compile("^setblock\\s+\\S+\\s+\\S+\\s+\\S+\\s+([^\\s\\[{]+)");
    private static final Pattern CHANCE = Pattern.compile("if\\s+predicate\\s+\\S*chances/([0-9.]+)");
    private static final Pattern EXECUTE_RUN = Pattern.compile("^execute\\s+(.*?)\\s+run\\s+(.*)$");

    private final Function<String, String> functionReader;

    /** @param functionReader reads a function file by id ("ns:path"), returns {@code null} when unavailable */
    public OutputParser(Function<String, String> functionReader) {
        this.functionReader = functionReader;
    }

    public List<TerfRecipe.Output> parse(String command) {
        List<TerfRecipe.Output> out = new ArrayList<>();
        parseInto(command, out, 0);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void parseInto(String command, List<TerfRecipe.Output> out, int depth) {
        String cmd = command == null ? "" : command.trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.isEmpty()) return;

        try {
            Matcher m = SUMMON_ITEM.matcher(cmd);
            if (m.find()) {
                Object nbt = Snbt.parsePrefix(cmd, m.end()).value();
                if (nbt instanceof Map<?, ?> map && map.get("Item") instanceof Map<?, ?> item) {
                    out.add(itemOutput((Map<String, Object>) item));
                    return;
                }
            }

            m = FUNCTION.matcher(cmd);
            if (m.find()) {
                String fn = m.group(1);
                String argText = m.group(2).trim();
                Map<String, Object> args = null;
                if (argText.startsWith("{")) {
                    Object parsed = Snbt.parsePrefix(argText, 0).value();
                    if (parsed instanceof Map<?, ?> pm) args = (Map<String, Object>) pm;
                }
                if (args != null && fn.endsWith("custom_item_summon") && args.get("id") != null) {
                    int count = orDefault(Snbt.asInt(args.get("count")), 1);
                    out.add(new TerfRecipe.Output(TerfRecipe.OutputKind.MATERIAL, Snbt.asString(args.get("id")),
                            count, null, null, null));
                    return;
                }
                if (args != null && fn.contains("fluid") && args.get("id") != null && args.get("amount") != null) {
                    out.add(new TerfRecipe.Output(TerfRecipe.OutputKind.FLUID, Snbt.asString(args.get("id")),
                            orDefault(Snbt.asInt(args.get("amount")), 1), null, null, null));
                    return;
                }
                if (args == null && argText.isEmpty() && depth < 2) {
                    String body = functionReader.apply(fn);
                    if (body != null) {
                        List<TerfRecipe.Output> nested = parseFunctionBody(body, depth + 1);
                        if (!nested.isEmpty()) {
                            out.addAll(nested);
                            return;
                        }
                    }
                }
                out.add(TerfRecipe.Output.special(describeFunction(fn)));
                return;
            }

            m = SETBLOCK.matcher(cmd);
            if (m.find()) {
                out.add(new TerfRecipe.Output(TerfRecipe.OutputKind.BLOCK, m.group(1), 1, null, null, null));
                return;
            }
        } catch (RuntimeException ignored) {
            // fall through to a text output
        }
        out.add(TerfRecipe.Output.special(describeCommand(cmd)));
    }

    /**
     * Collects the outputs of a function file used as a "random output table". Lines guarded by a
     * chance predicate get that chance; since they usually {@code return run}, the remaining
     * probability goes to the next lines.
     */
    private List<TerfRecipe.Output> parseFunctionBody(String body, int depth) {
        List<TerfRecipe.Output> result = new ArrayList<>();
        double remaining = 100.0;
        boolean anyChance = false;
        for (String line : StorageEmulator.joinContinuations(body)) {
            String cmd = line.trim();
            if (cmd.startsWith("$")) continue;
            Double chance = null;
            Matcher em = EXECUTE_RUN.matcher(cmd);
            if (em.find()) {
                Matcher cm = CHANCE.matcher(em.group(1));
                if (!cm.find()) continue; // world-dependent condition, skip
                chance = Double.parseDouble(cm.group(1));
                cmd = em.group(2).trim();
                if (cmd.startsWith("return run ")) cmd = cmd.substring("return run ".length());
                anyChance = true;
            } else if (cmd.startsWith("return run ")) {
                cmd = cmd.substring("return run ".length());
            }
            if (!(cmd.startsWith("summon") || cmd.startsWith("function") || cmd.startsWith("setblock")
                    || cmd.startsWith("loot") || cmd.startsWith("give"))) continue;
            List<TerfRecipe.Output> lineOutputs = new ArrayList<>();
            parseInto(cmd, lineOutputs, depth);
            lineOutputs.removeIf(o -> o.kind() == TerfRecipe.OutputKind.SPECIAL);
            double effective = chance == null ? remaining : remaining * chance / 100.0;
            for (TerfRecipe.Output o : lineOutputs) {
                result.add(anyChance ? o.withChance(round(effective)) : o);
            }
            if (chance != null) remaining -= effective;
        }
        return result;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @SuppressWarnings("unchecked")
    private static TerfRecipe.Output itemOutput(Map<String, Object> item) {
        String id = Snbt.asString(item.get("id"));
        int count = orDefault(Snbt.asInt(item.get("count")), orDefault(Snbt.asInt(item.get("Count")), 1));
        Map<String, Object> components = item.get("components") instanceof Map<?, ?> c
                ? (Map<String, Object>) c : null;
        return new TerfRecipe.Output(TerfRecipe.OutputKind.ITEM, id, count, components, null, null);
    }

    private static int orDefault(Integer v, int def) {
        return v == null ? def : v;
    }

    static String describeFunction(String fn) {
        String path = fn.contains(":") ? fn.substring(fn.indexOf(':') + 1) : fn;
        String[] parts = path.split("/");
        // entity/black_hole/summon -> "Black Hole" ; entity/vehicle/hoverbike/summon -> "Hoverbike"
        String last = parts[parts.length - 1];
        String name = parts.length >= 2 && (last.equals("summon") || last.startsWith("summon_"))
                ? parts[parts.length - 2] : last;
        return MachineDefs.prettify(name);
    }

    static String describeCommand(String cmd) {
        if (cmd.startsWith("summon ")) {
            String[] parts = cmd.split("\\s+");
            if (parts.length > 1) return "Summons " + parts[1].replace("minecraft:", "");
        }
        return cmd.length() > 60 ? cmd.substring(0, 60) + "..." : cmd;
    }

    /** Helper for callers that just need an empty component map. */
    public static Map<String, Object> newCompound() {
        return new LinkedHashMap<>();
    }
}
