package com.aye.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Aye 应用配置（对应 application.yml 的 {@code aye.*}）。
 */
@ConfigurationProperties(prefix = "aye")
public class AppProperties {

    /** 数据目录（SQLite 单文件就放在这里） */
    private String dataDir = ".aye";

    /** 工具层 resolvePath 的起点（工作区绝对路径） */
    private String cwd = System.getProperty("user.dir");

    /** 用户信任级别：trusted / standard / paranoid */
    private String trustLevel = "standard";

    /** Agent 最大步数 */
    private int maxSteps = 10;

    /** 单次输出最大 token */
    private int maxOutputTokens = 8000;

    // ---- 模型兜底（用户未在 models 表里配默认模型时用） ----

    private String defaultModel = "deepseek-chat";
    private String defaultBaseUrl = "https://api.deepseek.com/v1";
    private String defaultApiKey = "";

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }

    public String getTrustLevel() { return trustLevel; }
    public void setTrustLevel(String trustLevel) { this.trustLevel = trustLevel; }

    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }

    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public String getDefaultBaseUrl() { return defaultBaseUrl; }
    public void setDefaultBaseUrl(String defaultBaseUrl) { this.defaultBaseUrl = defaultBaseUrl; }

    public String getDefaultApiKey() { return defaultApiKey; }
    public void setDefaultApiKey(String defaultApiKey) { this.defaultApiKey = defaultApiKey; }
}
