package com.aye.server.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.server.web.ApiException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * read_local_file —— 唯一的本机文件读取工具（设计第 3 节）。
 *
 * <p>行为：
 * <ul>
 *   <li>READ 类，只读免确认；</li>
 *   <li>目标路径必须在本会话 {@link LocalAttachmentRegistry} 授权范围内；</li>
 *   <li>文本文件：返回内容，上限 1MB；二进制文件拒绝；</li>
 *   <li>目录：返回受限清单（递归、跳过 node_modules/.git、上限 2000 项）；</li>
 *   <li>图片：返回格式与尺寸等文本元信息（见类末「图像能力说明」）。</li>
 * </ul>
 */
public final class LocalFileReadTool implements Tool {

    /** 文本读取上限 1MB。 */
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    /** 目录清单条目上限。 */
    private static final int MAX_LIST_ENTRIES = 2000;
    /** 目录递归时整体跳过的目录名（设计点名 node_modules / .git）。 */
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", ".git");
    private static final Set<String> IMAGE_EXTS =
            Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp");

    private final LocalAttachmentRegistry registry;

    public LocalFileReadTool(LocalAttachmentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() { return "read_local_file"; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public String description() {
        return "读取【用户本机】上已作为附件授权的文件（桌面端后端与用户同机）。"
                + "传入路径必须是用户本轮附带的文件或其目录之内的路径，未授权路径一律拒绝。"
                + "传目录路径返回递归文件清单（跳过 node_modules/.git，最多 2000 项）。"
                + "文本文件最多返回 1MB；需要具体内容时再按清单精确读取，不要一次遍历整个项目。"
                + "图片仅返回尺寸/格式元信息，无法直接看图。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object()
                .property("path", JsonSchema.string()
                        .description("本机文件或目录的绝对路径（来自用户附件清单）"))
                .required("path")
                .build();
    }

    @Override
    public long timeoutMs() { return 60_000L; }

    @Override
    public String promptSnippet() {
        return "read_local_file — 在附件白名单内读取用户本机文件/列目录（READ 免确认，文本上限 1MB，目录清单上限 2000 项）。";
    }

    @Override
    public List<String> resolveAffectedPaths(JsonNode input, ToolContext ctx) {
        String path = input.path("path").asText("");
        return path.isBlank() ? List.of() : List.of(path);
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) {
        String sessionId = ctx.sessionId();
        String raw = input.path("path").asText("");
        if (sessionId == null || sessionId.isBlank()) {
            throw new ApiException(400, "缺少会话标识", "NO_SESSION");
        }
        final Path path;
        try {
            path = Paths.get(raw).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new ApiException(400, "路径无效：" + raw, "BAD_PATH");
        }

        if (!registry.isAllowed(sessionId, path.toString())) {
            throw new ApiException(403,
                    "路径不在本轮附件授权范围内（文件精确匹配 / 目录前缀匹配）：" + path,
                    "LOCAL_PATH_NOT_ALLOWED");
        }
        if (!Files.exists(path)) {
            throw new ApiException(404, "文件不可用（已被移动或删除）：" + path, "LOCAL_FILE_GONE");
        }
        try {
            if (Files.isDirectory(path)) {
                return listDirectory(path);
            }
            long size = Files.size(path);
            String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (IMAGE_EXTS.contains(extOf(fileName))) {
                return describeImage(path, size);
            }
            if (size > MAX_TEXT_BYTES) {
                throw new ApiException(413,
                        "文件超过 1MB 文本上限（" + size + " 字节），请让用户指定需要的片段或改用文件面板下载",
                        "LOCAL_FILE_TOO_LARGE");
            }
            byte[] bytes = Files.readAllBytes(path);
            if (looksBinary(bytes)) {
                throw new ApiException(415, "二进制文件无法以文本读取：" + path, "LOCAL_BINARY");
            }
            return Map.of(
                    "path", path.toString(),
                    "size_bytes", size,
                    "content", new String(bytes, StandardCharsets.UTF_8));
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(500, "读取本地文件失败：" + e.getMessage(), "LOCAL_READ_FAILED");
        }
    }

    // ── 目录清单：手工递归以便在 node_modules/.git 处剪枝 ──

    private Map<String, Object> listDirectory(Path root) throws IOException {
        List<String> entries = new ArrayList<>();
        boolean[] truncated = {false};
        collect(root, root, entries, truncated);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("directory", root.toString());
        out.put("entry_count", entries.size());
        out.put("skipped_dirs", List.copyOf(SKIP_DIRS));
        out.put("truncated", truncated[0]);
        out.put("note", truncated[0] ? "条目超过 2000 已截断，请给出更具体的子目录" : "");
        out.put("entries", entries);
        return out;
    }

    private void collect(Path dir, Path root, List<String> out, boolean[] truncated) throws IOException {
        if (truncated[0]) return;
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.forEach(children::add);
        }
        children.sort(Comparator.comparing(p ->
                p.getFileName().toString().toLowerCase(Locale.ROOT)));
        for (Path p : children) {
            String name = p.getFileName().toString();
            // 不跟随符号链接目录递归，避免经授权树内链接遍历到授权范围之外
            boolean isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
            if (isDir && SKIP_DIRS.contains(name)) continue; // 剪枝：整棵跳过
            if (out.size() >= MAX_LIST_ENTRIES) {
                truncated[0] = true;
                return;
            }
            String rel = root.relativize(p).toString().replace('\\', '/');
            out.add(rel + (isDir ? "/" : ""));
            if (isDir) collect(p, root, out, truncated);
            if (truncated[0]) return;
        }
    }

    // ── 图片：返回尺寸/格式（图像能力降级，见类注释末） ──

    private Map<String, Object> describeImage(Path path, long size) {
        Integer width = null;
        Integer height = null;
        String format = null;
        try (ImageInputStream iis = ImageIO.createImageInputStream(path.toFile())) {
            if (iis != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(iis, true);
                        width = reader.getWidth(0);
                        height = reader.getHeight(0);
                        format = reader.getFormatName();
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (IOException ignored) {
            // 无法解码时仅返回基础信息
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path.toString());
        out.put("size_bytes", size);
        out.put("image_format", format);
        out.put("width", width);
        out.put("height", height);
        out.put("note", "当前工具结果通道仅支持文本，无法把图片像素送入模型；以上为可读取的全部图像元信息。");
        return out;
    }

    private static String extOf(String lowerFileName) {
        int i = lowerFileName.lastIndexOf('.');
        return i < 0 ? "" : lowerFileName.substring(i);
    }

    private static boolean looksBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 1024);
        int suspicious = 0;
        for (int i = 0; i < n; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) return true;
            if (b < 7 || (b > 13 && b < 32)) suspicious++;
        }
        return suspicious > n / 10;
    }
}
