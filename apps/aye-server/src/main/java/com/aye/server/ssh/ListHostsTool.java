package com.aye.server.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.permission.ToolCategory;
import com.aye.ai.tool.JsonSchema;
import com.aye.ai.tool.Tool;
import com.aye.ai.tool.ToolContext;
import com.aye.server.storage.HostStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * list_hosts 工具 —— 列出已配置的 SSH 主机（供 AI 获取 host_key 及其连接可用性）。
 *
 * <p>其它 SSH / SFTP 工具（run_ssh、sftp_*）都以 {@code host_key} 作为入参，
 * 本工具是它们的前置查询入口，故设为 always-load（始终可用）。
 *
 * <p>每台主机附带状态标注（已连接 / 可自动建连 / 需用户先连接）：凭据不落库，
 * 密码认证主机只有内存中有活跃会话时才能被 AI 工具使用。先给出状态，
 * 避免 AI 盲目调用 run_ssh 才在深层报错、白耗一轮。
 */
public final class ListHostsTool implements Tool {

    private final HostStore hostStore;
    private final SshSessionManager sessionManager;

    public ListHostsTool(HostStore hostStore, SshSessionManager sessionManager) {
        this.hostStore = hostStore;
        this.sessionManager = sessionManager;
    }

    @Override
    public String name() { return "list_hosts"; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public String description() {
        return "列出已配置的 SSH 主机（含稳定 host_key、名称、地址、用户名、认证方式、连接可用性）。"
                + "run_ssh / sftp_* 工具都需要 host_key 参数，必须使用本工具返回的值。"
                + "状态说明：[已连接]=可直接执行；[可自动建连]=私钥认证会自动建连；"
                + "[需用户先连接]=凭据不落库，AI 无法自行连接，须引导用户先在主机管理页连接。";
    }

    @Override
    public JsonNode inputSchema() {
        return JsonSchema.object().build();
    }

    @Override
    public String promptSnippet() {
        return "list_hosts — 列出已配置的 SSH 主机及其连接可用性（已连接/可自动建连/需用户先连接），"
                + "获取后续 run_ssh / sftp_* 工具所需的 host_key。";
    }

    @Override
    public Object execute(JsonNode input, ToolContext ctx) {
        List<Map<String, Object>> hosts = hostStore.findAll();
        if (hosts.isEmpty()) {
            return "尚未配置任何 SSH 主机。请先在主机管理页添加服务器。";
        }
        StringBuilder sb = new StringBuilder("已配置 ").append(hosts.size()).append(" 台主机：\n");
        for (Map<String, Object> h : hosts) {
            sb.append("- host_key=").append(h.get("hostKey"))
                    .append("  ").append(h.get("name"))
                    .append("  ").append(h.get("username"))
                    .append('@').append(h.get("address")).append(':').append(h.get("port"))
                    .append("  (").append(h.get("authType")).append(")")
                    .append("  ").append(connectivity(h)).append('\n');
        }
        return sb.toString();
    }

    /** 按会话内存状态 + 认证方式判定该主机对 AI 工具的可用性（与 SshToolSupport.resolveSession 三级策略一致）。 */
    private String connectivity(Map<String, Object> host) {
        long hostId = ((Number) Objects.requireNonNull(host.get("id"))).longValue();
        if (sessionManager.findByHostId(hostId) != null) {
            return "[已连接]";
        }
        if ("PRIVATE_KEY".equals(host.get("authType"))) {
            Object path = host.get("privateKeyPath");
            if (path != null && !path.toString().isBlank()) {
                return "[可自动建连]";
            }
        }
        return "[需用户先连接]";
    }
}
