package tech.yaog.utils.aioclient.splitter;

import tech.yaog.utils.aioclient.AbstractSplitter;

public class DelimiterSplitter extends AbstractSplitter {

    private byte[] delimiter;

    public DelimiterSplitter(byte[] delimiter) {
        this.delimiter = delimiter;
    }

    @Override
    public void split(byte[] raw) {
        int length = raw.length;
        int delimiterLength = delimiter.length;
        if (length < delimiterLength) {
            return;
        }
        for (int i = 0;i<length - delimiterLength;i++) {
            if (equals(raw, i, delimiter, 0, delimiterLength)) {
                callback.newFrame(i, delimiterLength);
                return;
            }
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
