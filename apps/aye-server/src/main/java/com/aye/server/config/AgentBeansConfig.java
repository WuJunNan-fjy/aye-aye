package com.aye.server.config;

import com.aye.ai.tool.ToolRegistry;
import com.aye.ai.tools.BuiltinTools;
import com.aye.server.chat.ChatService;
import com.aye.server.chat.PendingInteractionHub;
import com.aye.server.chat.SessionCommandGrants;
import com.aye.server.deploy.DeployExtractRemoteTool;
import com.aye.server.deploy.DeployProbeRemoteTool;
import com.aye.server.deploy.DeployRunStore;
import com.aye.server.deploy.DeployStageTool;
import com.aye.server.deploy.DeployStartRemoteTool;
import com.aye.server.deploy.DeployUploadTool;
import com.aye.server.deploy.EditLocalFileTool;
import com.aye.server.deploy.LocalCommandClassifier;
import com.aye.server.deploy.LocalExecService;
import com.aye.server.deploy.LocalJobManager;
import com.aye.server.deploy.LocalJobPollTool;
import com.aye.server.deploy.RunLocalAsyncTool;
import com.aye.server.deploy.WorkspaceStore;
import com.aye.server.local.LocalAttachmentRegistry;
import com.aye.server.local.LocalFileReadTool;
import com.aye.server.observability.ObservabilityProjection;
import com.aye.server.ssh.ListHostsTool;
import com.aye.server.ssh.RemoteGrepTool;
import com.aye.server.ssh.SftpEditTool;
import com.aye.server.ssh.SftpListTool;
import com.aye.server.ssh.SftpMkdirTool;
import com.aye.server.ssh.SftpReadTool;
import com.aye.server.ssh.SftpRemoveTool;
import com.aye.server.ssh.SftpRenameTool;
import com.aye.server.ssh.SftpService;
import com.aye.server.ssh.SftpUploadTool;
import com.aye.server.ssh.SftpWriteTool;
import com.aye.server.ssh.SshJobManager;
import com.aye.server.ssh.SshSessionManager;
import com.aye.server.ssh.SshTool;
import com.aye.server.ssh.SshToolSupport;
import com.aye.server.session.SessionEventManager;
import com.aye.server.session.SessionEventStore;
import com.aye.server.ssh.RunSshAsyncTool;
import com.aye.server.ssh.SshJobPollTool;
import com.aye.server.storage.AuditStore;
import com.aye.server.storage.Database;
import com.aye.server.storage.HostStore;
import com.aye.server.storage.KnownHostsStore;
import com.aye.server.storage.ModelProviderStore;
import com.aye.server.storage.SessionStore;
import com.aye.server.storage.SettingsStore;
import com.aye.server.tools.TodoStore;
import com.aye.server.tools.TodoWriteTool;
import com.aye.server.tools.websearch.DeepSeekNativeWebSearchBackend;
import com.aye.server.tools.websearch.ExaMcpWebSearchBackend;
import com.aye.server.tools.websearch.TavilyWebSearchBackend;
import com.aye.server.tools.websearch.WebFetchTool;
import com.aye.server.tools.websearch.WebSearchBackend;
import com.aye.server.tools.websearch.WebSearchTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 服务层装配：存储 / SSH / 工具注册表 / 聊天编排 全部在此注入。
 *
 * <p>ToolRegistry 是全局单例（内置工具 + 注入 SSH/SFTP 工具），全部工具直接下发
 * （当前规模 ≤30 个，无需延迟加载）；每会话的审计账本 / 文件队列由
 * {@link BuiltinTools#initializeToolRuntime} 单独创建。
 */
@Configuration
public class AgentBeansConfig {

    @Bean
    public Database database(AppProperties props) {
        return new Database(props.getDataDir());
    }

    @Bean
    public ModelProviderStore modelProviderStore(Database db) {
        return new ModelProviderStore(db);
    }

    @Bean
    public SessionStore sessionStore(Database db) {
        return new SessionStore(db);
    }

    @Bean
    public HostStore hostStore(Database db) {
        return new HostStore(db);
    }

    @Bean
    public AuditStore auditStore(Database db) {
        return new AuditStore(db);
    }

    @Bean
    public SettingsStore settingsStore(Database db) {
        return new SettingsStore(db);
    }

    @Bean
    public KnownHostsStore knownHostsStore(Database db) {
        return new KnownHostsStore(db);
    }

    // ── 事件溯源：事件日志持久化 + 每会话日志管家（恢复/冷闭合/批量刷盘） ──

    @Bean
    public SessionEventStore sessionEventStore(Database db) {
        return new SessionEventStore(db);
    }

    @Bean
    public SessionEventManager sessionEventManager(SessionEventStore sessionEventStore) {
        return new SessionEventManager(sessionEventStore);
    }

    // ── 观测中心：事件日志 → 操作日志 / Agent 追踪 只读投影 ──

    @Bean
    public ObservabilityProjection observabilityProjection(Database db, HostStore hostStore,
                                                           SessionStore sessionStore,
                                                           SessionEventStore sessionEventStore) {
        return new ObservabilityProjection(db, hostStore, sessionStore, sessionEventStore);
    }

    @Bean(destroyMethod = "disconnectAll")
    public SshSessionManager sshSessionManager(HostStore hostStore, KnownHostsStore knownHostsStore) {
        return new SshSessionManager(hostStore, knownHostsStore);
    }

    @Bean
    public SftpService sftpService(SshSessionManager sshSessionManager) {
        return new SftpService(sshSessionManager);
    }

    @Bean
    public LocalAttachmentRegistry localAttachmentRegistry() {
        return new LocalAttachmentRegistry();
    }

    @Bean
    public LocalFileReadTool localFileReadTool(LocalAttachmentRegistry localAttachmentRegistry) {
        return new LocalFileReadTool(localAttachmentRegistry);
    }

    // ── 小白一键部署：工作区 / 本机执行 / 账本 / 工具 ──

    @Bean
    public WorkspaceStore workspaceStore(Database db) {
        return new WorkspaceStore(db);
    }

    @Bean
    public LocalCommandClassifier localCommandClassifier() {
        return new LocalCommandClassifier();
    }

    @Bean
    public LocalExecService localExecService() {
        return new LocalExecService();
    }

    @Bean(destroyMethod = "shutdown")
    public LocalJobManager localJobManager(LocalExecService localExecService) {
        return new LocalJobManager(localExecService);
    }

    @Bean
    public DeployRunStore deployRunStore(Database db) {
        return new DeployRunStore(db);
    }

    @Bean
    public RunLocalAsyncTool runLocalAsyncTool(WorkspaceStore workspaceStore,
                                               LocalJobManager localJobManager) {
        return new RunLocalAsyncTool(workspaceStore, localJobManager);
    }

    @Bean
    public LocalJobPollTool localJobPollTool(LocalJobManager localJobManager) {
        return new LocalJobPollTool(localJobManager);
    }

    @Bean
    public EditLocalFileTool editLocalFileTool(LocalAttachmentRegistry localAttachmentRegistry) {
        return new EditLocalFileTool(localAttachmentRegistry);
    }

    @Bean
    public DeployUploadTool deployUploadTool(SshToolSupport sshToolSupport, SftpService sftpService,
                                             LocalAttachmentRegistry localAttachmentRegistry,
                                             DeployRunStore deployRunStore,
                                             WorkspaceStore workspaceStore) {
        return new DeployUploadTool(sshToolSupport, sftpService, localAttachmentRegistry,
                deployRunStore, workspaceStore);
    }

    @Bean
    public DeployStageTool deployStageTool(DeployRunStore deployRunStore,
                                           WorkspaceStore workspaceStore) {
        return new DeployStageTool(deployRunStore, workspaceStore);
    }

    @Bean
    public DeployExtractRemoteTool deployExtractRemoteTool(SshToolSupport sshToolSupport,
                                                           SshJobManager sshJobManager) {
        return new DeployExtractRemoteTool(sshToolSupport, sshJobManager);
    }

    @Bean
    public DeployStartRemoteTool deployStartRemoteTool(SshToolSupport sshToolSupport,
                                                       SshJobManager sshJobManager,
                                                       DeployRunStore deployRunStore) {
        return new DeployStartRemoteTool(sshToolSupport, sshJobManager, deployRunStore);
    }

    @Bean
    public DeployProbeRemoteTool deployProbeRemoteTool(SshToolSupport sshToolSupport,
                                                       SshJobManager sshJobManager) {
        return new DeployProbeRemoteTool(sshToolSupport, sshJobManager);
    }

    @Bean
    public SshToolSupport sshToolSupport(HostStore hostStore, SshSessionManager sshSessionManager) {
        return new SshToolSupport(hostStore, sshSessionManager);
    }

    // ── SSH / SFTP 工具族 ──

    @Bean
    public ListHostsTool listHostsTool(HostStore hostStore, SshSessionManager sshSessionManager) {
        return new ListHostsTool(hostStore, sshSessionManager);
    }

    @Bean(destroyMethod = "shutdown")
    public SshJobManager sshJobManager(SshSessionManager sshSessionManager) {
        return new SshJobManager(sshSessionManager);
    }

    @Bean
    public SshTool sshTool(SshToolSupport sshToolSupport, SshSessionManager sshSessionManager) {
        return new SshTool(sshToolSupport, sshSessionManager);
    }

    @Bean
    public RunSshAsyncTool runSshAsyncTool(SshToolSupport sshToolSupport, SshJobManager sshJobManager) {
        return new RunSshAsyncTool(sshToolSupport, sshJobManager);
    }

    @Bean
    public SshJobPollTool sshJobPollTool(SshJobManager sshJobManager) {
        return new SshJobPollTool(sshJobManager);
    }

    @Bean
    public SftpListTool sftpListTool(SshToolSupport support, SftpService sftpService) {
        return new SftpListTool(support, sftpService);
    }

    @Bean
    public SftpReadTool sftpReadTool(SshToolSupport support, SftpService sftpService) {
        return new SftpReadTool(support, sftpService);
    }

    @Bean
    public SftpWriteTool sftpWriteTool(SshToolSupport support, SftpService sftpService) {
        return new SftpWriteTool(support, sftpService);
    }

    @Bean
    public SftpUploadTool sftpUploadTool(SshToolSupport support, SftpService sftpService) {
        return new SftpUploadTool(support, sftpService);
    }

    @Bean
    public SftpRemoveTool sftpRemoveTool(SshToolSupport support, SftpService sftpService) {
        return new SftpRemoveTool(support, sftpService);
    }

    @Bean
    public SftpMkdirTool sftpMkdirTool(SshToolSupport support, SftpService sftpService) {
        return new SftpMkdirTool(support, sftpService);
    }

    @Bean
    public SftpEditTool sftpEditTool(SshToolSupport support, SftpService sftpService) {
        return new SftpEditTool(support, sftpService);
    }

    @Bean
    public SftpRenameTool sftpRenameTool(SshToolSupport support, SftpService sftpService) {
        return new SftpRenameTool(support, sftpService);
    }

    @Bean
    public RemoteGrepTool remoteGrepTool(SshToolSupport support, SshSessionManager sshSessionManager) {
        return new RemoteGrepTool(support, sshSessionManager);
    }

    // ── 联网搜索 / 网页抓取 / 待办 ──

    @Bean
    public WebSearchBackend deepSeekNativeWebSearchBackend(ModelProviderStore modelProviderStore) {
        return new DeepSeekNativeWebSearchBackend(modelProviderStore);
    }

    @Bean
    public WebSearchBackend exaMcpWebSearchBackend() {
        return new ExaMcpWebSearchBackend();
    }

    @Bean
    public WebSearchBackend tavilyWebSearchBackend() {
        return new TavilyWebSearchBackend();
    }

    @Bean
    public WebSearchTool webSearchTool(SettingsStore settingsStore, ModelProviderStore modelProviderStore,
                                       java.util.List<WebSearchBackend> backends) {
        return new WebSearchTool(settingsStore, modelProviderStore, backends);
    }

    @Bean
    public WebFetchTool webFetchTool() {
        return new WebFetchTool();
    }

    @Bean
    public TodoStore todoStore() {
        return new TodoStore();
    }

    @Bean
    public TodoWriteTool todoWriteTool(TodoStore todoStore) {
        return new TodoWriteTool(todoStore);
    }

    @Bean
    public ToolRegistry toolRegistry(ListHostsTool listHostsTool, SshTool sshTool,
                                     RunSshAsyncTool runSshAsyncTool, SshJobPollTool sshJobPollTool,
                                     SftpListTool sftpListTool, SftpReadTool sftpReadTool,
                                     SftpWriteTool sftpWriteTool, SftpUploadTool sftpUploadTool,
                                     SftpRemoveTool sftpRemoveTool, SftpMkdirTool sftpMkdirTool,
                                     SftpEditTool sftpEditTool, SftpRenameTool sftpRenameTool,
                                     RemoteGrepTool remoteGrepTool,
                                     WebSearchTool webSearchTool, WebFetchTool webFetchTool,
                                     TodoWriteTool todoWriteTool,
                                    LocalFileReadTool localFileReadTool,
                                    RunLocalAsyncTool runLocalAsyncTool,
                                    LocalJobPollTool localJobPollTool,
                                    EditLocalFileTool editLocalFileTool,
                                    DeployUploadTool deployUploadTool,
                                    DeployStageTool deployStageTool,
                                    DeployExtractRemoteTool deployExtractRemoteTool,
                                    DeployStartRemoteTool deployStartRemoteTool,
                                    DeployProbeRemoteTool deployProbeRemoteTool) {
        ToolRegistry registry = BuiltinTools.createToolRegistry();
        // v2 是服务器运维助手：核心是操作远程服务器的工具族 + 联网搜索 + 待办进度。
        registry.register(listHostsTool); // 始终可用：查 host_key 的前置工具
        registry.register(sshTool);       // 外部工具注入：SSH 属于「接口抽象 + 外部注入」一类
        registry.register(runSshAsyncTool); // 长命令异步执行（启动即返回 job_id）
        registry.register(sshJobPollTool);  // 异步作业长轮询（READ，免确认）
        registry.register(sftpListTool);
        registry.register(sftpReadTool);
        registry.register(sftpWriteTool);
        registry.register(sftpUploadTool);
        registry.register(sftpRemoveTool);
        registry.register(sftpMkdirTool);
        registry.register(sftpEditTool);
        registry.register(sftpRenameTool);
        registry.register(remoteGrepTool);
        registry.register(webSearchTool);  // 联网搜索高频使用
        registry.register(webFetchTool);   // 抓网页正文
        registry.register(todoWriteTool);  // 多步任务进度
        registry.register(localFileReadTool); // READ：附件路径经白名单约束
        // 小白一键部署工具族（本机/远程执行工具经分级与后端构造命令）
        registry.register(runLocalAsyncTool);
        registry.register(localJobPollTool);
        registry.register(editLocalFileTool);
        registry.register(deployUploadTool);
        registry.register(deployStageTool);
        registry.register(deployExtractRemoteTool);
        registry.register(deployStartRemoteTool);
        registry.register(deployProbeRemoteTool);
        return registry;
    }

    @Bean
    public ChatService chatService(SessionStore sessionStore, ModelProviderStore modelProviderStore,
                                   AppProperties props, ToolRegistry toolRegistry,
                                   AuditStore auditStore, HostStore hostStore,
                                   PendingInteractionHub interactionHub,
                                   SessionCommandGrants sessionCommandGrants,
                                   SessionEventManager sessionEventManager,
                                   SshSessionManager sshSessionManager,
                                   LocalAttachmentRegistry localAttachmentRegistry,
                                   WorkspaceStore workspaceStore,
                                   LocalCommandClassifier localCommandClassifier,
                                   LocalPathAuthority localPathAuthority) {
        return new ChatService(sessionStore, modelProviderStore, props, toolRegistry,
                auditStore, hostStore, interactionHub, sessionCommandGrants,
                sessionEventManager, sshSessionManager, localAttachmentRegistry,
                workspaceStore, localCommandClassifier, localPathAuthority);
    }

    /**
     * CORS：本地桌面应用（Electron / Vite dev）与局域网浏览器访问跨源使用。
     * 数据面安全由 {@link ApiTokenAuthConfig} 的令牌校验保证（桌面端必配 token；
     * 未配置 token 的浏览器模式为显式开放形态）。
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("*")
                        .allowedHeaders("*");
            }
        };
    }
}
