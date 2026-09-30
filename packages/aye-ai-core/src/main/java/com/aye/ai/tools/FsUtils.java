package com.aye.ai.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

/**
 * 文件工具共用函数（对应 DeepWork 的 {@code tools/utils.ts}）。
 *
 * <p>路径解析、二进制检测、原子写入、行号格式化、行尾/引号归一化。
 */
public final class FsUtils {

    private FsUtils() {}

    // ---------------------------------------------------------------------------
    // 二进制文件扩展名黑名单
    // ---------------------------------------------------------------------------
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".zip", ".gz", ".tar", ".rar", ".7z",
            ".exe", ".dll", ".so", ".dylib", ".wasm",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico",
            ".pdf", ".mp3", ".mp4", ".avi", ".mov", ".mkv",
            ".pyc", ".class", ".jar",
            ".sqlite", ".db");

    // ---------------------------------------------------------------------------
    // 路径解析
    // ---------------------------------------------------------------------------
    /** 解析为绝对路径并限制在 cwd 内（防路径穿越） */
    public static String resolvePath(String input, String cwd) {
        Path cwdPath = Paths.get(cwd).toAbsolutePath().normalize();
        Path p = Paths.get(input);
        Path abs = (p.isAbsolute() ? p : cwdPath.resolve(p)).normalize().toAbsolutePath();
        if (!abs.equals(cwdPath) && !abs.startsWith(cwdPath)) {
            throw new IllegalArgumentException("路径越界: " + abs + "（仅允许访问 " + cwdPath + " 内的文件）");
        }
        return abs.toString();
    }

    /** 绝对路径 → 相对 cwd 的路径（省 token；越界时回退绝对路径） */
    public static String toRelativePath(String absPath, String cwd) {
        try {
            Path abs = Paths.get(absPath).toAbsolutePath().normalize();
            Path c = Paths.get(cwd).toAbsolutePath().normalize();
            String rel = c.relativize(abs).toString();
            return rel.startsWith("..") ? absPath : rel;
        } catch (Exception e) {
            return absPath;
        }
    }

    // ---------------------------------------------------------------------------
    // 二进制检测
    // ---------------------------------------------------------------------------
    public static boolean isBinaryFile(String filePath, byte[] buffer) {
        String lower = filePath.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && BINARY_EXTENSIONS.contains(lower.substring(dot))) return true;

        int n = Math.min(buffer.length, 8192);
        if (n == 0) return false;
        int nonPrintable = 0;
        for (int i = 0; i < n; i++) {
            int b = buffer[i] & 0xFF;
            if (b == 0) return true;
            if (b == 9 || b == 10 || b == 13) continue;
            if (b >= 32 && b <= 126) continue;
            if (b >= 128) continue; // UTF-8 多字节
            nonPrintable++;
        }
        return (double) nonPrintable / n > 0.3;
    }

    // ---------------------------------------------------------------------------
    // 行尾 / 引号归一化
    // ---------------------------------------------------------------------------
    public static String detectLineEnding(String content) {
        int crlf = content.indexOf("\r\n");
        int lf = content.indexOf('\n');
        if (crlf != -1 && (lf == -1 || crlf < lf)) return "CRLF";
        return "LF";
    }

    public static String convertLineEnding(String text, String ending) {
        String lf = text.replace("\r\n", "\n");
        return "CRLF".equals(ending) ? lf.replace("\n", "\r\n") : lf;
    }

    public static String normalizeQuotes(String text) {
        return text
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201C', '"')
                .replace('\u201D', '"');
    }

    // ---------------------------------------------------------------------------
    // 原子写入
    // ---------------------------------------------------------------------------
    public static void atomicWrite(String filePath, String content) throws IOException {
        Path target = Paths.get(filePath);
        Path dir = target.getParent();
        if (dir != null) Files.createDirectories(dir);

        Path tmp = Paths.get(filePath + ".tmp." + ProcessHandle.current().pid() + "." + System.nanoTime());
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---------------------------------------------------------------------------
    // 行号格式化
    // ---------------------------------------------------------------------------
    public static String addLineNumbers(List<String> lines, int startLine) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String num = String.format("%6d", startLine + i);
            sb.append(num).append('→').append(lines.get(i));
            if (i < lines.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }
}
