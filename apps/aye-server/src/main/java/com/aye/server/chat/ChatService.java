package com.aye.server.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.agent.AgentEventSink;
import com.aye.ai.agent.AgentLoop;
import com.aye.ai.agent.AgentLoopOptions;
import com.aye.ai.event.AgentEvent;
import com.aye.ai.llm.LlmTypes.AuthHeaderKind;
import com.aye.ai.llm.LlmTypes.LlmProviderConfig;
import com.aye.ai.message.UIMessage;
import com.aye.ai.message.UIMessage.UIPart;
import com.aye.ai.permission.DestructiveTag;
import com.aye.ai.permission.TrustLevel;
import com.aye.ai.session.SessionEvent;
import com.aye.ai.session.SessionEventLog;
import com.aye.ai.session.SessionEventType;
import com.aye.ai.tool.ToolContext;
import com.aye.ai.tool.ToolRegistry;
import com.aye.ai.tools.BuiltinTools;
import com.aye.ai.util.Json;
import com.aye.server.audit.SqliteAuditLedger;
import com.aye.server.config.AppProperties;
import com.aye.server.config.ModelVendorProfiles;
import com.aye.server.deploy.LocalCommandClassifier;
import com.aye.server.deploy.WorkspaceStore;
import com.aye.server.local.LocalAttachmentRegistry;
import com.aye.server.session.SessionEventManager;
import com.aye.server.ssh.SshSessionManager;
import com.aye.server.storage.AuditStore;
import com.aye.server.storage.HostStore;
import com.aye.server.storage.ModelProviderStore;
import com.aye.server.storage.ModelProviderStore.CatalogModel;
import com.aye.server.storage.ModelProviderStore.DefaultRef;
import com.aye.server.storage.ModelProviderStore.Provider;
import com.aye.server.storage.SessionStore;
import com.aye.server.storage.SettingsStore;
import com.aye.server.web.ApiException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天编排服务：把 HTTP 请求桥接到 AI 核心的 {@link AgentLoop}，并流式回吐 SSE。
 *
 * <p>一次请求的生命周期：
 * <ol>
 *   <li>解析模型 → 构造 {@link LlmProviderConfig}</li>
 *   <li>从 SQLite 读历史 + 追加用户消息</li>
 *   <li>用 {@link BuiltinTools#initializeToolRuntime} 构造每会话的 ToolContext</li>
 *   <li>后台线程跑 {@link AgentLoop#agentLoop}，事件经 sink 直接写 SseEmitter</li>
 *   <li>结束后用 {@link ConversationRecorder} 重建 assistant/tool 消息落库</li>
 * </ol>
 */
public final class ChatService {

    private static final System.Logger LOG = System.getLogger(ChatService.class.getName());

    private final SessionStore sessionStore;
    private final ModelProviderStore modelStore;
    private final AppProperties props;
    private final ToolRegistry toolRegistry;
    private final AuditStore auditStore;
    private final HostStore hostStore;
    private final PendingInteractionHub interactionHub;
    /** 会话级命令授权（用户勾选「本会话相同操作不再询问」后的精确匹配免确认集合）。 */
    private final SessionCommandGrants sessionGrants;
    /** 事件溯源管家：事件日志是唯一事实源，任何打断后都可据此续跑。 */
    private final SessionEventManager eventManager;
    /** SSH 会话管家：用于在系统提示词环境段渲染主机连接状态快照。 */
    private final SshSessionManager sshSessionManager;
    /** 本轮附件路径白名单：请求进入时注册，runAgent 结束时清除。 */
    private final LocalAttachmentRegistry localAttachments;
    private final com.aye.server.config.LocalPathAuthority pathAuthority;
    /** 项目（工作区）存储：会话绑定的工作区根。 */
    private final WorkspaceStore workspaceStore;
    /** 本机命令分级器：仅用于硬拒绝，不能替代人工批准。 */
    private final LocalCommandClassifier commandClassifier;
    private final SettingsStore settingsStore;

    /** 用户显式进入部署模式时注入系统提示的「一键部署」流水线指令。 */
    private static final String DEPLOY_SUPPLEMENT = """
            用户已显式进入「部署模式」，本次对话绑定了一个本地项目（工作区）。\
            本轮及部署模式下的后续消息都围绕部署展开：当用户表达部署意图（如「部署/发布/上线这个项目」）\
            或要求继续时，你必须执行「小白一键部署」标准流水线，并通过 deploy_stage 工具同步每一步状态\
            （这是前端进度卡片的唯一数据源）。阶段固定为 7 个，id 与含义如下：\
            select=选择项目、detect=识别项目、prepare=准备构建环境、build=构建打包、confirm=确认产物、\
            upload=上传到服务器、verify=启动并验证。

            执行要求：
            1. select：确认当前工作区就是用户要部署的项目。
            2. detect：用 run_local_async 在工作区内查看文件（dir/ls、package.json、pom.xml 等），\
               判断项目类型、构建方式与启动方式；长命令用 local_job_poll 查看结果。
            3. prepare：检查/准备构建所需环境（如 npm install）；无需构建的项目标记 skipped。
            4. build：执行构建命令（如 npm run build、mvn package）；静态项目可跳过。
            5. confirm：在工作区内创建部署压缩包（tar -czf <name>.tar.gz <要部署的文件或目录>），\
               一句话向用户说明产物名称与大小；用户已在消息中明确授权部署时无需再次询问。
            6. upload：调用 deploy_upload 上传压缩包（remoteDir 形如 /root/aye-apps/<app>，\
               filename 与压缩包同名），再用 deploy_extract_remote 解压到 current。
            7. verify：调用 deploy_start_remote 按其支持的三种形态之一启动\
               （java -jar / node / python3 -m http.server），再用 deploy_probe_remote 探活；\
               成功后告知用户访问地址与停止方式。

            纪律：每个阶段开始时 deploy_stage(status=running)，结束时 deploy_stage(status=done|failed|skipped,\
            summary=一句话结果)；阶段失败不要跳过，先诊断修复再重试该阶段；远程部署目录统一使用\
            /root/aye-apps/<app>，不要把文件散落到其他位置；除只读探查外，不要用 run_ssh/sftp \
            手工替代部署工具族。""";

    /**
     * 每会话 Agent 运行互斥：防止同 sessionId 同时发起多个 agent 导致事件 / 消息落库交错。
     * v1（SSE）与 v2（后台 turn）共用同一注册表；{@link RunHandle#cancel()} 是唯一中止信号：
     * v1 由 emitter completion/timeout 置位（断连即中止，行为冻结），v2 由 /cancel 显式置位，
     * 与任何 HTTP 连接的存废无关。
     */
    private final ConcurrentHashMap<String, RunHandle> runningSessions = new ConcurrentHashMap<>();

    /**
     * 每会话中途插话队列：Agent 运行期间用户经 /api/agent/interject 发来的指示在此缓冲，
     * AgentLoop 在步边界 drain（协作式，不打断流式/工具执行）。生命周期与 runningSessions 对齐。
     */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> interjectionQueues =
            new ConcurrentHashMap<>();

    /** 有界 agent 运行线程池：替代每请求裸 new Thread，控制并发会话上限。 */
    private static final int AGENT_CORE = 2;
    private static final int AGENT_MAX = 8;
    private static final ExecutorService AGENT_POOL = new ThreadPoolExecutor(
            AGENT_CORE, AGENT_MAX, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "aye-agent");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /** 一次 agent 运行的控制句柄：turnNo 对 v1 可能为 0（worker 内自行编号），v2 在提交前确定。 */
    private record RunHandle(String sessionId, int turnNo, AtomicBoolean cancel, long startedAt) {
    }

    /** v2 启动成功结果：POST /api/v2/agent/turns 返回 202。 */
    public record StartResult(String sessionId, int turnNo) {
    }

    public ChatService(SessionStore sessionStore, ModelProviderStore modelStore,
                       AppProperties props, ToolRegistry toolRegistry,
                       AuditStore auditStore, HostStore hostStore,
                       PendingInteractionHub interactionHub,
                       SessionCommandGrants sessionGrants,
                       SessionEventManager eventManager, SshSessionManager sshSessionManager,
                       LocalAttachmentRegistry localAttachments,
                       WorkspaceStore workspaceStore,
                       LocalCommandClassifier commandClassifier,
                       com.aye.server.config.LocalPathAuthority pathAuthority,
                       SettingsStore settingsStore) {
        this.sessionStore = sessionStore;
        this.modelStore = modelStore;
        this.props = props;
        this.toolRegistry = toolRegistry;
        this.auditStore = auditStore;
        this.hostStore = hostStore;
        this.interactionHub = interactionHub;
        this.sessionGrants = sessionGrants;
        this.eventManager = eventManager;
        this.sshSessionManager = sshSessionManager;
        this.localAttachments = localAttachments;
        this.workspaceStore = workspaceStore;
        this.commandClassifier = commandClassifier;
        this.pathAuthority = pathAuthority;
        this.settingsStore = settingsStore;
    }

    /**
     * v1 入口：SSE 流式对话（行为冻结）。
     *
     * <p>emitter completion/timeout 即置 RunHandle 取消信号——浏览器断连/刷新仍立即中止 agent，
     * 审批等待中断连仍按拒绝（见交互闭包）。事件实时出口为 SseEmitter。
     */
    public SseEmitter stream(ChatRequest input) {
        ChatRequest req = resolveRequest(input);
        SseEmitter emitter = new SseEmitter(0L); // 无超时，由客户端或 Agent 结束
        String sessionId = (req.sessionId() == null || req.sessionId().isBlank())
                ? UUID.randomUUID().toString() : req.sessionId();

        // 同会话互斥：已有一个 agent 在跑则拒绝并发，避免落库交错（v1/v2 共用）
        RunHandle handle = new RunHandle(sessionId, 0, new AtomicBoolean(false),
                System.currentTimeMillis());
        if (runningSessions.putIfAbsent(sessionId, handle) != null) {
            try {
                send(emitter, Json.object().put("type", "error")
                        .put("message", "该会话已有任务在运行，请稍后再试"));
            } catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }
        // v1 冻结语义：HTTP 连接结束（客户端断开/完成/超时）即中止 agent
        emitter.onCompletion(() -> handle.cancel().set(true));
        emitter.onTimeout(() -> handle.cancel().set(true));

        // 建插话队列（在提交任务前，消除「任务已起跑但队列未就绪」的窗口）
        interjectionQueues.put(sessionId, new ConcurrentLinkedQueue<>());

        try {
            AGENT_POOL.execute(() -> runAgent(req, sessionId, emitter, handle));
        } catch (RejectedExecutionException e) {
            runningSessions.remove(sessionId, handle);
            interjectionQueues.remove(sessionId);
            try {
                send(emitter, Json.object().put("type", "error")
                        .put("message", "系统繁忙，请稍后再试"));
            } catch (Exception ignored) {}
            emitter.complete();
        }
        return emitter;
    }

    /**
     * v2 入口：启动一个与 HTTP 连接完全解耦的后台 turn。
     *
     * <p>同步完成参数/互斥/Key/线程池校验后返回 202，agent 事件只写事件日志
     * （前端经 GET events 订阅），调用方断开 HTTP 不产生任何中止信号。
     */
    public StartResult start(ChatRequest input) {
        ChatRequest req = resolveRequest(input);
        if (req.message() == null || req.message().isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        String sessionId = (req.sessionId() == null || req.sessionId().isBlank())
                ? UUID.randomUUID().toString() : req.sessionId();

        RunHandle placeholder = new RunHandle(sessionId, 0, new AtomicBoolean(false),
                System.currentTimeMillis());
        if (runningSessions.putIfAbsent(sessionId, placeholder) != null) {
            throw new ApiException(409, "该会话已有任务在运行，请稍后再试", "SESSION_BUSY");
        }
        try {
            // 同步模型 Key 校验（与 v1 SSE error 文案一致）
            LlmProviderConfig provider = resolveProvider(req.provider(), req.modelId());
            if (provider.apiKey() == null || provider.apiKey().isBlank()) {
                throw new ApiException(422,
                        "未配置模型 API Key，请先在「设置」里配置模型与密钥",
                        "MODEL_KEY_MISSING");
            }

            // 保证会话行存在（202 返回时侧栏立即可见）；事件日志 open 顺带完成冷恢复闭合
            String userText = buildUserText(req);
            sessionStore.createSession(sessionId, userText, req.hostId());
            SessionEventLog opened = eventManager.open(sessionId);
            int turnNo = nextTurnNo(opened);

            interjectionQueues.put(sessionId, new ConcurrentLinkedQueue<>());
            RunHandle handle = new RunHandle(sessionId, turnNo, placeholder.cancel(),
                    placeholder.startedAt());
            runningSessions.put(sessionId, handle);
            try {
                AGENT_POOL.execute(() -> runAgent(req, sessionId, null, handle));
            } catch (RejectedExecutionException e) {
                runningSessions.remove(sessionId, handle);
                interjectionQueues.remove(sessionId);
                throw new ApiException(503, "系统繁忙，请稍后再试", "AGENT_POOL_FULL");
            }
            LOG.log(System.Logger.Level.INFO,
                    "v2 agent turn started: session=" + sessionId + " turn=" + turnNo);
            return new StartResult(sessionId, turnNo);
        } catch (RuntimeException e) {
            // 提交前任何失败：释放互斥与插话队列（worker 未提交，无并发接任窗口）
            runningSessions.remove(sessionId, placeholder);
            interjectionQueues.remove(sessionId);
            throw e;
        }
    }

    /**
     * v2 显式取消：幂等置位该会话运行句柄的取消信号。
     * AgentLoop 在步边界 / LLM 读循环内秒级退出并发 turn/end(aborted)。
     *
     * @return false=该会话当前没有运行任务（调用方按幂等提示处理，不报错）
     */
    public boolean cancel(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        RunHandle handle = runningSessions.get(sessionId);
        if (handle == null) return false;
        handle.cancel().set(true);
        LOG.log(System.Logger.Level.INFO,
                "agent cancel requested: session=" + sessionId + " turn=" + handle.turnNo());
        return true;
    }

    /**
     * 共享 agent 运行核心（v1 SSE / v2 后台 turn 同构）。
     *
     * @param emitter v1 传 SseEmitter（实时事件出口 + 断连中止）；v2 传 null（事件只走
     *                事件日志 + recorder，无任何连接可断）
     * @param handle  本次运行句柄，其 cancel 信号是唯一中止来源
     */
    private void runAgent(ChatRequest req, String sessionId, SseEmitter emitter, RunHandle handle) {
        AtomicBoolean aborted = handle.cancel();
        // live=true 为 v1：事件额外推 SSE，发送失败（客户端断开）置中止；
        // v2：sink 只喂 recorder（事件日志双写由 AgentLoop 的 eventLog 通道负责）。
        boolean live = emitter != null;
        ConversationRecorder recorder = new ConversationRecorder();
        AgentEventSink sink = event -> {
            recorder.accept(event);
            if (live) {
                try {
                    send(emitter, AgentEventJson.toJson(event));
                } catch (Exception e) {
                    aborted.set(true); // 客户端断开 → 触发中止（v1 冻结语义）
                }
            }
        };

        // 事件溯源 turn 状态：turn/start 发出后置位，catch 据此闭合孤儿 turn。
        SessionEventLog eventLog = null;
        int turnNo = 0;
        boolean turnStarted = false;
        String turnModelId = null;

        try {
            ResolvedModel resolved = resolveModel(req.provider(), req.modelId());
            LlmProviderConfig provider = resolved.config();
            CatalogModel catalog = resolved.catalog();
            if (provider.apiKey() == null || provider.apiKey().isBlank()) {
                // v2 在 start() 已同步校验，此处理论不可达；v1 保持 SSE error 行为。
                pushError(emitter,
                        "未配置模型 API Key，请先在「设置」里配置模型与密钥");
                completeEmitter(emitter);
                return;
            }

            // 保证会话存在（sessions 表：看板/侧栏投影），用户文本含注入的附件文本
            String userText = buildUserText(req);
            sessionStore.createSession(sessionId, userText, req.hostId());

            // ---- 事件溯源：打开日志（打开即冷恢复闭合）→ 新 turn ----
            // 事件日志是唯一事实源；LLM 历史由 surface 事件折叠，停止/断线/重启后续跑都靠它。
            // 边界顺序契约：turn/start 必须是该 turn 的第一条事件，user/message 紧随其后。
            eventLog = eventManager.open(sessionId);
            eventManager.markActive(sessionId);
            // v2 的 turnNo 在 start() 已预估（互斥保证无竞争）；v1 沿用 worker 内编号。
            turnNo = handle.turnNo() > 0 ? handle.turnNo() : nextTurnNo(eventLog);
            turnModelId = provider.model();
            eventLog.turnStarted(turnNo, turnModelId, req.mode());
            turnStarted = true;
            eventLog.userMessage(turnNo, userText, SessionEventType.SOURCE_USER);
            List<UIMessage> messages = new ArrayList<>(eventLog.deriveUIMessages());

            // 过渡兼容：旧 messages 表继续写一份投影，供尚未切换事件引擎的旧接口/页面读取；
            // Agent 喂模历史已改为 deriveUIMessages()，不再读此表（#9 前端切换后可下线）。
            UIMessage userMsg = new UIMessage("user", List.of(new UIPart.TextPart(userText)));
            sessionStore.appendMessage(sessionId, userMsg);

            // 每会话 ToolContext（注入审计账本 / 文件队列）
            // 审计走 SQLite 落库：每个工具执行留痕到 audit_logs，供「日志中心」页查询。
            ToolContext ctx = BuiltinTools.initializeToolRuntime(
                    toolRegistry, sessionId, props.getCwd(), parseTrustLevel(req.mode()), aborted::get, null,
                    new SqliteAuditLedger(auditStore, hostStore, req.mode()));
            // executionMode 透传到权限引擎：readonly 模式下写/执行工具被拒绝
            ctx.executionMode(req.mode());

            // 注册本轮附件到会话级白名单（桌面本机路径 / 浏览器 uploads 路径统一处理）
            if (req.attachments() != null) {
                for (ChatAttachment att : req.attachments()) {
                    if (att.path() == null || att.path().isBlank()) continue;
                    // 桌面端必须提供主进程颁发的路径来源凭证，防止网页伪造本机路径
                    pathAuthority.requirePathProof(att.path(), att.pathToken());
                    if (att.isDirectory()) {
                        localAttachments.registerDirectory(sessionId, att.path());
                    } else {
                        localAttachments.registerFile(sessionId, att.path());
                    }
                }
            }

            // 绑定本轮项目（工作区）：注册到会话绑定 + 授权根（之后本机工具只在该目录内活动）
            if (req.workspaceId() != null) {
                WorkspaceStore.Workspace ws = workspaceStore.get(req.workspaceId());
                if (ws == null) {
                    throw new ApiException(400, "项目（工作区）不存在：" + req.workspaceId(),
                            "WORKSPACE_NOT_FOUND");
                }
                workspaceStore.bindSession(sessionId, ws.id());
                localAttachments.registerDirectory(sessionId, ws.canonicalPath());
            }
            sessionGrants.prepare(sessionId, req.mode(), workspaceScope(sessionId));

            // 交互确认通道 + 主动询问通道：挂到 ToolContext，AgentLoop 的工具执行器
            // 在命中 Confirm/ask_user 时调用，阻塞等待前端经 REST 回传（见 PendingInteractionHub）。
            // interaction/requested 事件由 AgentLoop wrapper 写事件日志（两路径共有）；
            // v1 额外在此把 confirmation_request / ask_user 推给当前 SSE（冻结行为）。
            ctx.waitForUserConfirm(new ToolContext.UserConfirmCallbackEx() {
                @Override
                public ToolContext.UserConfirmResult awaitConfirmEx(
                        String callId, String confirmId, String toolName, String message,
                        List<DestructiveTag> tags, JsonNode input) {
                    List<String> tagNames = tags == null ? List.of()
                            : tags.stream().map(String::valueOf).toList();
                    // 本机危险命令的硬拒绝先于记住授权；安全分级不再自动批准操作。
                    if (isLocalCommandDenied(toolName, input)) {
                        recorder.confirmResolved(confirmId, toolName,
                                "[本机安全规则拒绝] " + message, tagNames, input, false);
                        return ToolContext.UserConfirmResult.decided(false, false);
                    }
                    String scope = workspaceScope(sessionId);
                    sessionGrants.prepare(sessionId, req.mode(), scope);
                    // 全确认和带风险标签的强制确认不能被记住授权绕过。
                    boolean canRemember = "write_confirm".equals(req.mode()) && tagNames.isEmpty();
                    // 会话级授权短路：用户此前勾选「本会话相同操作不再询问」且参数完全一致 → 直接放行，
                    // 不再推 confirmation_request 卡片（审计决议仍由 ToolExecutor 按 USER 批准落账）。
                    if (canRemember && sessionGrants.isGranted(sessionId, req.mode(), scope, toolName, input)) {
                        recorder.confirmResolved(confirmId, toolName,
                                "[本会话已授权，自动放行] " + message, tagNames, input, true);
                        return ToolContext.UserConfirmResult.decided(true, true);
                    }
                    if (live) {
                        try {
                            send(emitter, AgentEventJson.toJson(new AgentEvent.ConfirmationRequestEvent(
                                    confirmId, toolName, message, tagNames, input)));
                        } catch (Exception e) {
                            aborted.set(true); // v1：推送时连接已断 → 按既有语义中止
                        }
                    }
                    PendingInteractionHub.CompletionState st =
                            interactionHub.awaitSessionConfirm(confirmId, aborted::get);
                    if (st.interrupted()) {
                        LOG.log(System.Logger.Level.WARNING,
                                "交互等待超时，按拒绝闭合：session=" + sessionId
                                        + " tool=" + toolName + " confirmId=" + confirmId);
                        recorder.confirmResolved(confirmId, toolName,
                                "[等待超时，自动拒绝] " + message, tagNames, input, false);
                        return ToolContext.UserConfirmResult.timedOut();
                    }
                    boolean scopeUnchanged = scope.equals(workspaceScope(sessionId));
                    boolean approved = st.approved() && scopeUnchanged;
                    if (!scopeUnchanged) message = "[项目已变更，本次批准失效] " + message;
                    // 批准且勾选记住：登记为会话级授权，后续完全相同的命令/参数不再询问
                    boolean remembered = approved && st.remembered() && canRemember;
                    if (remembered) {
                        sessionGrants.grant(sessionId, req.mode(), scope, toolName, input);
                    }
                    // 决议落进 assistant parts，历史重放时还原为静态完成行（不可再操作）
                    recorder.confirmResolved(confirmId, toolName, message, tagNames, input, approved);
                    return ToolContext.UserConfirmResult.decided(approved, remembered);
                }
            });
            ctx.askUser(new ToolContext.AskUserCallbackEx() {
                @Override
                public ToolContext.AskUserResult askUserEx(
                        String askId, String question, String hint, List<String> options) {
                    if (live) {
                        try {
                            send(emitter, AgentEventJson.toJson(
                                    new AgentEvent.AskUserEvent(askId, question, hint, options)));
                        } catch (Exception e) {
                            aborted.set(true);
                        }
                    }
                    PendingInteractionHub.CompletionState st =
                            interactionHub.awaitSessionAsk(askId, aborted::get);
                    if (st.interrupted()) {
                        LOG.log(System.Logger.Level.WARNING,
                                "询问等待超时，按跳过闭合：session=" + sessionId
                                        + " askId=" + askId);
                        recorder.askResolved(askId, question, hint, options, null);
                        return ToolContext.AskUserResult.timedOut();
                    }
                    String answer = st.answer();
                    recorder.askResolved(askId, question, hint, options, answer);
                    return ToolContext.AskUserResult.answered(answer);
                }
            });

            // 主机快照在本轮开始时一次生成，注入主循环环境信息段。
            // 快照可能过期，需要最新状态时仍可调 list_hosts 刷新。
            String hostSnapshot = buildHostSnapshot();

            // 部署模式（用户在前端显式开启）：注入一键部署流水线指令，驱动 AI 走标准 7 阶段并同步阶段卡片。
            // 仅绑定项目不算部署模式——普通问答（看代码/改配置）不应被部署提示词污染。
            boolean deployMode = Boolean.TRUE.equals(req.deploy());
            String systemSupplement = deployMode ? DEPLOY_SUPPLEMENT : null;

            // 中途插话供应器：AgentLoop 步边界 drain，多条指示合并成一段返回
            ConcurrentLinkedQueue<String> interjectionQueue = interjectionQueues.get(sessionId);
            // 模型目录中配置的上下文窗口/最大输出透传给 AgentLoop：
            // 上下文窗口决定 ContextCompactor 的压缩阈值（缺省 128K，对 32K 小窗口模型会失效）；
            // 最大输出覆盖应用级默认（TruncationHandler 与 LLM 请求共用）。
            Integer catalogMaxOutput = catalog != null ? catalog.maxTokens() : null;
            Integer maxOutputTokens = catalogMaxOutput != null ? catalogMaxOutput : props.getMaxOutputTokens();
            AgentLoopOptions options = new AgentLoopOptions()
                    .aborted(aborted::get)
                    .toolContext(ctx)
                    .maxSteps(props.getMaxSteps())
                    .maxOutputTokens(maxOutputTokens)
                    .contextWindowTokens(catalog == null ? null : catalog.contextWindow())
                    .systemSupplement(systemSupplement)
                    .envSupplement(hostSnapshot)
                    .interjections(() -> drainInterjections(interjectionQueue))
                    // 事件溯源双写：turn/start 已在上面发，AgentLoop 负责 request/header、
                    // step、chunk、assistant/message、tool/*、interaction/*、turn/end。
                    .eventLog(eventLog)
                    .turnNo(turnNo)
                    .mode(req.mode());

            if (live) {
                send(emitter, Json.object().put("type", "start").put("sessionId", sessionId));
            }

            // ---------- 上下文注入：按 dsh 风格发射一组「<name>」行 ----------
            // 仅用于前端时间线展示（Tool / System prompt 的"被纳入考虑"清单），
            // 真实内容由 SystemPromptBuilder.buildSystemPrompt() 拼进 system message。
            // 运维助手定位：不展示本地工作目录/操作系统等编码环境信息。
            // 三路径一致：① 事件日志 context/injected（v2 实时 + 两路径冷恢复的正式来源）
            // ② recorder（旧 messages 表历史基线）；v1 额外 ③ SSE ContextInjectedEvent。
            List<String> toolNames = toolRegistry.list().stream()
                    .map(t -> t.name())
                    .toList();
            List<AgentEvent.ContextInjectedEvent> contextItems = new ArrayList<>();
            contextItems.add(new AgentEvent.ContextInjectedEvent(
                    "内置工具集",
                    toolNames.isEmpty() ? "(无)" : String.join(", ", toolNames)));
            contextItems.add(new AgentEvent.ContextInjectedEvent(
                    "系统提示",
                    "Aye 运维助手 · 工具优先原则"));
            for (AgentEvent.ContextInjectedEvent ci : contextItems) {
                eventLog.contextInjected(turnNo, ci.name(), ci.summary());
                recorder.accept(ci);
                if (live) {
                    send(emitter, AgentEventJson.toJson(ci));
                }
            }

            AgentLoop.agentLoop(messages, provider, toolRegistry, options, sink);

            // turn/end 已由 AgentLoop 的 finally 保证发出；结束前等事件 durable，
            // 客户端立刻断线/刷新也能从磁盘恢复完整现场。超时不能静默：告警并强制再刷一次，
            // 避免存储持续故障时宣布完成而事实未落盘（v2 无 SSE，同样必须等齐）。
            if (!eventManager.awaitDurable(sessionId, eventLog.lastSeq(), 10_000L)) {
                LOG.log(System.Logger.Level.WARNING,
                        "事件落盘等待超时（10s），尝试强制刷盘：session=" + sessionId
                                + " seq=" + eventLog.lastSeq());
                eventManager.flushNow(sessionId);
            }

            // 重建并落库本轮 assistant / tool 消息（旧 messages 表过渡投影）
            recorder.finish();
            for (UIMessage m : recorder.messages()) {
                sessionStore.appendMessage(sessionId, m);
            }

            if (!live) {
                LOG.log(System.Logger.Level.INFO,
                        "v2 agent turn finished: session=" + sessionId + " turn=" + turnNo);
            }
            completeEmitter(emitter);
        } catch (Throwable t) {
            // 闭合可能的孤儿 turn：AgentLoop try 块之前（system prompt 构建、消息转换、
            // request/header）或其启动前的 SQLite/SSE 失败都不会走 AgentLoop 的 finally。
            // AgentLoop 自身已闭合（lastTurnEndReason 非空）时不重复发。
            closeOpenTurn(eventLog, turnStarted, turnNo, turnModelId, req.mode(), sessionId, t);
            pushError(emitter,
                    "服务端异常：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            completeEmitter(emitter);
        } finally {
            // 释放同会话互斥锁，允许该会话后续再次发起对话；
            // markInactive 会同步 flush 本会话事件（异常路径也覆盖），随后允许 LRU 淘汰。
            localAttachments.clear(sessionId);
            try {
                eventManager.markInactive(sessionId);
            } catch (RuntimeException ignored) { }
            runningSessions.remove(sessionId, handle);
            interjectionQueues.remove(sessionId);
        }
    }

    /** 下一个 turn 序号：日志中最大 turn 坐标 +1（冷恢复/跨重启连续）。 */
    private static int nextTurnNo(SessionEventLog log) {
        int max = 0;
        for (SessionEvent e : log.events()) {
            if (e.turnNo() > max) max = e.turnNo();
        }
        return max + 1;
    }

    /**
     * 异常路径补偿：turn/start 已发出但当前 turn 没有终态时补发 turn/end(error)，
     * 防止 session_turns 投影永久 running（缓存命中不会重跑冷恢复闭合，必须在此显式补）。
     * AgentLoop 的 finally 已闭合时（lastTurnEndReason 非空）不重复发射。
     */
    private void closeOpenTurn(SessionEventLog eventLog, boolean turnStarted, int turnNo,
                               String modelId, String mode, String sessionId, Throwable t) {
        if (!turnStarted || eventLog == null || eventLog.lastTurnEndReason() != null) return;
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        try {
            eventLog.turnEnded(turnNo, SessionEventType.REASON_ERROR, modelId, mode, msg);
            eventManager.flushNow(sessionId);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "补发 turn/end 失败：session=" + sessionId, ex);
        }
    }

    /**
     * 中途插话：Agent 运行期间把用户指示入队，AgentLoop 在下一个步边界 drain 生效
     * （协作式，不打断进行中的流式/工具执行）。事件侧由 AgentLoop drain 时发
     * user/message(source=inject)，是跨轮次上下文连续的正式来源；
     * 旧 messages 表仅为过渡投影补写一份，前端切换事件引擎后可移除。
     *
     * @return true=已入队；false=该会话当前没有运行中的任务（调用方应提示用户）
     */
    public boolean interject(String sessionId, String text) {
        if (sessionId == null || sessionId.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("sessionId 和 text 不能为空");
        }
        ConcurrentLinkedQueue<String> queue = interjectionQueues.get(sessionId);
        if (queue == null) return false;
        queue.add(text.strip());
        try {
            sessionStore.appendMessage(sessionId,
                    new UIMessage("user", List.of(new UIPart.TextPart(text.strip()))));
        } catch (RuntimeException ignored) {
            // 过渡投影落库失败不影响插话生效（事件日志与本轮上下文不受影响）
        }
        return true;
    }

    /** drain 插话队列：多条指示按到达顺序合并成一段，无则返回 null。 */
    private static String drainInterjections(ConcurrentLinkedQueue<String> queue) {
        if (queue == null || queue.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        String item;
        while ((item = queue.poll()) != null) {
            if (sb.length() > 0) sb.append("\n---\n");
            sb.append(item);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** provider 配置 + 命中的目录模型项（目录未收录该模型时 catalog 为 null）。 */
    private record ResolvedModel(LlmProviderConfig config, CatalogModel catalog) {}

    private LlmProviderConfig resolveProvider(String providerId, String modelId) {
        return resolveModel(providerId, modelId).config();
    }

    /**
     * 解析厂商配置与目录模型。
     * provider + modelId 都指定时用指定值，否则落到全局默认模型；
     * 完全没有配置时兜底到 application.yml 的默认值（catalog 为 null）。
     */
    private ResolvedModel resolveModel(String providerId, String modelId) {
        Provider p = null;
        String mid = modelId;
        if (providerId != null && !providerId.isBlank() && mid != null && !mid.isBlank()) {
            p = modelStore.get(providerId);
        } else {
            DefaultRef d = modelStore.getDefaultRef();
            if (d != null) {
                p = modelStore.get(d.providerId());
                mid = d.modelId();
            }
        }
        if (p == null) {
            // 兜底：无模型配置时用 application.yml 里的默认值
            return new ResolvedModel(LlmProviderConfig.builder(
                    props.getDefaultModel(), props.getDefaultBaseUrl(), props.getDefaultApiKey())
                    .build(), null);
        }
        String baseUrl = (p.baseUrl() == null || p.baseUrl().isBlank())
                ? vendorBaseUrl(p.id(), props.getDefaultBaseUrl()) : p.baseUrl();
        String apiKey = (p.apiKey() == null || p.apiKey().isBlank()) ? props.getDefaultApiKey() : p.apiKey();
        LlmProviderConfig config = LlmProviderConfig.builder(mid, baseUrl, apiKey)
                .authHeaderKind(providerToAuthKind(p.id()))
                .build();
        // 命中目录模型（contextWindow / maxTokens 由此进入压缩器与输出预算）；
        // 目录里没有的临时模型 id 返回 null，由调用方走默认值。
        String finalMid = mid;
        CatalogModel catalog = p.models().stream()
                .filter(m -> m.modelId().equals(finalMid))
                .findFirst().orElse(null);
        return new ResolvedModel(config, catalog);
    }

    private static String vendorBaseUrl(String providerId, String fallback) {
        ModelVendorProfiles.Vendor v = ModelVendorProfiles.get(providerId);
        return (v == null || v.defaultBaseUrl() == null || v.defaultBaseUrl().isBlank()) ? fallback : v.defaultBaseUrl();
    }

    private static AuthHeaderKind providerToAuthKind(String provider) {
        if ("anthropic".equalsIgnoreCase(provider)) return AuthHeaderKind.X_API_KEY;
        return AuthHeaderKind.AUTHORIZATION_BEARER; // openai 及一切 OpenAI 兼容厂商
    }

    /**
     * 渲染本会话主机快照（host_key/名称/地址/认证方式/连接可用性），注入系统提示词环境段。
     * 状态判定口径与 {@link com.aye.server.ssh.ListHostsTool} 保持一致；
     * 无配置主机时返回 null（不占 token）。快照为轮次开始时的状态，可能已过期。
     */
    private String buildHostSnapshot() {
        List<Map<String, Object>> hosts = hostStore.findAll();
        if (hosts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("本会话主机快照（")
                .append(hosts.size())
                .append(" 台，为轮次开始时状态，可能已过期；需要最新状态请调 list_hosts）：");
        for (Map<String, Object> h : hosts) {
            long id = ((Number) h.get("id")).longValue();
            String connectivity;
            if (sshSessionManager.findByHostId(id) != null) {
                connectivity = "[已连接]";
            } else if ("PRIVATE_KEY".equals(h.get("authType"))
                    && h.get("privateKeyPath") != null
                    && !h.get("privateKeyPath").toString().isBlank()) {
                connectivity = "[可自动建连]";
            } else {
                connectivity = "[需用户先连接]";
            }
            sb.append("\n- host_key=").append(h.get("hostKey"))
                    .append("  ").append(h.get("name"))
                    .append("  ").append(h.get("username"))
                    .append('@').append(h.get("address"))
                    .append(':').append(h.get("port"))
                    .append("  (").append(h.get("authType")).append(")  ")
                    .append(connectivity);
        }
        return sb.toString();
    }

    private TrustLevel parseTrustLevel(String mode) {
        return "all_confirm".equals(mode) ? TrustLevel.PARANOID : TrustLevel.STANDARD;
    }

    /** 在任务启动前解析并校验模式，避免缺省模式绕过用户保存的安全设置。 */
    private ChatRequest resolveRequest(ChatRequest req) {
        String mode = req.mode();
        if (mode == null || mode.isBlank()) mode = settingsStore.getExecutionMode();
        if (!java.util.Set.of("readonly", "write_confirm", "all_confirm").contains(mode)) {
            throw new IllegalArgumentException("无效的执行模式：" + mode);
        }
        return new ChatRequest(req.sessionId(), req.message(), req.provider(), req.modelId(),
                req.hostId(), mode, req.attachments(), req.workspaceId(), req.deploy());
    }

    private String workspaceScope(String sessionId) {
        WorkspaceStore.Workspace ws = workspaceStore.boundWorkspace(sessionId);
        return ws == null ? "" : ws.id() + ":" + ws.canonicalPath();
    }

    private static void send(SseEmitter emitter, JsonNode node) throws Exception {
        emitter.send(SseEmitter.event().name("message").data(node, MediaType.APPLICATION_JSON));
    }

    /** v2（emitter=null）时所有 SSE 触点为 no-op；v1 发送 error 事件，失败静默。 */
    private static void pushError(SseEmitter emitter, String message) {
        if (emitter == null) return;
        try {
            send(emitter, Json.object().put("type", "error").put("message", message));
        } catch (Exception ignored) {
        }
    }

    private static void completeEmitter(SseEmitter emitter) {
        if (emitter == null) return;
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    /**
     * 安全分级仅负责硬拒绝危险本机命令，不能替代用户的写操作审批。
     */
    private boolean isLocalCommandDenied(String toolName, JsonNode input) {
        return "run_local_async".equals(toolName)
                && commandClassifier.classify(input.path("command").asText(""))
                == LocalCommandClassifier.Level.DENY;
    }

    private static String buildUserText(ChatRequest req) {
        String text = req.message() == null ? "" : req.message();
        List<ChatAttachment> atts = req.attachments();
        if (atts == null || atts.isEmpty()) return text;

        StringBuilder sb = new StringBuilder(text);
        sb.append("\n\n## 本轮附带的本地文件（需要内容时必须调用 read_local_file 按需读取，不要凭空猜测其内容）：");
        for (ChatAttachment att : atts) {
            if (att.path() == null || att.path().isBlank()) continue;
            String label = (att.name() == null || att.name().isBlank()) ? att.path() : att.name();
            sb.append("\n- ").append(att.isDirectory() ? "[目录] " : "[文件] ")
                    .append(label).append(" → ").append(att.path());
        }
        return sb.toString();
    }
}
