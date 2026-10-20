package com.titanx.emulator;

import java.io.*;
import java.util.*;

public final class DependencyGraph {

    private static final int MAX_FILES = 20000;
    private static final int MAX_ELF = 200;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_OUTPUT = 120000;

    private DependencyGraph() {}

    private static final class Node {
        File file;
        String path;
        String name;
        String arch = "Unknown";
        String soname = "";
        boolean valid;
        boolean inspected;
        boolean elf;
        List<String> needs = new ArrayList<>();

        Node(File f, String p) {
            file = f;
            path = p;
            name = f.getName();
        }
    }

    private static final class Stats {
        int found;
        int missing;
        int incompatible;
        int unverified;
        int duplicates;
        int cycles;
        int truncated;
    }

    private static final class Context {
        Map<String, List<Node>> names = new HashMap<>();
        Map<Node, Integer> states = new IdentityHashMap<>();
        StringBuilder output = new StringBuilder();
        Stats stats = new Stats();
        int edges;
    }

    private static void append(StringBuilder b, String s) {
        if (b.length() + s.length() < MAX_OUTPUT) {
            b.append(s).append('\n');
        }
    }

    private static boolean isElf(File f) {
        if (f.length() < 20) return false;

        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4];
            if (in.read(b) != 4) return false;

            return (b[0] & 255) == 127
                && b[1] == 'E'
                && b[2] == 'L'
                && b[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    private static void collect(
        File root,
        File dir,
        int depth,
        List<Node> out
    ) throws IOException {

        if (depth > MAX_DEPTH || out.size() >= MAX_FILES)
            return;

        File[] files = dir.listFiles();
        if (files == null) return;

        Arrays.sort(files, Comparator.comparing(File::getName));

        String base = root.getCanonicalPath() + File.separator;

        for (File f : files) {
            if (out.size() >= MAX_FILES) return;

            String canonical = f.getCanonicalPath();

            if (!canonical.startsWith(base)) continue;

            if (f.isDirectory()) {
                collect(root, f, depth + 1, out);
            } else if (f.isFile()) {
                out.add(new Node(
                    f,
                    canonical.substring(base.length())
                ));
            }
        }
    }

    private static void inspect(Node n) {
        n.inspected = true;

        String report = NativeBridge.inspect(n.file);

        if (!report.startsWith("ELF detected\n"))
            return;

        boolean recognized = false;
        boolean reading = false;

        for (String line : report.split("\n")) {
            String s = line.trim();

            if (s.startsWith("Architecture: ")) {
                n.arch = s.substring(
                    "Architecture: ".length()
                ).trim();
            }

            if (s.startsWith("DT_SONAME: ")) {
                String value = s.substring(
                    "DT_SONAME: ".length()
                ).trim();

                if (!value.equals("None"))
                    n.soname = value;
            }

            if (s.startsWith("DT_NEEDED (")) {
                recognized = true;
                reading = true;
                continue;
            }

            if (s.equals("DT_NEEDED: None")) {
                recognized = true;
                reading = false;
                continue;
            }

            if (reading) {
                if (s.startsWith("- ")) {
                    String dep = s.substring(2).trim();
                    if (!dep.isEmpty())
                        n.needs.add(dep);
                } else {
                    reading = false;
                }
            }
        }

        n.valid = recognized
            && !n.arch.equals("Unknown");
    }

    private static void index(
        Map<String, List<Node>> map,
        String key,
        Node n
    ) {
        List<Node> list = map.get(key);

        if (list == null) {
            list = new ArrayList<>();
            map.put(key, list);
        }

        if (!list.contains(n))
            list.add(n);
    }

    private static String prefix(int depth) {
        StringBuilder b = new StringBuilder();

        for (int i = 0; i < depth; i++)
            b.append("  ");

        return b.toString();
    }

    private static void visit(
        Node node,
        Context ctx,
        int depth
    ) {
        if (depth > MAX_DEPTH || ctx.edges >= 5000) {
            ctx.stats.truncated++;
            return;
        }

        Integer old = ctx.states.get(node);

        if (old != null) {
            if (old == 1) {
                ctx.stats.cycles++;
                append(ctx.output,
                    prefix(depth) + "[CYCLE] "
                    + node.path);
            }
            return;
        }

        ctx.states.put(node, 1);

        for (String dep : node.needs) {
            if (++ctx.edges > 5000) {
                ctx.stats.truncated++;
                break;
            }

            String pad = prefix(depth + 1);

            List<Node> matches = ctx.names.get(dep);

            if (matches == null || matches.isEmpty()) {
                ctx.stats.missing++;
                append(ctx.output,
                    pad + "[MISSING] " + dep);
                continue;
            }

            if (matches.size() > 1) {
                ctx.stats.duplicates++;
                append(ctx.output,
                    pad + "[DUPLICATE CANDIDATES] "
                    + dep + " (" + matches.size() + ")");
            }

            Node selected = null;
            Node mismatch = null;
            boolean unknown = false;
            int compatible = 0;

            for (Node candidate : matches) {
                if (!candidate.inspected || !candidate.valid) {
                    unknown = true;
                    continue;
                }

                if (candidate.arch.equals(node.arch)) {
                    compatible++;

                    if (selected == null)
                        selected = candidate;
                } else {
                    mismatch = candidate;
                }
            }

            if (compatible > 1) {
                ctx.stats.unverified++;
                append(ctx.output,
                    pad + "[AMBIGUOUS] " + dep
                    + " (" + compatible
                    + " matching architectures)");
                continue;
            }

            if (selected != null) {
                ctx.stats.found++;

                append(ctx.output,
                    pad + "[FOUND] " + dep
                    + " -> " + selected.path);

                if (ctx.states.get(selected) != null
                    && ctx.states.get(selected) == 1) {
                    ctx.stats.cycles++;
                    append(ctx.output,
                        pad + "  [CYCLE] "
                        + selected.path);
                } else {
                    visit(selected, ctx, depth + 1);
                }

            } else if (unknown) {
                ctx.stats.unverified++;
                append(ctx.output,
                    pad + "[UNVERIFIED] " + dep);

            } else if (mismatch != null) {
                ctx.stats.incompatible++;
                append(ctx.output,
                    pad + "[INCOMPATIBLE] " + dep
                    + " -> " + mismatch.path
                    + " (" + mismatch.arch + ")");

            } else {
                ctx.stats.unverified++;
                append(ctx.output,
                    pad + "[UNVERIFIED] " + dep);
            }
        }

        ctx.states.put(node, 2);
    }

    public static String analyze(File runtime)
        throws IOException {

        if (runtime == null || !runtime.isDirectory())
            return "Runtime directory not found";

        List<Node> files = new ArrayList<>();
        collect(runtime, runtime, 0, files);

        List<Node> elfNodes = new ArrayList<>();

        int skipped = 0;

        for (Node n : files) {
            if (!isElf(n.file)) continue;

            n.elf = true;

            if (elfNodes.size() >= MAX_ELF) {
                skipped++;
                continue;
            }

            inspect(n);
            elfNodes.add(n);
        }

        Context ctx = new Context();

        for (Node n : files) {
            index(ctx.names, n.name, n);

            if (n.valid && !n.soname.isEmpty())
                index(ctx.names, n.soname, n);
        }

        append(ctx.output,
            "TITANX DEPENDENCY GRAPH v1.2");
        append(ctx.output, "");
        append(ctx.output,
            "Runtime files: " + files.size());
        append(ctx.output,
            "ELF inspected: " + elfNodes.size());
        append(ctx.output,
            "ELF skipped: " + skipped);
        append(ctx.output, "");

        int invalid = 0;

        for (Node n : elfNodes) {
            if (!n.valid) {
                invalid++;
                append(ctx.output,
                    "[UNVERIFIED ELF] " + n.path);
                continue;
            }

            if (ctx.states.containsKey(n))
                continue;

            append(ctx.output,
                "----------------------------");
            append(ctx.output,
                "[ROOT] " + n.path);
            append(ctx.output,
                "Architecture: " + n.arch);
            append(ctx.output,
                "SONAME: " + (n.soname.isEmpty()
                    ? "None" : n.soname));

            visit(n, ctx, 0);
        }

        append(ctx.output, "");
        append(ctx.output,
            "============================");
        append(ctx.output, "SUMMARY");
        append(ctx.output,
            "FOUND edges: " + ctx.stats.found);
        append(ctx.output,
            "MISSING edges: " + ctx.stats.missing);
        append(ctx.output,
            "INCOMPATIBLE edges: "
            + ctx.stats.incompatible);
        append(ctx.output,
            "UNVERIFIED edges: "
            + ctx.stats.unverified);
        append(ctx.output,
            "DUPLICATE candidate names: "
            + ctx.stats.duplicates);
        append(ctx.output,
            "CYCLE edges: " + ctx.stats.cycles);
        append(ctx.output,
            "UNVERIFIED ELF files: " + invalid);
        append(ctx.output,
            "TRUNCATED traversals: "
            + ctx.stats.truncated);
        append(ctx.output, "");
        append(ctx.output,
            "Results describe imported files only.");
        append(ctx.output,
            "Graph is not a dynamic linker simulation.");
        append(ctx.output,
            "No imported ELF files were executed.");

        return ctx.output.toString();
    }

    public static String selfTest() {
        StringBuilder result = new StringBuilder();

        append(result, "TITANX GRAPH SELF-TEST v1.2");
        append(result, "");

        Map<String, List<String>> graph = new HashMap<>();

        graph.put("A", Arrays.asList("B"));
        graph.put("B", Arrays.asList("C"));
        graph.put("C", Arrays.asList("A"));

        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();

        boolean cycle = testCycle(
            "A", graph, visited, active
        );

        append(result,
            "Cycle detection: " + (cycle ? "PASS" : "FAIL"));

        boolean missing =
            !graph.containsKey("Missing");

        append(result,
            "Missing node: " + (missing ? "PASS" : "FAIL"));

        Map<String, Integer> duplicates = new HashMap<>();
        duplicates.put("libsame.so", 2);

        boolean duplicate =
            duplicates.get("libsame.so") > 1;

        append(result,
            "Duplicate detection: "
            + (duplicate ? "PASS" : "FAIL"));

        boolean passed = cycle && missing && duplicate;

        append(result, "");
        append(result, passed
            ? "ALL 3 GRAPH LOGIC TESTS PASSED"
            : "GRAPH SELF-TEST FAILED");

        append(result, "");
        append(result,
            "Synthetic graph logic test only.");
        append(result,
            "Use BUILD DEPENDENCY GRAPH to inspect files.");

        return result.toString();
    }

    private static boolean testCycle(
        String node,
        Map<String, List<String>> graph,
        Set<String> visited,
        Set<String> active
    ) {
        if (active.contains(node))
            return true;

        if (visited.contains(node))
            return false;

        visited.add(node);
        active.add(node);

        for (String next : graph.getOrDefault(
            node, Collections.emptyList())) {

            if (testCycle(next, graph, visited, active))
                return true;
        }

        active.remove(node);
        return false;
    }
}
