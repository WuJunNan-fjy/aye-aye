package com.aye.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.aye.ai.audit.AuditDecision;
import com.aye.ai.audit.AuditLedger;
import com.aye.ai.audit.AuditRecord;
import com.aye.ai.audit.ExecutionResultForAudit;
import com.aye.ai.llm.LlmTypes.LlmMessage;
import com.aye.ai.llm.LlmTypes.LlmToolCall;
import com.aye.ai.permission.PermissionDecision;
import com.aye.ai.permission.PermissionEngine;
import com.aye.ai.tools.AskUserTool;
import com.aye.ai.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具执行器（对应 DeepWork 的 {@code tool-executor.ts}）。
 *
 * <p>职责：1. 按工具名查找  2. JSON.parse + JSON Schema 校验  3. 带超时控制执行
 * 4. 所有异常（未知工具/校验失败/执行报错/超时）都吃成错误结果，不向外抛。
 */
public final class ToolExecutor {

    private ToolExecutor() {}

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(0);
    /**
     * 有界工具执行线程池：避免 cachedThreadPool 在并发会话 + 挂起工具时无界增长线程。
     * 队列满载且线程到上限时用 CallerRunsPolicy 在调用方线程上执行，保证 LLM 期望的执行不丢。
     * 注意：工具执行在线程池里时，等待用户确认的交互发生在主循环线程（不在本池）。
     */
    private static final int CORE_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final int MAX_THREADS = Math.max(4, CORE_THREADS * 2);
    private static final ExecutorService POOL = new ThreadPoolExecutor(
            CORE_THREADS, MAX_THREADS, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "aye-tool-" + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * 执行一轮工具调用：按工具声明的 {@code isConcurrencySafe} 分区（参考 Claude Code
     * partitionToolCalls）——
     * <ul>
     *   <li>连续的「并发安全」工具（只读、无交互阻塞）提交线程池<b>并行</b>执行；</li>
     *   <li>非安全工具（写/执行/交互类，或未知工具）在主循环线程<b>串行</b>执行。</li>
     * </ul>
     * 结果严格按 LLM 给出的调用顺序回灌：并行只缩短墙钟时间，不改变消息顺序，
     * 也保证「写后读」这类顺序语义（写工具会切断并行批）。
     */
    public static List<ToolResult> executeToolCalls(List<LlmToolCall> toolCalls, ToolRegistry registry, ToolContext ctx) {
        ToolResult[] results = new ToolResult[toolCalls.size()];
        int i = 0;
        while (i < toolCalls.size()) {
            LlmToolCall call = toolCalls.get(i);
            Tool def = registry.find(call.name());
            // 非安全工具（或未知工具）：主循环线程串行执行，保留「人工确认阻塞在主循环线程」的语义
            if (def == null || !def.isConcurrencySafe()) {
                results[i] = runToolCall(call, registry, ctx, false);
                i++;
                continue;
            }
            // 收集连续的安全工具，组成一个并行批
            int batchStart = i;
            while (i < toolCalls.size()) {
                Tool d = registry.find(toolCalls.get(i).name());
                if (d == null || !d.isConcurrencySafe()) break;
                i++;
            }
            int batchEnd = i;

            List<Future<ToolResult>> futures = new ArrayList<>(batchEnd - batchStart);
            for (int k = batchStart; k < batchEnd; k++) {
                final LlmToolCall c = toolCalls.get(k);
                // 池满时 CallerRunsPolicy 会在主循环线程直接跑 = 自动降级为串行，不会丢任务
                futures.add(POOL.submit(() -> runToolCall(c, registry, ctx, true)));
            }
            // 按调用顺序收结果：单个工具超时/异常只影响自身，不拖累同批其它工具
            for (int k = batchStart; k < batchEnd; k++) {
                LlmToolCall c = toolCalls.get(k);
                long timeoutMs = registry.find(c.name()).timeoutMs();
                try {
                    results[k] = futures.get(k - batchStart).get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    futures.get(k - batchStart).cancel(true);
                    results[k] = makeErrorResult(c, c.name(), "工具执行超时（" + timeoutMs + "ms）");
                } catch (Exception e) {
                    results[k] = makeErrorResult(c, c.name(), unwrapMessage(e));
                }
            }
        }
        return Arrays.asList(results);
    }

    /**
     * 单个工具调用的完整处理：查找 → 解析校验 → 权限检查（含人工确认）→ 执行 → 审计收尾。
     * 任何异常都吃成错误结果，不向外抛。
     *
     * @param inlineExecute true=调用方已运行在线程池工作线程上（并行批任务），{@code def.execute}
     *                      直接内联执行，超时由外层 {@code future.get + cancel} 控制；
     *                      false=运行在主循环线程上（串行路径），{@code def.execute} 仍提交线程池
     *                      并限时等待，保证人工确认的阻塞发生在主循环线程而非池线程。
     */
    private static ToolResult runToolCall(LlmToolCall call, ToolRegistry registry, ToolContext ctx,
                                          boolean inlineExecute) {
        String toolName = call.name();

        if (ctx != null && ctx.isAborted()) {
            return makeErrorResult(call, toolName, "请求已取消（用户断开连接）");
        }

        Tool def = registry.find(toolName);
        if (def == null) {
            return makeErrorResult(call, toolName, "未知工具：" + toolName);
        }
        long timeoutMs = def.timeoutMs();

        // ---------- 解析 + 校验参数 ----------
        JsonNode parsedInput;
        try {
            parsedInput = parseAndValidateArgs(call.arguments(), def.inputSchema());
        } catch (Exception e) {
            return makeErrorResult(call, toolName, e.getMessage());
        }

        // ---------- 权限检查（PermissionEngine 接线） ----------
        // ask_user 本身即交互通道，无文件/命令副作用：
        // 跳过权限引擎，避免 all_confirm 模式下对它也弹一次确认（双重打扰、体验割裂）。
        boolean isInteractionTool = def instanceof AskUserTool;
        String auditRecordId = null;
        if (ctx != null && !isInteractionTool) {
            PermissionDecision decision;
            try {
                PermissionEngine.BuiltContext built = PermissionEngine.buildPermissionContextAndHash(
                        def, parsedInput, ctx, null, null);
                decision = PermissionEngine.evaluatePermission(built.ctx());

                AuditLedger audit = ctx.audit();
                if (audit != null) {
                    auditRecordId = audit.recordStart(ctx.effectiveSessionId(), toolName, built.inputSha256(),
                            parsedInput,
                            built.ctx().resolvedPaths(), built.ctx().destructiveHints());
                    AuditDecision ad;
                    if (decision instanceof PermissionDecision.Allow) {
                        ad = AuditDecision.ALLOW;
                    } else if (decision instanceof PermissionDecision.Deny) {
                        ad = AuditDecision.DENY;
                    } else {
                        ad = AuditDecision.CONFIRM_PENDING;
                    }
                    audit.markDecision(auditRecordId, ad, AuditRecord.DecidedBy.SYSTEM_RULE);
                }
            } catch (Exception e) {
                // 权限引擎自身异常：不放行，按保守确认处理
                decision = new PermissionDecision.Confirm("权限检查失败：" + unwrapMessage(e), List.of(), null);
            }

            if (decision instanceof PermissionDecision.Deny d) {
                notifyAutoDecision(ctx, call.id(), "deny", d.reason());
                closeAuditRecord(ctx, auditRecordId, false, d.reason());
                return makeErrorResult(call, toolName, "操作被拒绝：" + d.reason());
            }
            if (decision instanceof PermissionDecision.Allow) {
                notifyAutoDecision(ctx, call.id(), "allow", null);
            }
            if (decision instanceof PermissionDecision.Confirm c) {
                // 交互确认通道：若环境配置了 waitForUserConfirm（SSE + REST 返回通道），
                // 则发出 ConfirmationRequestEvent 并阻塞等待用户批准/拒绝后继续执行，
                // 而不是像早期版本那样一律保守拒绝——这正是「审批/人工确认」与主循环的整合点。
                ToolContext.UserConfirmCallback confirmCb = ctx.waitForUserConfirm();
                String confirmId = "cf-" + UUID.randomUUID();
                boolean approved;
                if (confirmCb == null) {
                    approved = false;
                } else {
                    try {
                        // 回调实现方（ChatService）负责发出 ConfirmationRequestEvent 并阻塞等用户回传
                        approved = confirmCb.awaitConfirm(call.id(), confirmId, toolName,
                                c.message(), c.tags(), parsedInput);
                    } catch (Exception e) {
                        closeAuditRecord(ctx, auditRecordId, false, "等待人工确认失败：" + unwrapMessage(e));
                        return makeErrorResult(call, toolName, "等待人工确认失败：" + unwrapMessage(e));
                    }
                }
                if (!approved) {
                    String msg = "操作被拒绝（用户未批准确认）：" + c.message();
                    closeAuditRecord(ctx, auditRecordId, false, msg);
                    return makeErrorResult(call, toolName, msg);
                }
                // 批准 → 授权并执行，更新审计决策为 ALLOW
                if (approved && ctx.audit() != null && auditRecordId != null) {
                    ctx.audit().markDecision(auditRecordId, AuditDecision.ALLOW, AuditRecord.DecidedBy.USER);
                }
            }
            // Allow 或 已批准 → 继续执行，auditRecordId 留给执行结果收尾
        }

        // ---------- 执行（带超时） ----------
        ToolContext execCtx = ctx;
        long startNanos = System.nanoTime();
        Future<Object> future = null;
        try {
            Object result;
            if (inlineExecute) {
                // 并行批：当前线程即池工作线程，内联执行；超时/中断由外层 future.get + cancel 负责
                result = def.execute(parsedInput, execCtx);
            } else {
                // 串行路径：主循环线程限时等待池线程执行，人工确认的阻塞留在主循环线程
                future = POOL.submit(() -> def.execute(parsedInput, execCtx));
                try {
                    result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true); // 尽量中断挂起的工具执行，释放线程池线程
                    closeAuditRecord(ctx, auditRecordId, false, "工具执行超时（" + timeoutMs + "ms）", startNanos);
                    return makeErrorResult(call, toolName, "工具执行超时（" + timeoutMs + "ms）");
                }
            }
            String outputText = serializeToolOutputForLLM(result);
            closeAuditRecord(ctx, auditRecordId, true, previewOf(outputText), startNanos);
            return new ToolResult(
                    LlmMessage.tool(call.id(), truncateAndStoreOutput(outputText, ctx)),
                    result,
                    false,
                    toolName);
        } catch (Exception e) {
            // 并行批内联执行被 cancel（中断）或工具自身抛错都落这里
            String msg = unwrapMessage(e);
            closeAuditRecord(ctx, auditRecordId, false, msg, startNanos);
            return makeErrorResult(call, toolName, msg);
        }
    }

    /** 通知事件流自动决议（auto allow/deny）；监听缺失或抛错均不得影响工具执行。 */
    private static void notifyAutoDecision(ToolContext ctx, String callId, String decision, String rule) {
        if (ctx == null || ctx.autoDecisionListener() == null) return;
        try {
            ctx.autoDecisionListener().onAutoDecision(callId, decision, ctx.executionMode(), rule);
        } catch (Exception ignored) {
            // 事件留痕失败不允许反噬执行主路径
        }
    }

    private static void closeAuditRecord(ToolContext ctx, String recordId, boolean success, String message) {
        closeAuditRecord(ctx, recordId, success, message, null);
    }

    private static void closeAuditRecord(ToolContext ctx, String recordId, boolean success, String message, Long startNanos) {
        if (ctx == null || recordId == null || ctx.audit() == null) return;
        Long durationMs = startNanos == null ? null : (System.nanoTime() - startNanos) / 1_000_000;
        ctx.audit().recordEnd(recordId, new ExecutionResultForAudit(success, success ? null : message,
                success ? message : "", durationMs));
    }

    private static String previewOf(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private static JsonNode parseAndValidateArgs(String rawArgs, JsonNode schema) throws Exception {
        String args = (rawArgs == null || rawArgs.trim().isEmpty()) ? "{}" : rawArgs;
        JsonNode parsed = Json.parse(args);
        List<String> errors = JsonSchemaValidator.validate(schema, parsed);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("参数校验失败：" + String.join("; ", errors));
        }
        return parsed;
    }

    /** 把工具的任意返回值序列化成 LLM 能吃的字符串 */
    static String serializeToolOutputForLLM(Object output) {
        if (output == null) return "";
        if (output instanceof String) return (String) output;
        if (output instanceof Number || output instanceof Boolean) return String.valueOf(output);
        return Json.stringify(output);
    }

    // ==========================================================================
    // 工具结果大小上限：超限 → 完整存盘 + 只回摘要（参考 Claude Code maxResultSizeChars）
    // ==========================================================================
    private static final int MAX_TOOL_RESULT_CHARS = 30_000;
    private static final int HEAD_PREVIEW_CHARS = 5_000;
    private static final int TAIL_PREVIEW_CHARS = 3_000;
    private static final long RESULT_FILE_TTL_MS = 24L * 3600 * 1000;

    /**
     * 若工具结果超过 {@link #MAX_TOOL_RESULT_CHARS}，把完整内容写入本地临时文件，
     * 返回「元信息 + 首尾预览 + 存盘路径」的摘要；未超限则原样返回。
     * 存盘失败时回退纯截断（保留前 30k），保证不影响主流程。
     */
    static String truncateAndStoreOutput(String output, ToolContext ctx) {
        if (output == null || output.length() <= MAX_TOOL_RESULT_CHARS) {
            return output == null ? "" : output;
        }
        String storedPath = storeToTempFile(output, ctx);
        String head = output.substring(0, Math.min(HEAD_PREVIEW_CHARS, output.length()));
        String tail = output.substring(Math.max(0, output.length() - TAIL_PREVIEW_CHARS));
        StringBuilder sb = new StringBuilder();
        sb.append("[工具结果共 ").append(output.length()).append(" 字符，超过上限 ")
                .append(MAX_TOOL_RESULT_CHARS).append("，已保存完整内容至：\n  ")
                .append(storedPath)
                .append("\n（本地临时文件，仅供查看；若需继续读取远程主机文件请改用 sftp_read 等工具）]\n开头：")
                .append(head);
        if (output.length() > HEAD_PREVIEW_CHARS + TAIL_PREVIEW_CHARS) {
            sb.append("\n…中间省略…\n结尾：").append(tail);
        }
        return sb.toString();
    }

    /** 写入临时目录并返回绝对路径；失败返回提示文案而非抛异常。 */
    private static String storeToTempFile(String output, ToolContext ctx) {
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "aye-tool-results");
            Files.createDirectories(dir);
            cleanOldResultFiles(dir);
            String session = (ctx != null && ctx.effectiveSessionId() != null)
                    ? ctx.effectiveSessionId() : "unknown";
            String name = session + "-" + System.currentTimeMillis()
                    + "-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
            Path file = dir.resolve(name);
            Files.writeString(file, output, StandardCharsets.UTF_8);
            return file.toAbsolutePath().toString();
        } catch (Exception e) {
            return "<无法写入临时文件：" + unwrapMessage(e) + ">";
        }
    }

    /** 清理超过 {@link #RESULT_FILE_TTL_MS} 的旧结果文件。 */
    private static void cleanOldResultFiles(Path dir) {
        long cutoff = System.currentTimeMillis() - RESULT_FILE_TTL_MS;
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 单个文件清理失败不影响其余
                }
            }
        } catch (Exception ignored) {
            // 目录不可枚举时跳过清理
        }
    }

    private static ToolResult makeErrorResult(LlmToolCall call, String toolName, String message) {
        String errorText = toolName + " 执行失败：" + message;
        return new ToolResult(
                LlmMessage.tool(call.id(), errorText),
                Json.object().put("error", message),
                true,
                toolName);
    }

    private static String unwrapMessage(Exception e) {
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null) return cause.getMessage();
        if (e.getMessage() != null) return e.getMessage();
        return e.getClass().getSimpleName();
    }
}
