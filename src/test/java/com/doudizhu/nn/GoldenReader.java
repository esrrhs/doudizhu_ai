package com.doudizhu.nn;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * 读取 PerfectDouCPP tools/pddump.cpp 导出的 golden 记录 (gzip 文本)。
 *
 * <p>记录是行式的: 每个数值块以一行 "{@code <KEY> <个数>} 表头" 开头, 其后每行最多 20 个值,
 * 所以按 token 逐个读取最稳妥。牌面用训练侧写法: 3..9 T J Q K A 2 B(小王) R(大王), 过牌为 "-"。
 */
final class GoldenReader implements AutoCloseable {

    private static final char[] CHARS =
            {'3', '4', '5', '6', '7', '8', '9', 'T', 'J', 'Q', 'K', 'A', '2', 'B', 'R'};

    private final BufferedReader in;
    private String[] tokens = new String[0];
    private int index;

    static GoldenReader open(String classpathResource) {
        InputStream raw = GoldenReader.class.getResourceAsStream(classpathResource);
        if (raw == null) {
            throw new IllegalStateException("找不到 golden 资源 " + classpathResource);
        }
        try {
            return new GoldenReader(new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private GoldenReader(BufferedReader in) {
        this.in = in;
    }

    boolean hasNext() {
        return index < tokens.length || readLine() != null;
    }

    String nextToken() {
        while (index >= tokens.length) {
            if (readLine() == null) {
                throw new IllegalStateException("golden 文件提前结束");
            }
        }
        return tokens[index++];
    }

    /** 读入下一非空行并填满缓冲区; 文件结束返回 null */
    private String readLine() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) {
                    tokens = line.trim().split("\\s+");
                    index = 0;
                    return line;
                }
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 丢弃当前行剩余内容 */
    void skipLine() {
        tokens = new String[0];
        index = 0;
    }

    float[] readFloats(int n) {
        float[] out = new float[n];
        for (int i = 0; i < n; ++i) {
            out[i] = Float.parseFloat(nextToken());
        }
        return out;
    }

    /** 牌面字符串 -> 15 维张数 */
    static int[] parseCounts(String cards) {
        int[] c = new int[PerfectDouFeatures.RANKS];
        if ("-".equals(cards)) {
            return c;
        }
        for (char ch : cards.toCharArray()) {
            c[indexOf(ch)]++;
        }
        return c;
    }

    /** 15 维张数 -> 牌面字符串 */
    static String cardsOf(int[] counts) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < counts.length; ++r) {
            for (int k = 0; k < counts[r]; ++k) {
                sb.append(CHARS[r]);
            }
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    private static int indexOf(char ch) {
        for (int r = 0; r < CHARS.length; ++r) {
            if (CHARS[r] == ch) {
                return r;
            }
        }
        throw new IllegalArgumentException("无法识别的点数字符 " + ch);
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
