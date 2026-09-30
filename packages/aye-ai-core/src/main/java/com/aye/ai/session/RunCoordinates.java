package com.aye.ai.session;

/**
 * 运行期 (turn, step) 坐标的可变持有者。
 *
 * <p>{@code AgentLoop} 每进入一轮 / 一步就更新这里；服务层注入的人工确认 / ask_user
 * 回调在任意工作线程被触发时，通过 ToolContext 上挂载的同一个实例读出当前坐标，
 * 从而把 interaction 事件写到正确的 turn/step 上（回调触发点在工具执行栈深处，
 * 无法通过参数传参）。
 *
 * <p>字段 volatile：并行安全工具在工具池工作线程上触发自动放行回调时也会读取坐标。
 */
public final class RunCoordinates {

    private volatile int turnNo = SessionEvent.NO_COORD;
    private volatile int stepNo = SessionEvent.NO_COORD;

    public int turnNo() {
        return turnNo;
    }

    public int stepNo() {
        return stepNo;
    }

    public RunCoordinates turnNo(int turnNo) {
        this.turnNo = turnNo;
        return this;
    }

    public RunCoordinates stepNo(int stepNo) {
        this.stepNo = stepNo;
        return this;
    }
}
