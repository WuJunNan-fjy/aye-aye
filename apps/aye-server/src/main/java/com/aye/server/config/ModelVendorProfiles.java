package com.aye.server.config;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型厂商 Profile 配置（对应前端 {@code modelApi.ts} 的 VendorInfo，
 * 数据从 legacy backend-ts 的 model-profiles.ts 平移）。
 *
 * <p>仅包含「添加模型」弹窗需要的展示字段；thinking 兼容性参数由
 * ai-core 的 LlmClient 统一处理（reasoning_content/reasoning/thinking 三字段归一），
 * 无需在此重复配置。
 */
public final class ModelVendorProfiles {

    /** 推荐模型 */
    public record PopularModel(String id, String name, Boolean supportsThinking) {}

    /** 厂商信息（前端 VendorInfo 同构） */
    public record Vendor(String id, String name, String icon, String iconUrl, String defaultBaseUrl,
                         String sdkProvider, String docsUrl, List<PopularModel> popularModels) {}

    /** 预置厂商列表（与 legacy model-profiles.ts 一致，共 12 家：11 家预置 + custom） */
    private static final List<Vendor> VENDORS = List.of(
            new Vendor("deepseek", "DeepSeek", "🔮", "/vendor-logos/deepseek.svg",
                    "https://api.deepseek.com/v1", "openai", "https://api-docs.deepseek.com/",
                    List.of(
                            new PopularModel("deepseek-v4-flash", "DeepSeek-V4-Flash", null),
                            new PopularModel("deepseek-v4-pro", "DeepSeek-V4-Pro", true),
                            new PopularModel("deepseek-coder", "DeepSeek-Coder", null))),
            new Vendor("mimo", "小米 MiMo", "🟠", "/vendor-logos/mimo.svg",
                    "https://api.xiaomimimo.com/v1", "openai", "https://platform.xiaomimimo.com/",
                    List.of(
                            new PopularModel("mimo-v2.5-pro", "MiMo-V2.5-Pro", true),
                            new PopularModel("mimo-v2.5", "MiMo-V2.5", true))),
            new Vendor("qwen", "通义千问", "🔷", "/vendor-logos/qwen.svg",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "openai",
                    "https://help.aliyun.com/zh/model-studio/developer-reference/compatibility-of-openai-with-dashscope",
                    List.of(
                            new PopularModel("qwen3-max", "Qwen3-Max", null),
                            new PopularModel("qwen3.6-plus", "Qwen3.6-Plus", true),
                            new PopularModel("qwen3.5-plus", "Qwen3.5-Plus", null),
                            new PopularModel("qwen3-turbo", "Qwen3-Turbo", null),
                            new PopularModel("qwq-32b", "QwQ-32B", true),
                            new PopularModel("qwen-long", "Qwen-Long (长文本)", null))),
            new Vendor("zhipu", "智谱清言", "💠", "/vendor-logos/zhipu.svg",
                    "https://open.bigmodel.cn/api/paas/v4", "openai",
                    "https://open.bigmodel.cn/dev/api/normal-model/glm-4",
                    List.of(
                            new PopularModel("glm-5", "GLM-5", true),
                            new PopularModel("glm-5-turbo", "GLM-5-Turbo", null),
                            new PopularModel("glm-4-flash", "GLM-4-Flash (免费)", null),
                            new PopularModel("glm-4-air", "GLM-4-Air", null))),
            new Vendor("moonshot", "Kimi (Moonshot)", "🌙", "/vendor-logos/moonshot.svg",
                    "https://api.moonshot.cn/v1", "openai", "https://platform.moonshot.cn/docs",
                    List.of(
                            new PopularModel("kimi-k2.6", "Kimi K2.6", true),
                            new PopularModel("moonshot-v1-128k", "Moonshot v1 (128K)", null),
                            new PopularModel("moonshot-v1-32k", "Moonshot v1 (32K)", null))),
            new Vendor("minimax", "MiniMax", "🎯", "/vendor-logos/minimax.svg",
                    "https://api.minimax.chat/v1", "openai", "https://www.minimaxi.com/document/",
                    List.of(
                            new PopularModel("abab6.5s-chat", "abab6.5s", null),
                            new PopularModel("abab6.5-chat", "abab6.5", null))),
            new Vendor("openai", "OpenAI", "🤖", "/vendor-logos/openai.svg",
                    "https://api.openai.com/v1", "openai", "https://platform.openai.com/docs",
                    List.of(
                            new PopularModel("gpt-5", "GPT-5", null),
                            new PopularModel("gpt-5-mini", "GPT-5 mini", null),
                            new PopularModel("gpt-4o", "GPT-4o", null),
                            new PopularModel("gpt-4o-mini", "GPT-4o mini", null),
                            new PopularModel("o3", "o3", true),
                            new PopularModel("o3-mini", "o3-mini", true))),
            new Vendor("anthropic", "Anthropic (Claude)", "🟣", "/vendor-logos/anthropic.svg",
                    "", "anthropic", "https://docs.anthropic.com/",
                    List.of(
                            new PopularModel("claude-fable-5", "Claude Fable 5", true),
                            new PopularModel("claude-opus-4-8", "Claude Opus 4.8", null),
                            new PopularModel("claude-sonnet-5", "Claude Sonnet 5", null),
                            new PopularModel("claude-sonnet-4-6", "Claude Sonnet 4.6", null),
                            new PopularModel("claude-haiku-4-5", "Claude Haiku 4.5", null))),
            new Vendor("siliconflow", "硅基流动", "⚡", "/vendor-logos/siliconflow.svg",
                    "https://api.siliconflow.cn/v1", "openai", "https://docs.siliconflow.cn/",
                    List.of(
                            new PopularModel("deepseek-ai/DeepSeek-V3", "DeepSeek-V3", null),
                            new PopularModel("deepseek-ai/DeepSeek-R1", "DeepSeek-R1", true),
                            new PopularModel("Qwen/Qwen2.5-72B-Instruct", "Qwen2.5-72B", null),
                            new PopularModel("Qwen/Qwen2.5-7B-Instruct", "Qwen2.5-7B (免费)", null),
                            new PopularModel("THUDM/glm-4-9b-chat", "GLM-4-9B (免费)", null))),
            new Vendor("openrouter", "OpenRouter", "🔀", "/vendor-logos/openrouter.svg",
                    "https://openrouter.ai/api/v1", "openai", "https://openrouter.ai/docs",
                    List.of(
                            new PopularModel("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", null),
                            new PopularModel("google/gemini-flash-1.5", "Gemini Flash 1.5", null),
                            new PopularModel("meta-llama/llama-3.1-70b-instruct", "Llama 3.1 70B", null),
                            new PopularModel("deepseek/deepseek-r1:free", "DeepSeek-R1 (免费)", true))),
            new Vendor("ollama", "Ollama", "🦙", "/vendor-logos/ollama.svg",
                    "http://localhost:11434/v1", "openai", "https://ollama.com/docs",
                    List.of(
                            new PopularModel("llama3.2", "Llama 3.2", null),
                            new PopularModel("qwen2.5", "Qwen 2.5", null),
                            new PopularModel("deepseek-r1", "DeepSeek R1", true))),
            new Vendor("custom", "自定义 (OpenAI 兼容)", "🔧", "/vendor-logos/custom.svg",
                    "", "openai", null, List.of()));

    private static final Map<String, Vendor> VENDOR_MAP = VENDORS.stream()
            .collect(Collectors.toMap(Vendor::id, Function.identity()));

    private ModelVendorProfiles() {}

    /** 全部厂商（供 /api/models/vendors 接口） */
    public static List<Vendor> all() {
        return VENDORS;
    }

    /** 按 id 取厂商，未知厂商返回 null */
    public static Vendor get(String vendorId) {
        return vendorId == null ? null : VENDOR_MAP.get(vendorId);
    }

    /** 厂商 id 是否有效（创建/更新模型时校验 provider） */
    public static boolean isValid(String vendorId) {
        return get(vendorId) != null;
    }
}
