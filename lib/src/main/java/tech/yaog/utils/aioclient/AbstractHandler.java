package tech.yaog.utils.aioclient;

public abstract class AbstractHandler<T> {
    public abstract boolean handle(T msg);
}
