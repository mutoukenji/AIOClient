package tech.yaog.utils.aioclient.io;

import java.net.InetAddress;

public abstract class IO {

    public interface Callback {
        void onReceived(byte[] data);
        void onConnected();
        void onDisconnected();
        void onException(Throwable t);
    }

    public abstract boolean connect(String remote);
    public abstract void disconnect();
    public abstract void beginRead();
    public abstract void stopRead();
    public abstract void write(byte[] bytes);

    protected Callback callback;
    protected boolean keepAlive;
    protected int connTimeout = 0;

    public IO(Callback callback) {
        this.callback = callback;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public int getConnTimeout() {
        return connTimeout;
    }

    public void setConnTimeout(int connTimeout) {
        this.connTimeout = connTimeout;
    }
}
