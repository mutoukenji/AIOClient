package tech.yaog.utils.aioclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

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

    private long connTimeout = 30000;
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

    public Bootstrap connTimeout(long connTimeout) {
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

    public Bootstrap splitter(AbstractSplitter splitter) {
        this.splitter = splitter;
        return this;
    }

    private Thread readerThread;
    private Thread senderThread;
    private Socket socket;
    private ThreadPoolExecutor executors = new ScheduledThreadPoolExecutor(10);
    private final Object bufferLock = new Object();
    private byte[] buffer = new byte[0];
    private final Object sendLock = new Object();

    private Queue<Object> toSendList = new ArrayBlockingQueue<>(10000);

    public void send(Object msg) {
        synchronized (sendLock) {
            toSendList.offer(msg);
            sendLock.notifyAll();
        }
    }

    public void disconnect() throws IOException {
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (senderThread != null) {
            senderThread.interrupt();
        }
        if (socket != null) {
            try {
                socket.getInputStream().close();
                socket.getOutputStream().close();
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                socket = null;
            }
        }
    }

    public void connect(InetSocketAddress address) throws IOException {

        socket = new Socket();
        socket.setKeepAlive(keepAlive);
        try {
            socket.connect(address, (int) connTimeout);
        } catch (IOException e) {
            throw e;
        }
        final InputStream is = socket.getInputStream();
        final OutputStream os = socket.getOutputStream();

        readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                splitter.callback = new AbstractSplitter.Callback() {
                    @Override
                    public void newFrame(int length, int skip) {
                        if (length <= 0 && skip <= 0) {
                            return;
                        }
                        if (length <= 0) {
                            buffer = Arrays.copyOfRange(buffer, skip, buffer.length);
                            return;
                        }
                        byte[] data;
                        synchronized (bufferLock) {
                            data = Arrays.copyOf(buffer, length);
                            buffer = Arrays.copyOfRange(buffer, length+skip, buffer.length);
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
                int readFailed = 0;
                while (!Thread.interrupted()) {
                    try {
                        int read;
                        byte[] tmp = new byte[1024];
                        while ((read = is.read(tmp)) > 0) {
                            synchronized (bufferLock) {
                                int offset = buffer.length;
                                buffer = Arrays.copyOf(buffer, offset + read);
                                System.arraycopy(tmp, 0, buffer, offset, read);
                            }
                            splitter.split(buffer);
                            if (event != null) {
                                event.onReceived();
                            }
                        }
                        if (read == -1) {
                            readFailed++;
                            if (readFailed >= 10) {
                                if (event != null) {
                                    event.onDisconnected();
                                }
                                break;
                            }
                        }
                        else {
                            readFailed = 0;
                        }
                    } catch (IOException e) {
                        exceptionHandler.onExceptionTriggered(e);
                    }
                }
            }
        });

        readerThread.setPriority(Thread.MAX_PRIORITY);
        readerThread.setName(address.getAddress()+":"+address.getPort()+"_Recv");
        readerThread.start();

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
                                        try {
                                            os.write(bytes);
                                            if (event != null) {
                                                event.onSent();
                                            }
                                        } catch (IOException e) {
                                            exceptionHandler.onExceptionTriggered(e);
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
        senderThread.setName(address.getAddress()+":"+address.getPort()+"_Send");
        senderThread.start();

        if (event != null) {
            event.onConnected();
        }
    }

}
