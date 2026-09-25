package com.terfrecipes.data;

import java.util.List;
import java.util.Map;

/** A machine recipe extracted from the datapack, independent of Minecraft classes. */
public record TerfRecipe(
        String machine,
        String id,
        String title,
        List<Input> inputs,
        List<Output> outputs,
        Map<String, Snbt.Num> params,
        List<String> notes
) {

    public enum InputKind {
        /** Item key as the datapack compares it: custom_data.id if present, else the item id. */
        ITEM,
        /** Fluid id as stored by datapipes_lib (e.g. {@code terf.hydrogen}, {@code water}). */
        FLUID,
        /** Empty slot (fabricator "z"). */
        EMPTY
    }

    /**
     * @param key   item key / fluid id
     * @param count amount (items) or fluid amount; 1 when unknown
     * @param slot  position index in the machine (grid slot for the fabricator)
     */
    public record Input(InputKind kind, String key, int count, int slot) {
    }

    public enum OutputKind {
        /** Plain item from {@code summon item}: {@link Output#components} may hold item components. */
        ITEM,
        /** Custom TERF item from {@code custom_item_summon}: {@link Output#key} is a material id. */
        MATERIAL,
        /** Fluid added to a tank. */
        FLUID,
        /** Block placed with setblock (shown as its item). */
        BLOCK,
        /** Anything else (entity, explosion...), shown as text. */
        SPECIAL
    }

    /**
     * @param chance probability in percent when the output is random, otherwise {@code null}
     */
    public record Output(OutputKind kind, String key, int count, Map<String, Object> components,
                         Double chance, String text) {
        public static Output special(String text) {
            return new Output(OutputKind.SPECIAL, null, 1, null, null, text);
        }

        public Output withChance(Double chance) {
            return new Output(kind, key, count, components, chance, text);
        }
    }
}
