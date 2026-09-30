package com.aye.ai.permission;

/**
 * 工具操作类别（工具注册表核心：每个工具必须显式申报其一）。
 *
 * <p>这是「工具分类注册表」的类别契约（对应审查报告 P0-2）。相比旧的「硬编码工具名单」，
 * 类别由工具自身声明，权限引擎据此做 fail-closed 决策：
 * <ul>
 *   <li>{@link #READ}：只读，永不破坏 → 直接放行</li>
 *   <li>{@link #WRITE}：写文件系统（不改本机环境）→ 需按策略确认</li>
 *   <li>{@link #EXECUTE}：执行命令/脚本（可影响环境）→ 需按策略确认 + 命令级黑名单</li>
 *   <li>{@link #DESTRUCTIVE}：明确破坏性（删库/格式化/覆盖）→ 一律拒绝或强确认</li>
 * </ul>
 *
 * <p><b>fail-closed 约束</b>：{@code ToolRegistry} 注册时校验 category，缺失即抛异常拒绝加载，
 * 从根上杜绝「漏登记名单 → default-allow 放行」的暗洞。
 */
public enum ToolCategory {

    /** 只读操作：不影响任何持久状态，直接放行 */
    READ,

    /** 写文件/数据：会改动工作区或远端文件，但不改变本机运行环境 */
    WRITE,

    /** 执行命令/脚本：可影响本机或远端的环境（run_shell/bash/run_ssh） */
    EXECUTE,

    /** 明确破坏性：删除、格式化、覆盖裸设备等不可逆操作，一律最严处理 */
    DESTRUCTIVE;
}