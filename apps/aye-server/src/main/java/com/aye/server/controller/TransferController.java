package com.aye.server.controller;

import com.aye.server.transfer.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 分片上传端点。chunk 走 application/octet-stream 直读，绕过 multipart 200MB 限制。
 */
@RestController
@RequestMapping("/api/sftp/transfer")
public class TransferController {

    private final TransferService transfer;

    public TransferController(TransferService transfer) {
        this.transfer = transfer;
    }

    @PostMapping("/init")
    public TransferService.InitResponse init(@RequestBody TransferService.InitRequest req)
            throws Exception {
        return transfer.init(req);
    }

    @PostMapping(value = "/chunk/{uploadId}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Map<String, Object> chunk(@PathVariable String uploadId,
                                     @RequestParam("index") int index,
                                     HttpServletRequest request) throws Exception {
        long len = request.getContentLengthLong();
        transfer.receiveChunk(uploadId, index, request.getInputStream(), len);
        return Map.of("received", true, "index", index);
    }

    @PostMapping("/complete/{uploadId}")
    public Map<String, Object> complete(@PathVariable String uploadId) throws Exception {
        transfer.complete(uploadId);
        return Map.of("status", "ok");
    }

    @PostMapping("/abort/{uploadId}")
    public Map<String, Object> abort(@PathVariable String uploadId) throws Exception {
        transfer.abort(uploadId);
        return Map.of("status", "ok");
    }

    @GetMapping("/status/{uploadId}")
    public Map<String, Object> status(@PathVariable String uploadId) throws Exception {
        return transfer.status(uploadId);
    }
}
