package tech.yaog.utils.aioclient;

public abstract class AbstractSplitter {

    public interface Callback {
        void newFrame(int offset, int length, int skip);
        void newFrame(int length, int skip);
        void newFrame(int length);
    }

    protected Callback callback;

    public abstract void split(byte[] raw);

}
