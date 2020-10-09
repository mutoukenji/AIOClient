package tech.yaog.utils.aioclient;

public abstract class AbstractSplitter {

    public interface Callback {
        void newFrame(int length, int skip);
    }

    protected Callback callback;

    public abstract void split(byte[] raw);

}
