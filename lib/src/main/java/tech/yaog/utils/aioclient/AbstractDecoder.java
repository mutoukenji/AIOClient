package tech.yaog.utils.aioclient;

public abstract class AbstractDecoder<T> {
    public abstract T decode(byte[] byteBuffer);
}
