package com.aye.server.deploy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 有界进程输出：保留开头 256KB + 结尾 64KB（环形），总字节照常计数。
 *
 * <p>结构与 ssh 包 SshJobManager 的内部实现一致，供 LocalJobManager 使用。
 */
final class BoundedOutput {

    static final int HEAD_CAP = 256 * 1024;
    static final int TAIL_CAP = 64 * 1024;

    private final ByteArrayOutputStream head = new ByteArrayOutputStream();
    private final byte[] tail = new byte[TAIL_CAP];
    private int tailPos;
    private boolean tailWrapped;
    private long total;

    synchronized void append(byte[] b, int off, int len) {
        total += len;
        int room = HEAD_CAP - head.size();
        if (room > 0) {
            int n = Math.min(room, len);
            head.write(b, off, n);
            off += n;
            len -= n;
        }
        for (int i = 0; i < len; i++) {
            tail[tailPos] = b[off + i];
            tailPos++;
            if (tailPos == tail.length) {
                tailPos = 0;
                tailWrapped = true;
            }
        }
    }

    synchronized boolean truncated() {
        return total > HEAD_CAP;
    }

    /** 完整渲染（head + 省略标记 + tail）。 */
    synchronized String renderAll() {
        String h = head.toString(StandardCharsets.UTF_8);
        if (total <= HEAD_CAP) return h;
        byte[] tailBytes;
        if (!tailWrapped) {
            tailBytes = new byte[tailPos];
            System.arraycopy(tail, 0, tailBytes, 0, tailPos);
        } else {
            tailBytes = new byte[tail.length];
            System.arraycopy(tail, tailPos, tailBytes, 0, tail.length - tailPos);
            System.arraycopy(tail, 0, tailBytes, tail.length - tailPos, tailPos);
        }
        long omitted = total - HEAD_CAP - tailBytes.length;
        return h + "\n…[输出过大，省略中间 " + Math.max(0, omitted)
                + " 字节；以下为最后 64KB]…\n"
                + new String(tailBytes, StandardCharsets.UTF_8);
    }

    /** 轮询渲染：不超过 maxChars，超限只留最后 maxChars。 */
    synchronized String renderTail(int maxChars) {
        String all = renderAll();
        if (all.length() <= maxChars) return all;
        String t = all.substring(all.length() - maxChars);
        return "…[仅显示最后 " + maxChars + " 字符，完整输出共 " + total + " 字节]…\n" + t;
    }
}
