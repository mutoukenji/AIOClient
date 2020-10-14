package tech.yaog.utils.aioclient.io;

import android.os.Build;

import androidx.annotation.Keep;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

@Keep
public class NIO extends TCPIO {

    private SocketChannel socketChannel;
    private Selector selector;
    private SelectionKey opKey;
    private final Queue<byte[]> toSend = new ArrayBlockingQueue<>(1000);

    private final Object connectLock = new Object();

    public NIO(Callback callback) {
        super(callback);
    }

    private Runnable connectWaiting = new Runnable() {
        @Override
        public void run() {
            while (!Thread.interrupted()) {
                try {
                    selector.select(100);
                    if (Thread.interrupted()) {
                        return;
                    }
                    Set<SelectionKey> keys = selector.selectedKeys();
                    Iterator<SelectionKey> it = keys.iterator();
                    SelectionKey key;
                    while (it.hasNext()) {
                        key = it.next();
                        if (key.isConnectable()) {
                            if (socketChannel.finishConnect()) {
                                synchronized (connectLock) {
                                    connectLock.notifyAll();
                                }
                                callback.onConnected();
                                return;
                            }
                        }
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }
    };

    private Thread opThread;

    @Override
    public boolean connect(InetAddress address, int port) {
        try {
            socketChannel = SocketChannel.open();
            selector = Selector.open();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                socketChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, keepAlive);
            }
            else {
                socketChannel.socket().setKeepAlive(keepAlive);
            }
            socketChannel.configureBlocking(false);
            boolean isConnected = socketChannel.connect(new InetSocketAddress(address, port));
            if (!isConnected) {
                Thread waiting = new Thread(connectWaiting);
                waiting.start();
                opKey = socketChannel.register(selector, SelectionKey.OP_CONNECT);
                synchronized (connectLock) {
                    connectLock.wait(connTimeout);
                }
                if (!socketChannel.isConnected()) {
                    waiting.interrupt();
                    opKey.cancel();
                    return false;
                }
            }
            else {
                callback.onConnected();
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        startOpThread();
        return true;
    }

    @Override
    public void disconnect() {
        try {
            stopOpThread();
            opKey.cancel();
            opKey = null;
            socketChannel.close();
        } catch (IOException e) {
            callback.onException(e);
        }
    }

    private void stopOpThread() {
        if (opThread != null) {
            opThread.interrupt();
        }
    }

    private void startOpThread() {
        opThread = new Thread() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    try {
                        selector.select(100);
                        if (Thread.interrupted()) {
                            return;
                        }
                        Set<SelectionKey> keys = selector.selectedKeys();
                        Iterator<SelectionKey> it = keys.iterator();
                        SelectionKey key;
                        while (it.hasNext()) {
                            key = it.next();
                            if (key.isReadable()) {
                                ByteBuffer buffer = ByteBuffer.allocate(1024);
                                try {
                                    int read = socketChannel.read(buffer);
                                    if (read > 0) {
                                        byte[] bytes = new byte[read];
                                        buffer.flip();
                                        buffer.get(bytes);
                                        callback.onReceived(bytes);
                                    } else if (read < 0) {
                                        callback.onDisconnected();
                                        break;
                                    }
                                }
                                catch (SocketException e) {
                                    callback.onDisconnected();
                                    break;
                                }
                            }
                            if (key.isWritable()) {
                                boolean needSend = true;
                                while (needSend) {
                                    byte[] bytes = null;
                                    int ret = 0;
                                    synchronized (toSend) {
                                        if (!toSend.isEmpty()) {
                                            bytes = toSend.poll();
                                        }
                                    }
                                    if (bytes != null) {
                                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                                        ret = socketChannel.write(buffer);
                                    }
                                    if (ret < 0) {
                                        needSend = false;
                                    }
                                    else {
                                        synchronized (toSend) {
                                            needSend = !toSend.isEmpty();
                                        }
                                    }
                                }
                            }
                        }
                    } catch (IOException e) {
                        callback.onException(e);
                        return;
                    }
                }
            }
        };
        opThread.setPriority(Thread.MAX_PRIORITY);
        opThread.setName("Op"+System.currentTimeMillis());
        opThread.start();

        if (opKey == null) {
            try {
                opKey = socketChannel.register(selector, SelectionKey.OP_WRITE);
            } catch (ClosedChannelException e) {
                callback.onException(e);
            }
        }
        else {
            int opkey = opKey.interestOps();
            opkey |= SelectionKey.OP_WRITE;
            opKey.interestOps(opkey);
        }
    }

    @Override
    public void beginRead() {
        try {
            if (opKey == null) {
                opKey = socketChannel.register(selector, SelectionKey.OP_READ);
            }
            else {
                int opkey = opKey.interestOps();
                opkey |= SelectionKey.OP_READ;
                opKey.interestOps(opkey);
            }
        } catch (ClosedChannelException e) {
            callback.onException(e);
        }
    }

    @Override
    public void stopRead() {
        if (opKey != null) {
            int opkey = opKey.interestOps();
            opkey &= ~SelectionKey.OP_READ;
            opKey.interestOps(opkey);
        }
    }

    @Override
    public void write(byte[] bytes) {
        synchronized (toSend) {
            toSend.offer(bytes);
        }
    }
}
