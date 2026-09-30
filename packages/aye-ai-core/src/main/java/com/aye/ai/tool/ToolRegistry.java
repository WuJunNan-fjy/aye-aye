package com.aye.ai.tool;

import com.aye.ai.llm.LlmTypes.LlmToolDefinition;
import com.aye.ai.llm.LlmTypes.LlmToolDefinition.Function;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表（对应 DeepWork 的 {@code ToolRegistry}）。
 *
 * <p>注册幂等：同名且同一实例静默跳过；同名但不同实例抛错（防止业务代码覆盖工具）。
 * 输出 OpenAI 原生 {@code tools} 数组（LLMToolDefinition[]）。
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** 注册工具 */
    public void register(Tool def) {
        if (def.category() == null) {
            throw new IllegalStateException("工具 \"" + def.name()
                    + "\" 未申报 ToolCategory（分类注册表缺类别）。请实现 category() 后注册，"
                    + "否则权限引擎无法做 fail-closed 决策。");
        }
        Tool existing = tools.get(def.name());
        if (existing != null) {
            if (existing == def) return; // 幂等
            throw new IllegalStateException("工具 \"" + def.name() + "\" 已注册，不能重复注册（新旧定义不是同一对象）");
        }
        tools.put(def.name(), def);
    }

    /** 注销工具 */
    public boolean unregister(String name) {
        return tools.remove(name) != null;
    }

    /** 按名称查找工具 */
    public Tool find(String name) {
        return tools.get(name);
    }

    /** 列出所有工具（浅拷贝） */
    public List<Tool> list() {
        return new ArrayList<>(tools.values());
    }

    /** 已注册工具数量 */
    public int size() {
        return tools.size();
    }

    /** 输出 LLM 协议层的工具说明书数组（每次重新生成，支持运行时增删） */
    public List<LlmToolDefinition> toToolDefinitions() {
        List<LlmToolDefinition> out = new ArrayList<>();
        for (Tool def : tools.values()) {
            out.add(definitionFromTool(def));
        }
        return out;
    }

    /** 工具定义 → LLM 协议格式 */
    private static LlmToolDefinition definitionFromTool(Tool def) {
        Function fn = new Function(def.name(), def.description(), def.inputSchema());
        return new LlmToolDefinition("function", fn);
    }
}
