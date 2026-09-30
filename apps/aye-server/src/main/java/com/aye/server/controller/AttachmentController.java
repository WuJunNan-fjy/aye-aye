package com.aye.server.controller;

import com.aye.server.config.AppProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天附件上传（对应前端 agentApi.uploadAttachment）。
 *
 * <p>POST /api/agent/upload —— 把用户拖入的本地文件落盘到 {@code {dataDir}/uploads/}，
 * 返回带服务端绝对路径的元数据；前端在随后的 /chat/stream 请求里带上 attachments，
 * 由 {@code ChatService} 把文本内容注入用户消息。
 */
@RestController
@RequestMapping("/api/agent")
public class AttachmentController {

    private static final long MAX_BYTES = 200L * 1024 * 1024; // 200MB 上限

    private final AppProperties props;

    public AttachmentController(AppProperties props) {
        this.props = props;
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file 不能为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("文件超过 200MB 上限");
        }

        Path dir = Path.of(props.getDataDir()).resolve("uploads").toAbsolutePath().normalize();
        Files.createDirectories(dir);

        String id = UUID.randomUUID().toString().replace("-", "");
        String safeName = file.getOriginalFilename() == null
                ? "attachment" : file.getOriginalFilename().replaceAll("[\\\\/:*?\"<>|]", "_");
        Path target = dir.resolve(id + "_" + safeName).normalize();
        // 防目录穿越：确保仍在 uploads 目录下
        if (!target.getParent().equals(dir)) {
            target = dir.resolve(id + "_attachment");
        }
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", id);
        meta.put("name", safeName);
        meta.put("size", file.getSize());
        meta.put("mimetype", file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        meta.put("path", target.toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("file", meta);
        return body;
    }
}
