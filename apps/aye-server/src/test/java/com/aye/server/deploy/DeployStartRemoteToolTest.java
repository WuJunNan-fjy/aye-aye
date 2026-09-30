package com.aye.server.deploy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 启动外壳构造：保证 run.pid 记录应用自身 PID。 */
class DeployStartRemoteToolTest {

    @Test
    void ampersandBindsOnlyToNohup() {
        String cmd = DeployStartRemoteTool.buildStartCommand(
                "/root/aye-apps/mini-site", "python3 -m http.server 18080");

        // cd 与后续逻辑之间必须是 ;（不是 &&），这样 & 只作用于 nohup
        assertTrue(cmd.contains("cd '/root/aye-apps/mini-site/current';"), cmd);
        // 启动前必须先检查旧 pid，存活则拒绝重复启动
        assertTrue(cmd.contains("kill -0 \"$OLD\""), cmd);
        assertTrue(cmd.contains("already_running pid=$OLD"), cmd);
        // $! 紧跟 nohup 后台任务
        assertTrue(cmd.contains("2>&1 & echo $! > '/root/aye-apps/mini-site/run.pid'"), cmd);
        assertTrue(cmd.endsWith("echo started pid=$(cat '/root/aye-apps/mini-site/run.pid')"), cmd);
    }

    @Test
    void javaJarShapePreserved() {
        String cmd = DeployStartRemoteTool.buildStartCommand(
                "/root/aye-apps/demo", "java -jar /root/aye-apps/demo/current/app.jar");
        // 日志必须追加（>>），不能覆盖（>）历史 console.log
        assertTrue(cmd.contains(
                "nohup java -jar /root/aye-apps/demo/current/app.jar >>"), cmd);
        assertTrue(cmd.contains(">> '/root/aye-apps/demo/console.log'"), cmd);
    }
}
