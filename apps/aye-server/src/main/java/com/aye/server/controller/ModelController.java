package com.aye.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aye.server.config.ModelVendorProfiles;
import com.aye.server.storage.ModelProviderStore;
import com.aye.server.storage.ModelProviderStore.CatalogModel;
import com.aye.server.storage.ModelProviderStore.DefaultRef;
import com.aye.server.storage.ModelProviderStore.Provider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置接口（provider 中心制，对应前端 {@code modelApi.ts}）。
 *
 * <p>设计对标 deepseek-harness：
 * <ul>
 *   <li>一个厂商一条配置（key + baseUrl + 模型目录），不再按模型实例建记录</li>
 *   <li>key 单独存放、write-only——接口只回 {@code hasKey} 布尔，永不回传明文</li>
 *   <li>{@code discover} 端点探测免费拉取模型列表，代替「发一条 ping」的付费测试</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelProviderStore store;
    private final ObjectMapper objectMapper;

    public ModelController(ModelProviderStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /** 更新厂商配置输入（displayName/baseUrl/enabled 传 null 表示保持不变） */
    public record ProviderInput(String displayName, String baseUrl, Boolean enabled) {}

    /** 设置 key 输入（空串 = 清除） */
    public record KeyInput(String apiKey) {}

    /** 目录添加模型输入（contextWindow/maxTokens/thinking 为 v2 高级字段，可空） */
    public record CatalogInput(String modelId, String name, Integer contextWindow, Integer maxTokens, Boolean thinking) {}

    /** 设置默认模型输入 */
    public record DefaultInput(String providerId, String modelId) {}

    /** 端点探测输入（拉取 OpenAI 兼容 GET {baseUrl}/models 模型列表） */
    public record DiscoverInput(String provider, String baseUrl, String apiKey) {}

    /** 厂商配置视图（合并厂商 Profile 展示字段，key 脱敏为 hasKey） */
    private Map<String, Object> toView(Provider p) {
        ModelVendorProfiles.Vendor v = ModelVendorProfiles.get(p.id());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("name", v == null ? p.id() : v.name());
        m.put("icon", v == null ? "🔧" : v.icon());
        m.put("iconUrl", v == null ? "" : v.iconUrl());
        m.put("defaultBaseUrl", v == null ? "" : v.defaultBaseUrl());
        m.put("sdkProvider", v == null ? "openai" : v.sdkProvider());
        m.put("docsUrl", v == null ? null : v.docsUrl());
        m.put("popularModels", v == null ? List.of() : v.popularModels());
        m.put("baseUrl", p.baseUrl() == null ? "" : p.baseUrl());
        m.put("displayName", p.displayName() == null ? "" : p.displayName());
        m.put("enabled", p.enabled());
        m.put("hasKey", p.apiKey() != null && !p.apiKey().isEmpty());
        List<Map<String, Object>> models = new ArrayList<>();
        for (CatalogModel cm : p.models()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("modelId", cm.modelId());
            mm.put("name", cm.name() == null ? "" : cm.name());
            if (cm.contextWindow() != null) mm.put("contextWindow", cm.contextWindow());
            if (cm.maxTokens() != null) mm.put("maxTokens", cm.maxTokens());
            if (cm.thinking() != null) mm.put("thinking", cm.thinking());
            models.add(mm);
        }
        m.put("models", models);
        return m;
    }

    // ── 厂商配置 ──

    @GetMapping("/providers")
    public Map<String, Object> providers() {
        List<Map<String, Object>> out = store.listProviders().stream().map(this::toView).toList();
        return Map.of("providers", out);
    }

    @GetMapping("/vendors")
    public Map<String, Object> vendors() {
        return Map.of("vendors", ModelVendorProfiles.all());
    }

    /** 新建/更新厂商配置（首次保存 baseUrl 缺省用厂商默认地址；custom 厂商必须手动传 baseUrl） */
    @PutMapping("/providers/{id}")
    public ResponseEntity<Map<String, Object>> saveProvider(@PathVariable String id, @RequestBody ProviderInput input) {
        if (!ModelVendorProfiles.isValid(id)) {
            return badRequest("不支持的厂商: " + id);
        }
        String baseUrl = input.baseUrl();
        boolean isFirstCreate = store.get(id) == null;
        if (baseUrl == null && isFirstCreate) {
            ModelVendorProfiles.Vendor v = ModelVendorProfiles.get(id);
            baseUrl = v.defaultBaseUrl();
        }
        // custom 厂商没有默认地址，首次保存必须由用户显式传 baseUrl
        if ("custom".equals(id) && isFirstCreate && (baseUrl == null || baseUrl.isBlank())) {
            return badRequest("自定义厂商必须填写请求地址（Base URL）");
        }
        if (baseUrl != null && !baseUrl.isBlank() && !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return badRequest("Base URL 必须以 http:// 或 https:// 开头");
        }
        Provider p = store.upsertProvider(id, input.displayName(), baseUrl, input.enabled());
        return ResponseEntity.ok(Map.of("success", true, "provider", toView(p)));
    }

    @DeleteMapping("/providers/{id}")
    public Map<String, Object> deleteProvider(@PathVariable String id) {
        return Map.of("success", store.deleteProvider(id));
    }

    /** 设置/更换 key（write-only；空串 = 清除） */
    @PutMapping("/providers/{id}/key")
    public ResponseEntity<Map<String, Object>> setKey(@PathVariable String id, @RequestBody KeyInput input) {
        if (store.get(id) == null) {
            return badRequest("厂商配置不存在: " + id);
        }
        store.setKey(id, input.apiKey() == null ? "" : input.apiKey());
        return ResponseEntity.ok(Map.of("success", true, "hasKey", !store.apiKey(id).isEmpty()));
    }

    // ── 模型目录 ──

    @PostMapping("/providers/{id}/models")
    public ResponseEntity<Map<String, Object>> addModel(@PathVariable String id, @RequestBody CatalogInput input) {
        Provider p = store.get(id);
        if (p == null) return badRequest("厂商配置不存在: " + id);
        if (input.modelId() == null || input.modelId().isBlank()) {
            return badRequest("模型 ID 不能为空");
        }
        store.addModel(id, input.modelId().trim(), input.name() == null ? "" : input.name(),
                input.contextWindow(), input.maxTokens(), input.thinking());
        return ResponseEntity.ok(Map.of("success", true, "provider", toView(store.get(id))));
    }

    @DeleteMapping("/providers/{id}/models/{modelId}")
    public ResponseEntity<Map<String, Object>> removeModel(@PathVariable String id, @PathVariable String modelId) {
        if (store.get(id) == null) return badRequest("厂商配置不存在: " + id);
        store.removeModel(id, modelId);
        return ResponseEntity.ok(Map.of("success", true, "provider", toView(store.get(id))));
    }

    // ── 默认模型 ──

    @GetMapping("/default")
    public Map<String, Object> defaultModel() {
        DefaultRef d = store.getDefaultRef();
        return Map.of("default", d == null ? null : Map.of("providerId", d.providerId(), "modelId", d.modelId()));
    }

    @PutMapping("/default")
    public ResponseEntity<Map<String, Object>> setDefault(@RequestBody DefaultInput input) {
        if (input.providerId() == null || input.modelId() == null
                || !store.hasModel(input.providerId(), input.modelId())) {
            return badRequest("模型不存在，请先添加到厂商目录");
        }
        store.setDefault(input.providerId(), input.modelId());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── 端点探测 ──

    /**
     * 端点探测：GET {baseUrl}/models 拉取该端点可用的模型 id 列表（OpenAI 兼容格式），
     * 免费且不消耗 token，代替「发一条 ping」的付费测试。
     * baseUrl 缺省用厂商默认地址；apiKey 缺省复用该厂商已保存的 key。
     */
    @PostMapping("/discover")
    public Map<String, Object> discover(@RequestBody DiscoverInput input) {
        String provider = input.provider() == null ? "" : input.provider();
        ModelVendorProfiles.Vendor vendor = ModelVendorProfiles.get(provider);
        String baseUrl = input.baseUrl() == null || input.baseUrl().isBlank()
                ? (vendor == null ? "" : vendor.defaultBaseUrl())
                : input.baseUrl().trim();
        if (baseUrl.isBlank()) {
            return Map.of("success", false, "message", "该厂商不支持自动获取模型列表，请手动输入模型 ID");
        }
        String apiKey = input.apiKey() == null ? "" : input.apiKey().trim();
        if (apiKey.isBlank()) {
            apiKey = store.apiKey(provider);
        }
        if (apiKey.isBlank()) {
            return Map.of("success", false, "message", "请先保存该厂商的 API Key");
        }
        String url = baseUrl.replaceAll("/+$", "") + "/models";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        try {
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return Map.of("success", false, "message", "端点返回 HTTP " + resp.statusCode()
                        + (resp.body() != null && resp.body().length() < 200 ? "：" + resp.body() : ""));
            }
            JsonNode data = objectMapper.readTree(resp.body()).path("data");
            List<String> ids = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode node : data) {
                    String id = node.path("id").asText("");
                    if (!id.isEmpty()) ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                return Map.of("success", false, "message", "端点未返回任何模型");
            }
            Collections.sort(ids);
            return Map.of("success", true, "models", ids);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Map.of("success", false, "message", msg);
        }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", message));
    }
}
