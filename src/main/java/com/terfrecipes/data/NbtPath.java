package com.terfrecipes.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-implementation of the parts of vanilla's {@code NbtPathArgument} that a datapack uses to
 * write into command storage ({@code a.b.c}, {@code a[0]}, {@code a[]}, {@code a[{k:v}]},
 * {@code a{k:v}} and quoted keys).
 */
public final class NbtPath {

    sealed interface Node permits Key, Index, AllElements, MatchElement {
    }

    /** {@code key} or {@code key{filter}}. */
    record Key(String name, Map<String, Object> filter) implements Node {
    }

    /** {@code [n]} (negative = from end). */
    record Index(int index) implements Node {
    }

    /** {@code []}. */
    record AllElements() implements Node {
    }

    /** {@code [{filter}]}. */
    record MatchElement(Map<String, Object> filter) implements Node {
    }

    private final List<Node> nodes;
    private final String text;

    private NbtPath(List<Node> nodes, String text) {
        this.nodes = nodes;
        this.text = text;
    }

    public List<Node> nodes() {
        return nodes;
    }

    /** Keys of this path when it only contains plain keys, otherwise {@code null}. */
    public List<String> plainKeys() {
        List<String> out = new ArrayList<>();
        for (Node n : nodes) {
            if (!(n instanceof Key k) || k.filter() != null) return null;
            out.add(k.name());
        }
        return out;
    }

    @Override
    public String toString() {
        return text;
    }

    // ------------------------------------------------------------------ parsing

    public record ParseResult(NbtPath path, int end) {
    }

    /** Parses a path starting at {@code start}; stops at the first whitespace outside of brackets. */
    @SuppressWarnings("unchecked")
    public static ParseResult parse(String s, int start) {
        List<Node> nodes = new ArrayList<>();
        int i = start;
        boolean expectSegment = true;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) break;
            if (c == '.') {
                i++;
                expectSegment = true;
                continue;
            }
            if (c == '[') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) == ' ') j++;
                if (s.charAt(j) == ']') {
                    nodes.add(new AllElements());
                    i = j + 1;
                } else if (s.charAt(j) == '{') {
                    Snbt.Parsed p = Snbt.parsePrefix(s, j);
                    int k = p.end();
                    while (s.charAt(k) == ' ') k++;
                    if (s.charAt(k) != ']') throw new IllegalArgumentException("Expected ] in path " + s);
                    nodes.add(new MatchElement((Map<String, Object>) p.value()));
                    i = k + 1;
                } else {
                    int k = s.indexOf(']', j);
                    nodes.add(new Index(Integer.parseInt(s.substring(j, k).trim())));
                    i = k + 1;
                }
                expectSegment = false;
                continue;
            }
            if (c == '{') {
                // root or post-key compound filter
                Snbt.Parsed p = Snbt.parsePrefix(s, i);
                Map<String, Object> filter = (Map<String, Object>) p.value();
                if (!nodes.isEmpty() && nodes.get(nodes.size() - 1) instanceof Key k && k.filter() == null) {
                    nodes.set(nodes.size() - 1, new Key(k.name(), filter));
                }
                // a root filter ({..}.a) only restricts the match; ignore it
                i = p.end();
                expectSegment = false;
                continue;
            }
            if (!expectSegment) throw new IllegalArgumentException("Unexpected '" + c + "' in path " + s.substring(start));
            String key;
            if (c == '"' || c == '\'') {
                Snbt.Parsed p = Snbt.parsePrefix(s, i);
                key = (String) p.value();
                i = p.end();
            } else {
                int j = i;
                while (j < s.length() && isKeyChar(s.charAt(j))) j++;
                key = s.substring(i, j);
                i = j;
            }
            nodes.add(new Key(key, null));
            expectSegment = false;
        }
        if (nodes.isEmpty()) throw new IllegalArgumentException("Empty path");
        return new ParseResult(new NbtPath(nodes, s.substring(start, i)), i);
    }

    private static boolean isKeyChar(char c) {
        return c != ' ' && c != '"' && c != '\'' && c != '[' && c != ']' && c != '.' && c != '{' && c != '}'
                && !Character.isWhitespace(c);
    }

    // ------------------------------------------------------------------ reading

    /** All values currently matched by this path inside {@code root}. */
    public List<Object> get(Map<String, Object> root) {
        List<Object> current = new ArrayList<>();
        current.add(root);
        for (Node node : nodes) {
            List<Object> next = new ArrayList<>();
            for (Object o : current) collect(node, o, next);
            current = next;
            if (current.isEmpty()) break;
        }
        return current;
    }

    private static void collect(Node node, Object o, List<Object> out) {
        switch (node) {
            case Key k -> {
                if (o instanceof Map<?, ?> m && m.containsKey(k.name())) {
                    Object v = m.get(k.name());
                    if (k.filter() == null || Snbt.matches(k.filter(), v)) out.add(v);
                }
            }
            case Index idx -> {
                if (o instanceof List<?> l) {
                    int i = idx.index() < 0 ? l.size() + idx.index() : idx.index();
                    if (i >= 0 && i < l.size()) out.add(l.get(i));
                }
            }
            case AllElements a -> {
                if (o instanceof List<?> l) out.addAll(l);
            }
            case MatchElement me -> {
                if (o instanceof List<?> l) for (Object e : l) if (Snbt.matches(me.filter(), e)) out.add(e);
            }
        }
    }

    // ------------------------------------------------------------------ writing

    /** Vanilla {@code set}: creates missing parents and replaces the targeted value(s). */
    public int set(Map<String, Object> root, Object value) {
        List<Object> parents = getOrCreateParents(root);
        Node last = nodes.get(nodes.size() - 1);
        int changed = 0;
        for (Object parent : parents) changed += setOn(last, parent, value);
        return changed;
    }

    /** Vanilla {@code merge}: deep merges a compound into the target (creating it if needed). */
    @SuppressWarnings("unchecked")
    public int merge(Map<String, Object> root, Map<String, Object> value) {
        List<Object> targets = getOrCreate(root, true);
        int n = 0;
        for (Object t : targets) {
            if (t instanceof Map<?, ?> m) {
                Snbt.deepMerge((Map<String, Object>) m, value);
                n++;
            }
        }
        return n;
    }

    /** {@code append} / {@code prepend} / {@code insert i}. */
    @SuppressWarnings("unchecked")
    public int insert(Map<String, Object> root, int index, Object value) {
        List<Object> targets = getOrCreate(root, false);
        int n = 0;
        for (Object t : targets) {
            if (t instanceof List<?> l) {
                List<Object> list = (List<Object>) l;
                int i = index < 0 ? list.size() + index + 1 : index;
                i = Math.max(0, Math.min(list.size(), i));
                list.add(i, Snbt.copy(value));
                n++;
            }
        }
        return n;
    }

    /** {@code data remove}. */
    @SuppressWarnings("unchecked")
    public int remove(Map<String, Object> root) {
        List<Object> parents = new ArrayList<>();
        parents.add(root);
        for (int i = 0; i < nodes.size() - 1; i++) {
            List<Object> next = new ArrayList<>();
            for (Object o : parents) collect(nodes.get(i), o, next);
            parents = next;
        }
        Node last = nodes.get(nodes.size() - 1);
        int n = 0;
        for (Object p : parents) {
            switch (last) {
                case Key k -> {
                    if (p instanceof Map<?, ?> m && m.containsKey(k.name())
                            && (k.filter() == null || Snbt.matches(k.filter(), m.get(k.name())))) {
                        m.remove(k.name());
                        n++;
                    }
                }
                case Index idx -> {
                    if (p instanceof List<?> l) {
                        int i = idx.index() < 0 ? l.size() + idx.index() : idx.index();
                        if (i >= 0 && i < l.size()) {
                            l.remove(i);
                            n++;
                        }
                    }
                }
                case AllElements a -> {
                    if (p instanceof List<?> l) {
                        n += l.size();
                        l.clear();
                    }
                }
                case MatchElement me -> {
                    if (p instanceof List<?> l) {
                        int before = l.size();
                        ((List<Object>) l).removeIf(e -> Snbt.matches(me.filter(), e));
                        n += before - l.size();
                    }
                }
            }
        }
        return n;
    }

    private List<Object> getOrCreateParents(Map<String, Object> root) {
        List<Object> current = new ArrayList<>();
        current.add(root);
        for (int i = 0; i < nodes.size() - 1; i++) {
            Node next = nodes.get(i + 1);
            List<Object> out = new ArrayList<>();
            for (Object o : current) getOrCreate(nodes.get(i), o, preferredParent(next), out);
            current = out;
        }
        return current;
    }

    private List<Object> getOrCreate(Map<String, Object> root, boolean compoundLeaf) {
        List<Object> current = new ArrayList<>();
        current.add(root);
        for (int i = 0; i < nodes.size(); i++) {
            Object preferred = i + 1 < nodes.size() ? preferredParent(nodes.get(i + 1))
                    : compoundLeaf ? new LinkedHashMap<String, Object>() : new ArrayList<>();
            List<Object> out = new ArrayList<>();
            for (Object o : current) getOrCreate(nodes.get(i), o, preferred, out);
            current = out;
        }
        return current;
    }

    private static Object preferredParent(Node child) {
        return child instanceof Key ? new LinkedHashMap<String, Object>() : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static void getOrCreate(Node node, Object o, Object preferred, List<Object> out) {
        switch (node) {
            case Key k -> {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> map = (Map<String, Object>) m;
                    Object v = map.get(k.name());
                    if (v == null) {
                        v = k.filter() != null ? Snbt.copy(k.filter()) : Snbt.copy(preferred);
                        map.put(k.name(), v);
                    }
                    if (k.filter() == null || Snbt.matches(k.filter(), v)) out.add(v);
                }
            }
            case MatchElement me -> {
                if (o instanceof List<?> l) {
                    List<Object> list = (List<Object>) l;
                    boolean found = false;
                    for (Object e : list) {
                        if (Snbt.matches(me.filter(), e)) {
                            out.add(e);
                            found = true;
                        }
                    }
                    if (!found) {
                        Object created = Snbt.copy(me.filter());
                        list.add(created);
                        out.add(created);
                    }
                }
            }
            default -> collect(node, o, out);
        }
    }

    @SuppressWarnings("unchecked")
    private static int setOn(Node last, Object parent, Object value) {
        switch (last) {
            case Key k -> {
                if (parent instanceof Map<?, ?> m) {
                    ((Map<String, Object>) m).put(k.name(), Snbt.copy(value));
                    return 1;
                }
            }
            case Index idx -> {
                if (parent instanceof List<?> l) {
                    int i = idx.index() < 0 ? l.size() + idx.index() : idx.index();
                    if (i >= 0 && i < l.size()) {
                        ((List<Object>) l).set(i, Snbt.copy(value));
                        return 1;
                    }
                }
            }
            case AllElements a -> {
                if (parent instanceof List<?> l) {
                    List<Object> list = (List<Object>) l;
                    for (int i = 0; i < list.size(); i++) list.set(i, Snbt.copy(value));
                    return list.size();
                }
            }
            case MatchElement me -> {
                if (parent instanceof List<?> l) {
                    List<Object> list = (List<Object>) l;
                    int n = 0;
                    for (int i = 0; i < list.size(); i++) {
                        if (Snbt.matches(me.filter(), list.get(i))) {
                            list.set(i, Snbt.copy(value));
                            n++;
                        }
                    }
                    if (n == 0) {
                        // vanilla creates the element from the filter, then replaces it
                        list.add(Snbt.copy(value));
                        n = 1;
                    }
                    return n;
                }
            }
        }
        return 0;
    }
}
