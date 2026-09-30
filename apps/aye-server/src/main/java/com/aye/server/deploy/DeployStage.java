package com.aye.server.deploy;

/**
 * 部署阶段（用户看到的 7 步，顺序即枚举顺序）。
 */
public enum DeployStage {

    SELECT("select", "选择项目"),
    DETECT("detect", "看懂项目"),
    PREPARE("prepare", "准备环境"),
    BUILD("build", "构建打包"),
    CONFIRM("confirm", "确认产物"),
    UPLOAD("upload", "上传服务器"),
    VERIFY("verify", "启动与验证");

    private final String id;
    private final String title;

    DeployStage(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public String id() { return id; }
    public String title() { return title; }

    /** 按 id 解析；非法返回 null。 */
    public static DeployStage byId(String id) {
        if (id == null) return null;
        for (DeployStage s : values()) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }
}
