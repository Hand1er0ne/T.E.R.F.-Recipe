package com.terfrecipes.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A TERF multiblock structure, relative to the Multiblock Core.
 * <p>
 * Coordinates are "local" like the datapack's {@code ^x ^y ^z}: +x = left, +y = up, +z = forward
 * (the direction the player faced when placing the core).
 */
public record Multiblock(
        String id,
        String machine,
        String name,
        Map<Pos, BlockSpec> blocks,
        List<String> notes,
        boolean complex
) {

    public record Pos(int x, int y, int z) {
    }

    public enum Role {
        /** The block the Multiblock Core is placed in. */
        CORE,
        /** Energy input (datapipes power checks). */
        POWER,
        /** Fluid input/output port. */
        FLUID,
        NORMAL
    }

    /**
     * @param raw          block predicate as written in the datapack, e.g. {@code iron_trapdoor[open=false]}
     * @param id           block id or {@code #tag}
     * @param states       block states without brackets ("" when none)
     * @param alternatives block ids accepted by a tag (datapack tags resolved when possible), else [id]
     */
    public record BlockSpec(String raw, String id, String states, List<String> alternatives, Role role) {
        public boolean isTag() {
            return id.startsWith("#");
        }

        BlockSpec withRole(Role r) {
            return new BlockSpec(raw, id, states, alternatives, r);
        }
    }

    public int minX() {
        return blocks.keySet().stream().mapToInt(Pos::x).min().orElse(0);
    }

    public int maxX() {
        return blocks.keySet().stream().mapToInt(Pos::x).max().orElse(0);
    }

    public int minY() {
        return blocks.keySet().stream().mapToInt(Pos::y).min().orElse(0);
    }

    public int maxY() {
        return blocks.keySet().stream().mapToInt(Pos::y).max().orElse(0);
    }

    public int minZ() {
        return blocks.keySet().stream().mapToInt(Pos::z).min().orElse(0);
    }

    public int maxZ() {
        return blocks.keySet().stream().mapToInt(Pos::z).max().orElse(0);
    }

    /** Block predicate -> amount, in first-seen order (for the list view). */
    public Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (BlockSpec b : blocks.values()) out.merge(b.raw(), 1, Integer::sum);
        return out;
    }

    /** One representative spec per distinct predicate (same order as {@link #counts()}). */
    public List<BlockSpec> distinctSpecs() {
        Map<String, BlockSpec> out = new LinkedHashMap<>();
        for (BlockSpec b : blocks.values()) out.putIfAbsent(b.raw(), b);
        return new ArrayList<>(out.values());
    }
}
