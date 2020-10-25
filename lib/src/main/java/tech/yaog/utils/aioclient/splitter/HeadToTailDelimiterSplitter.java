package tech.yaog.utils.aioclient.splitter;

import tech.yaog.utils.aioclient.AbstractSplitter;

public class HeadToTailDelimiterSplitter extends AbstractSplitter {

    private byte[] head;
    private byte[] tail;

    public HeadToTailDelimiterSplitter(byte[] head, byte[] tail) {
        this.head = head;
        this.tail = tail;
    }

    @Override
    public void split(byte[] raw) {
        int length = raw.length;
        int headLength = head.length;
        int tailLength = tail.length;
        if (length < headLength + tailLength) {
            return;
        }
        int start = -1;
        for (int i = 0;i<=length - headLength - tailLength;i++) {
            if (equals(raw, i, head, 0, headLength)) {
                start = i;
                break;
            }
        }
        if (start == 0) {
            for (int i = start + headLength;i<=length - tailLength;i++) {
                if (equals(raw, i, tail, 0, tailLength)) {
                    callback.newFrame(headLength, i - headLength, tailLength);
                    break;
                }
            }
        }
        else if (start > 0) {
            // 丢掉多余的杂讯
            callback.newFrame(0, start);
        }
        else {
            // 留足头部空间，防止头部标识断包的可能性
            callback.newFrame(0, length - (headLength - 1));
        }
    }

    private boolean equals(byte[] l, int offset, byte[] r, int offsetR, int length) {
        if (offset+length > l.length || offsetR + length > r.length) {
            return false;
        }
        for (int i = 0;i<length;i++) {
            if (l[offset+i] != r[offsetR+i]) {
                return false;
            }
        }
        return true;
    }
}
