package org.tocharian.uzbek.dev;

import org.tocharian.uzbek.protect.ProtectionMarker;
import org.tocharian.uzbek.protect.ProtectionVerdict;
import org.tocharian.uzbek.script.NormalizedForm;
import org.tocharian.uzbek.script.ScriptDetector;
import org.tocharian.uzbek.script.UzNormalizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turn the demo product list into an Elasticsearch bulk body.
 *
 * <p>The plugin itself does not build yet (Layers 2 and 4 are missing), so the
 * Layer 1 search key is computed here and indexed as its own field. That is
 * exactly what the char_filter will emit once it exists, so the demo measures
 * the real behaviour rather than a mock-up.
 */
public final class BuildDemoIndex {

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args.length > 0 ? args[0] : "demo/products.tsv");
        Path out = Path.of(args.length > 1 ? args[1] : "demo/bulk.ndjson");

        ProtectionMarker pm = ProtectionMarker.get();
        List<String> lines = new ArrayList<>();

        for (String row : Files.readAllLines(in, StandardCharsets.UTF_8)) {
            if (row.isBlank()) continue;
            String[] f = row.split("\t");
            String id = f[0], title = f[1], note = f.length > 2 ? f[2] : "";

            NormalizedForm nf = UzNormalizer.normalize(title);

            // Per-token protection, so the demo shows what Layer 4 would skip.
            StringBuilder prot = new StringBuilder();
            for (String tok : title.split("\\s+")) {
                ProtectionVerdict v = pm.check(tok);
                if (v.isProtected()) {
                    if (prot.length() > 0) prot.append(' ');
                    prot.append(tok).append(':').append(v.reason());
                }
            }

            lines.add("{\"index\":{\"_id\":\"" + id + "\"}}");
            lines.add("{"
                + "\"title\":" + json(title) + ","
                + "\"title_uz\":" + json(nf.searchKey()) + ","
                + "\"internal\":" + json(nf.internal()) + ","
                + "\"script\":" + json(ScriptDetector.detect(title).script().name()) + ","
                + "\"protected\":" + json(prot.toString()) + ","
                + "\"note\":" + json(note)
                + "}");
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
