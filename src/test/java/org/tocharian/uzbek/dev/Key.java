package org.tocharian.uzbek.dev;

import org.tocharian.uzbek.script.UzNormalizer;

/** Print the Layer 1 search key for each argument. The query side of the demo. */
public final class Key {
    public static void main(String[] args) {
        for (String a : args) System.out.println(UzNormalizer.normalize(a).searchKey());
    }
}
