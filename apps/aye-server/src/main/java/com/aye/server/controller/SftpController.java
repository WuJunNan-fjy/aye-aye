package com.aye.server.controller;

import com.aye.server.ssh.SftpService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * SFTP 文件接口（对应前端 {@code sftpApi.ts}）。翻译自旧 NestJS {@code sftp.service.ts}。
 */
@RestController
@RequestMapping("/api/sftp")
public class SftpController {

    private final SftpService sftp;

    public SftpController(SftpService sftp) {
        this.sftp = sftp;
    }

    @GetMapping("/list/{sessionId}")
    public List<Map<String, Object>> list(@PathVariable String sessionId, @RequestParam("path") String path)
            throws Exception {
        return sftp.list(sessionId, path);
    }

    @GetMapping("/read/{sessionId}")
    public Map<String, Object> read(@PathVariable String sessionId, @RequestParam("path") String path,
                                    @RequestParam(value = "maxBytes", required = false) Integer maxBytes)
            throws Exception {
        String content = sftp.readText(sessionId, path, maxBytes == null ? 65536 : maxBytes);
        return Map.of("content", content);
    }

    @PostMapping("/write/{sessionId}")
    public Map<String, Object> write(@PathVariable String sessionId, @RequestParam("path") String path,
                                     @RequestBody Map<String, Object> body) throws Exception {
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content"));
        sftp.writeText(sessionId, path, content);
        return Map.of("status", "ok");
    }

    @GetMapping("/download/{sessionId}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String sessionId,
                                                          @RequestParam("path") String path) {
        String filename = path.substring(path.lastIndexOf('/') + 1);
        // RFC 5987：中文/空格文件名用 filename*=utf-8'' 传递
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        // writeRemoteTo 声明 throws Exception，而 StreamingResponseBody 只允许 IOException：
        // RuntimeException（如 ApiException）原样抛出以保留状态码，其余受检异常包装为 IOException
        StreamingResponseBody body = out -> {
            try {
                sftp.writeRemoteTo(sessionId, path, out);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=utf-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    @PostMapping("/upload/{sessionId}")
    public Map<String, Object> upload(@PathVariable String sessionId, @RequestParam("path") String path,
                                      @RequestParam("file") MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        sftp.upload(sessionId, path, file.getBytes(), filename);
        return Map.of("status", "ok");
    }

    @DeleteMapping("/remove/{sessionId}")
    public Map<String, Object> remove(@PathVariable String sessionId, @RequestParam("path") String path,
                                      @RequestParam(value = "directory", required = false) String directory)
            throws Exception {
        sftp.remove(sessionId, path, "true".equals(directory));
        return Map.of("status", "ok");
    }

    @PostMapping("/mkdir/{sessionId}")
    public Map<String, Object> mkdir(@PathVariable String sessionId, @RequestParam("path") String path)
            throws Exception {
        sftp.mkdir(sessionId, path);
        return Map.of("status", "ok");
    }

    @PostMapping("/rename/{sessionId}")
    public Map<String, Object> rename(@PathVariable String sessionId,
                                      @RequestParam("oldPath") String oldPath,
                                      @RequestParam("newPath") String newPath) throws Exception {
        sftp.rename(sessionId, oldPath, newPath);
        return Map.of("status", "ok");
    }
}
