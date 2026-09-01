package obf.util;

import java.util.*;

public class NameGen {
    private static final char[] ALPHABET = {'C', 'O', 'H', 'R', 'I'};
    private final Random rng;
    private final int minLen;
    private final int maxLen;
    private final Set<String> used = new HashSet<>();

    public NameGen(long seed, int minLen, int maxLen) {
        this.rng = new Random(seed);
        this.minLen = minLen;
        this.maxLen = maxLen;
    }

    public NameGen(int minLen, int maxLen) {
        this(System.nanoTime(), minLen, maxLen);
    }

    public String next() {
        for (int attempt = 0; attempt < 10000; attempt++) {
            int len = minLen + rng.nextInt(maxLen - minLen + 1);
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]);
            }
            String name = sb.toString();
            if (used.add(name)) return name;
        }
        throw new RuntimeException("NameGen exhausted");
    }

    public String nextPackage(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (i > 0) sb.append('/');
            sb.append(next());
        }
        return sb.toString();
    }

    public String nextInnerStyle() {
        String outer = next();
        return outer + "$" + next();
    }
}
