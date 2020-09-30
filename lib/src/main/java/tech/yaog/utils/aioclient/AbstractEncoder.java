package tech.yaog.utils.aioclient;

public abstract class AbstractEncoder<T> {
    public abstract byte[] encode(T msg);
}
