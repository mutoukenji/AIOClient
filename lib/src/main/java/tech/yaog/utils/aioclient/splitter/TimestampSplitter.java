package tech.yaog.utils.aioclient.splitter;

import tech.yaog.utils.aioclient.AbstractSplitter;

public class TimestampSplitter extends AbstractSplitter {

    private long lastPacket = -1;
    private long interval = 10;
    private int currentLength = 0;

    private Thread worker = new Thread() {
        @Override
        public void run() {
            long checkInterval = interval * 1000000L;
            while (!Thread.interrupted()) {
                int currentLength;
                synchronized (TimestampSplitter.this) {
                    currentLength = TimestampSplitter.this.currentLength;
                }
                if (currentLength > 0) {
                    long lastPacket;
                    synchronized (TimestampSplitter.this) {
                        lastPacket = TimestampSplitter.this.lastPacket;
                    }
                    if (lastPacket >= 0) {
                        long now = System.nanoTime();
                        long past = now - lastPacket;
                        if (past >= checkInterval) {
                            callback.newFrame(currentLength, 0);
                            synchronized (TimestampSplitter.this) {
                                TimestampSplitter.this.lastPacket = 0;
                                TimestampSplitter.this.currentLength = 0;
                            }
                        }
                    }
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    public TimestampSplitter(long interval) {
        this.interval = interval;
        worker.setName("TimestampSplitter");
        worker.start();
    }

    @Override
    public void split(byte[] raw) {
        int length = raw.length;
        int oldLength;
        synchronized (this) {
            oldLength = currentLength;
        }
        if (length > oldLength) {
            long now = System.nanoTime();
            synchronized (this) {
                this.currentLength = length;
                this.lastPacket = now;
            }
        }
    }
}
