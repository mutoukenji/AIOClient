package tech.yaog.utils.aioclient;

import android.os.Build;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

import tech.yaog.utils.aioclient.io.BIO;
import tech.yaog.utils.aioclient.io.NIO;
import tech.yaog.utils.aioclient.io.TCPIO;
import tech.yaog.utils.aioclient.splitter.TimestampSplitter;

/**
 * AIO 客户端
 *
 * 1. 通过 decoders encoders handlers 设置编码器、解码器、处理器
 * 2. 设置时应避免同时添加父类和子类的编码器、解码器、处理器，由于内部使用哈希表进行快速检索，如果同时出现父类与子类的编解码器，无法保证调用顺序！
 */
public class Bootstrap {

    private Map<Class<?>, AbstractDecoder<?>> decoders = new HashMap<>();
    private Map<Class<?>, AbstractEncoder<?>> encoders = new HashMap<>();
    private Map<Class<?>, AbstractHandler<?>> handlers = new HashMap<>();
    private AbstractSplitter splitter = new TimestampSplitter(10);
    private Event event = null;
    private ExceptionHandler exceptionHandler = new ExceptionHandler() {
        @Override
        public void onExceptionTriggered(Throwable t) {
            t.printStackTrace();
        }
    };

    private int connTimeout = 30000;
    private boolean keepAlive = false;

    public interface Event {
        void onConnected();
        void onDisconnected();
        void onSent();
        void onReceived();
    }

    public interface ExceptionHandler {
        void onExceptionTriggered(Throwable t);
    }

    public Bootstrap onEvent(Event eventListener) {
        event = eventListener;
        return this;
    }

    public Bootstrap exceptionHandler(ExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    public Bootstrap connTimeout(int connTimeout) {
        this.connTimeout = connTimeout;
        return this;
    }

    public Bootstrap keepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
        return this;
    }

    public Bootstrap addDecoder(AbstractDecoder<?> decoder) {
        Type[] types = ((ParameterizedType) decoder.getClass().getGenericSuperclass()).getActualTypeArguments();
        if (types.length == 1 && types[0] instanceof Class) {
            Class typeClazz = (Class)types[0];
            decoders.put(typeClazz, decoder);
        }
        return this;
    }

    public Bootstrap decoders(AbstractDecoder<?>... decoders) {
        this.decoders.clear();
        for (AbstractDecoder<?> decoder : decoders) {
            addDecoder(decoder);
        }
        return this;
    }

    public Bootstrap addEncoder(AbstractEncoder<?> encoder) {
        Type[] types = ((ParameterizedType) encoder.getClass().getGenericSuperclass()).getActualTypeArguments();
        if (types.length == 1 && types[0] instanceof Class) {
            Class typeClazz = (Class)types[0];
            encoders.put(typeClazz, encoder);
        }
        return this;
    }

    public Bootstrap encoders(AbstractEncoder<?>... encoders) {
        this.encoders.clear();
        for (AbstractEncoder<?> encoder : encoders) {
            addEncoder(encoder);
        }
        return this;
    }

    public Bootstrap addHandler(AbstractHandler<?> handler) {
        Type[] types = ((ParameterizedType) handler.getClass().getGenericSuperclass()).getActualTypeArguments();
        if (types.length == 1 && types[0] instanceof Class) {
            Class typeClazz = (Class)types[0];
            handlers.put(typeClazz, handler);
        }
        return this;
    }

    public Bootstrap handlers(AbstractHandler<?>... handlers) {
        this.handlers.clear();
        for (AbstractHandler<?> handler : handlers) {
            addHandler(handler);
        }
        return this;
    }

    public Bootstrap tcpioClass(Class<? extends TCPIO> tcpioClass) {
        this.tcpioClass = tcpioClass;
        return this;
    }

    public Bootstrap splitter(AbstractSplitter splitter) {
        this.splitter = splitter;
        return this;
    }

    private Thread senderThread;
    private ThreadPoolExecutor executors = new ScheduledThreadPoolExecutor(10);
    private final Object bufferLock = new Object();
    private byte[] buffer = new byte[0];
    private final Object sendLock = new Object();
    private TCPIO tcpio;
    private Class<? extends TCPIO> tcpioClass = autoDetect();

    private Queue<Object> toSendList = new ArrayBlockingQueue<>(10000);

    private TCPIO.Callback callback = new TCPIO.Callback() {
        @Override
        public void onReceived(byte[] data) {
            synchronized (bufferLock) {
                int offset = buffer.length;
                buffer = Arrays.copyOf(buffer, offset + data.length);
                System.arraycopy(data, 0, buffer, offset, data.length);
            }
            splitter.split(buffer);
            if (event != null) {
                event.onReceived();
            }
        }

        @Override
        public void onConnected() {
            tcpio.beginRead();
            if (event != null) {
                event.onConnected();
            }
        }

        @Override
        public void onDisconnected() {
            if (event != null) {
                event.onDisconnected();
            }
        }

        @Override
        public void onException(Throwable t) {
            exceptionHandler.onExceptionTriggered(t);
        }
    };

    /**
     * 根据 Android 版本自动决定使用的 io 接口.
     * 由于 Android N 才开始支持 NIO 的 SocketOption，在 Android N 以上版本中使用 NIO，之前的版本使用 BIO。
     * @return io 接口类
     */
    public static Class<? extends TCPIO> autoDetect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return NIO.class;
        }
        return BIO.class;
    }

    public void send(Object msg) {
        synchronized (sendLock) {
            toSendList.offer(msg);
            sendLock.notifyAll();
        }
    }

    public void disconnect() {
        tcpio.stopRead();
        if (senderThread != null) {
            senderThread.interrupt();
        }
        tcpio.disconnect();
    }

    public void connect(InetAddress address, int port) {
        try {
            tcpio = tcpioClass.getDeclaredConstructor(TCPIO.Callback.class).newInstance(callback);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        tcpio.setKeepAlive(keepAlive);
        tcpio.setConnTimeout(connTimeout);
        splitter.callback = new AbstractSplitter.Callback() {
            @Override
            public void newFrame(int length, int skip) {
                if (length <= 0 && skip <= 0) {
                    return;
                }
                if (length <= 0) {
                    synchronized (bufferLock) {
                        buffer = Arrays.copyOfRange(buffer, skip, buffer.length);
                        if (buffer.length > 0) {
                            splitter.split(buffer);
                        }
                    }
                    return;
                }
                byte[] data;
                synchronized (bufferLock) {
                    data = Arrays.copyOf(buffer, length);
                    buffer = Arrays.copyOfRange(buffer, length+skip, buffer.length);
                    if (buffer.length > 0) {
                        splitter.split(buffer);
                    }
                }
                for (AbstractDecoder<?> decoder : decoders.values()) {
                    final Object obj = decoder.decode(data);
                    if (obj != null) {
                        executors.execute(new Runnable() {
                            @Override
                            public void run() {
                                for (Class<?> clazz : handlers.keySet()) {
                                    if (clazz.isAssignableFrom(obj.getClass())) {
                                        AbstractHandler handler = handlers.get(clazz);
                                        try {
                                            handler.handle(obj);
                                        }
                                        catch (Exception e) {
                                            exceptionHandler.onExceptionTriggered(e);
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
            }
        };

        tcpio.connect(address, port);

        senderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    boolean hasData;
                    synchronized (sendLock) {
                        hasData = !toSendList.isEmpty();
                    }
                    if (hasData) {
                        Object msg;
                        synchronized (sendLock) {
                            msg = toSendList.poll();
                        }
                        if (msg != null) {
                            for (Class<?> clazz : encoders.keySet()) {
                                if (clazz.isAssignableFrom(msg.getClass())) {
                                    AbstractEncoder encoder = encoders.get(clazz);
                                    byte[] bytes = encoder.encode(msg);
                                    if (bytes != null) {
                                        tcpio.write(bytes);
                                        if (event != null) {
                                            event.onSent();
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    else {
                        synchronized (sendLock) {
                            try {
                                sendLock.wait();
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }
                }
            }
        });
        senderThread.setName(address.getAddress()+":"+port+"_Send");
        senderThread.start();

        if (event != null) {
            event.onConnected();
        }
    }

}
