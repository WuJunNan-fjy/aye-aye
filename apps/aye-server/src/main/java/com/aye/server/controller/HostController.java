package com.aye.server.controller;

import com.aye.server.storage.HostStore;
import com.aye.server.web.ApiException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主机配置接口（对应前端 {@code hostApi.ts}）。翻译自旧 NestJS {@code host.controller.ts}。
 */
@RestController
@RequestMapping("/api/hosts")
public class HostController {

    private final HostStore store;

    public HostController(HostStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return store.findAll();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Map<String, Object> host = store.findOne(id);
        if (host == null) throw new ApiException(404, "主机不存在：" + id, "HOST_NOT_FOUND");
        return host;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> host) {
        return store.create(host);
    }

    @PutMapping("/reorder")
    public Map<String, Object> reorder(@RequestBody Map<String, Object> body) {
        Object ids = body.get("orderedIds");
        if (!(ids instanceof List<?> list)) {
            throw new ApiException(400, "orderedIds 缺失", "BAD_REQUEST");
        }
        List<Long> ordered = new ArrayList<>();
        for (Object o : list) {
            ordered.add(((Number) o).longValue());
        }
        store.reorder(ordered);
        return Map.of("success", true);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> host) {
        Map<String, Object> updated = store.update(id, host);
        if (updated == null) throw new ApiException(404, "主机不存在：" + id, "HOST_NOT_FOUND");
        return updated;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable long id) {
        store.remove(id);
        return Map.of("success", true);
    }
}
