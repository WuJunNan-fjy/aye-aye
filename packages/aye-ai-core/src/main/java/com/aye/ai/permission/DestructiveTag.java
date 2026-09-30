package com.aye.ai.permission;

/**
 * 破坏性操作标签（对应 DeepWork 的 {@code DestructiveTag} 联合类型）。
 *
 * <p>用途：1. 权限引擎的识别与分类  2. 审计日志的过滤与检索  3. 确认弹窗的醒目提示。
 * 规则：标签只描述「这是哪一类操作」，不描述具体风险等级；风险等级由权限规则决定。
 */
public enum DestructiveTag {
    // ---- Git 相关 ----
    GIT_DATA_LOSS,            // git reset --hard / git clean -fd（丢失未提交内容）
    GIT_HISTORY_REWRITE,      // git push -f / git rebase (重写历史)
    GIT_SKIP_HOOKS,           // git commit --no-verify (跳过钩子)

    // ---- 文件/目录删除 ----
    RECURSIVE_FORCE_DELETE,   // rm -rf / rd /s /q / del /s /q
    SINGLE_FILE_DELETE,       // rm / del（单文件，非递归）

    // ---- 数据库 / 基础设施 ----
    DB_DROP,                  // DROP TABLE / DROP DATABASE
    DB_MASS_DELETE,           // DELETE FROM 无条件
    K8S_DELETE,               // kubectl delete (集群资源)
    TF_DESTROY,               // terraform destroy (基础设施销毁)

    // ---- 路径越界 ----
    PATH_ESCAPE_WRITE,        // 写操作越出工作区根目录
    PATH_ESCAPE_READ,         // 读操作越出工作区根目录（可能读密钥/系统敏感文件）

    // ---- Shell 绕过手法 ----
    COMMAND_SUBSTITUTION_BYPASS, // $() / `` / for /f 等间接执行绕过
    SHELL_CODE_INJECTION,        // 元字符/引号闭合导致的注入
    POWERSHELL_IEX,              // Invoke-Expression / iex 远程下载+执行

    // ---- 系统级高危操作 ----
    SYSTEM_DOWN                  // 关机/重启/杀死 INIT（shutdown/reboot/kill -9 1）
}
