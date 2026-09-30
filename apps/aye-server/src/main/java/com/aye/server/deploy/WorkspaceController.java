package com.aye.server.deploy;

import com.aye.server.config.AppProperties;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区 REST：/api/workspaces。
 *
 * <p>前端工作区选择器调用：列表、登记（选本地目录/ git clone 完成后）、重命名、移除、
 * 绑定到当前会话。
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceStore store;
    private final LocalExecService exec;
    private final AppProperties props;
    private final com.aye.server.config.LocalPathAuthority pathAuthority;

    public WorkspaceController(WorkspaceStore store, LocalExecService exec, AppProperties props,
                               com.aye.server.config.LocalPathAuthority pathAuthority) {
        this.store = store;
        this.exec = exec;
        this.props = props;
        this.pathAuthority = pathAuthority;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> items =
                store.list().stream().map(WorkspaceStore.Workspace::row).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspaces", items);
        return out;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String path = body.get("path") == null ? null : String.valueOf(body.get("path"));
        String title = body.get("title") == null ? null : String.valueOf(body.get("title"));
        String source = body.get("source") == null ? null : String.valueOf(body.get("source"));
        String gitUrl = body.get("gitUrl") == null ? null : String.valueOf(body.get("gitUrl"));
        String pathToken = body.get("pathToken") == null ? null : String.valueOf(body.get("pathToken"));
        // 登记本机目录必须有主进程颁发的来源凭证（用户经原生选择器确认过该目录）
        if (path != null && !path.isBlank()) {
            pathAuthority.requirePathProof(path, pathToken);
        }
        WorkspaceStore.Workspace ws = store.create(path, title, source, gitUrl);
        return ws.row();
    }

    @PostMapping("/{id}/rename")
    public Map<String, Object> rename(@PathVariable long id,
                                      @RequestBody Map<String, Object> body) {
        String title = body.get("title") == null ? null : String.valueOf(body.get("title"));
        store.rename(id, title);
        return store.get(id).row();
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        boolean ok = store.delete(id);
        return Map.of("ok", ok);
    }

    /** 绑定工作区到会话（选中工作区时前端立即调用）。 */
    @PostMapping("/bind")
    public Map<String, Object> bind(@RequestBody Map<String, Object> body) {
        String sessionId = body.get("sessionId") == null ? null : String.valueOf(body.get("sessionId"));
        Object wsId = body.get("workspaceId");
        if (sessionId == null || sessionId.isBlank() || !(wsId instanceof Number)) {
            throw new IllegalArgumentException("sessionId 与 workspaceId 不能为空");
        }
        store.bindSession(sessionId, ((Number) wsId).longValue());
        return Map.of("ok", true);
    }

    /** 解绑当前会话的工作区（切换项目/移除绑定时调用）。 */
    @PostMapping("/unbind")
    public Map<String, Object> unbind(@RequestBody Map<String, Object> body) {
        String sessionId = body.get("sessionId") == null ? null : String.valueOf(body.get("sessionId"));
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        store.unbindSession(sessionId);
        return Map.of("ok", true);
    }

    /** 查会话当前绑定的工作区（刷新页面/重启后前端回填）；未绑定 workspace 字段为 null。 */
    @GetMapping("/bound")
    public Map<String, Object> bound(@org.springframework.web.bind.annotation.RequestParam
                                     String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        WorkspaceStore.Workspace ws = store.boundWorkspace(sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspace", ws == null ? null : ws.row());
        return out;
    }

    /**
     * 从 Git 拉取：clone 到 {@code <dataDir>/projects/<repo>}（已存在则直接复用），
     * 成功后登记工作区并返回。第一刀仅支持公开仓库/本机已配凭证的仓库。
     */
    @PostMapping("/clone-git")
    public Map<String, Object> cloneGit(@RequestBody Map<String, Object> body)
            throws IOException, InterruptedException {
        String url = body.get("url") == null ? "" : String.valueOf(body.get("url")).trim();
        String branch = body.get("branch") == null ? "" : String.valueOf(body.get("branch")).trim();
        if (!GIT_URL.matcher(url).matches()) {
            throw new IllegalArgumentException("Git 地址不合法（仅支持 http(s):// 或 git@ 形式）：" + url);
        }
        if (!branch.isEmpty() && !SAFE_BRANCH.matcher(branch).matches()) {
            throw new IllegalArgumentException("分支名不合法：" + branch);
        }
        String repoName = repoName(url);

        Path projectsDir = Path.of(props.getDataDir(), "projects").toAbsolutePath();
        Files.createDirectories(projectsDir);
        Path target = projectsDir.resolve(repoName);
        if (!Files.exists(target)) {
            String cmd = "git clone " + (branch.isEmpty() ? "" : "-b " + branch + " ")
                    + url + " \"" + target.toString().replace('\\', '/') + "\"";
            LocalExecService.ExecResult r = exec.runSync(cmd, projectsDir, 300);
            if (r.timedOut() || r.exitCode() != 0) {
                throw new IllegalStateException(
                        "git clone 失败：" + (r.stderr().isBlank() ? r.stdout() : r.stderr()));
            }
        }
        WorkspaceStore.Workspace ws = store.create(target.toString(), repoName, "git", url);
        return ws.row();
    }

    private static final java.util.regex.Pattern GIT_URL = java.util.regex.Pattern.compile(
            "^(?:https?://[A-Za-z0-9._~:/?#@!$*+,;=%-]+|git@[A-Za-z0-9.-]+:[A-Za-z0-9._/-]+)$");
    private static final java.util.regex.Pattern SAFE_BRANCH = java.util.regex.Pattern.compile(
            "[A-Za-z0-9._/-]{1,100}");

    /** 从 URL 推导仓库目录名。 */
    private static String repoName(String url) {
        String tail = url;
        int slash = tail.lastIndexOf('/');
        if (slash >= 0) tail = tail.substring(slash + 1);
        int colon = tail.indexOf(':');
        if (colon >= 0) tail = tail.substring(colon + 1);
        if (tail.endsWith(".git")) tail = tail.substring(0, tail.length() - 4);
        if (!tail.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("无法从地址推导合法仓库名：" + url);
        }
        return tail;
    }
}
