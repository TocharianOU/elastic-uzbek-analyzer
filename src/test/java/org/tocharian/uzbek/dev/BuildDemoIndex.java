package org.tocharian.uzbek.dev;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turn {@code demo/products.tsv} into an Elasticsearch bulk body.
 *
 * <p>Titles go in exactly as written. Nothing is pre-computed here — the plugin
 * does the analysis at index time, which is the only way the demo shows what the
 * plugin actually does rather than what this file remembers about it.
 */
public final class BuildDemoIndex {

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args.length > 0 ? args[0] : "demo/products.tsv");
        Path out = Path.of(args.length > 1 ? args[1] : "demo/bulk-plain.ndjson");

        List<String> lines = new ArrayList<>();
        for (String row : Files.readAllLines(in, StandardCharsets.UTF_8)) {
            if (row.isBlank()) continue;
            String[] f = row.split("\t");
            if (f.length < 2) continue;
            lines.add("{\"index\":{\"_id\":\"" + f[0].trim() + "\"}}");
            lines.add("{\"title\":" + json(f[1])
                    + ",\"note\":" + json(f.length > 2 ? f[2] : "") + "}");
        }
        lines.add("");
        Files.writeString(out, String.join("\n", lines), StandardCharsets.UTF_8);
        System.out.println("wrote " + out + "  (" + (lines.size() - 1) / 2 + " docs)");
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }
}
