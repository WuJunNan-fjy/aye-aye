package com.aye.server.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级本地路径白名单：每轮对话只显式授权用户附带的路径。
 *
 * <p>文件按精确路径授权；目录按「目录前缀之内」授权（{@link Path#startsWith}）。
 * 授权随 agent 运行结束清除（fail-closed）：历史会话重放不会自动重新授权，
 * 用户在新一轮再次附带文件时重新注册。线程安全，供 worker 线程与工具线程并发访问。
 */
public final class LocalAttachmentRegistry {

    private record Scope(Set<Path> files, Set<Path> dirs) {
        Scope() {
            this(ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet());
        }
    }

    private final ConcurrentHashMap<String, Scope> scopes = new ConcurrentHashMap<>();

    /** 注册单个文件（精确匹配）。路径非法时静默忽略。 */
    public void registerFile(String sessionId, String rawPath) {
        normalize(rawPath).ifPresent(scope(sessionId).files()::add);
    }

    /** 注册目录前缀：该目录及其子树均可读。 */
    public void registerDirectory(String sessionId, String rawPath) {
        normalize(rawPath).ifPresent(scope(sessionId).dirs()::add);
    }

    /**
     * 判断目标路径是否在该会话授权范围内。
     *
     * <p>授权根与目标均经 {@link Path#toRealPath} 解析后再比较，防止授权目录内的
     * 符号链接把访问重定向到目录之外（如 {@code project/link -> /etc}）。
     * 真实路径无法解析时 fail-closed（拒绝）。
     */
    public boolean isAllowed(String sessionId, String rawPath) {
        Scope s = scopes.get(sessionId);
        if (s == null) return false;
        if (normalize(rawPath).isEmpty()) return false;
        Path realTarget = canonical(rawPath);
        if (realTarget == null) return false;
        for (Path f : s.files()) {
            Path realFile = canonical(f.toString());
            if (realFile != null && realTarget.equals(realFile)) return true;
        }
        for (Path dir : s.dirs()) {
            Path realDir = canonical(dir.toString());
            if (realDir != null && realTarget.startsWith(realDir)) return true;
        }
        return false;
    }

    /**
     * 消解符号链接后的真实路径；路径尚不存在（新建场景）时退化为绝对规范路径。
     * 解析失败（IO 错误等）返回 null，调用方按拒绝处理。
     */
    private static Path canonical(String rawPath) {
        Optional<Path> normalized = normalize(rawPath);
        if (normalized.isEmpty()) return null;
        Path p = normalized.get();
        try {
            return Files.exists(p) ? p.toRealPath() : p;
        } catch (IOException e) {
            return null;
        }
    }

    /** 清除该会话全部授权（agent 运行结束时调用）。 */
    public void clear(String sessionId) {
        if (sessionId != null) scopes.remove(sessionId);
    }

    private Scope scope(String sessionId) {
        return scopes.computeIfAbsent(sessionId, k -> new Scope());
    }

    private static Optional<Path> normalize(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return Optional.empty();
        try {
            return Optional.of(Path.of(rawPath).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
