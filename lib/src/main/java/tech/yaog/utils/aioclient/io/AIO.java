package tech.yaog.utils.aioclient.io;

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

@RequiresApi(Build.VERSION_CODES.O)
public class AIO extends IO {

    private AsynchronousSocketChannel socketChannel;
    private final Object connLock = new Object();
    private boolean isConnected;

    private Thread recvThread;
    private final Object readLock = new Object();

    public AIO(Callback callback) {
        super(callback);
    }

    @Override
    public boolean connect(String remote) {
        String[] remoteBlocks = remote.split(":");
        if (remoteBlocks.length < 2) {
            return false;
        }
        InetAddress address;
        int port;
        try {
            address = InetAddress.getByName(remoteBlocks[0].trim());
        } catch (UnknownHostException e) {
            callback.onException(e);
            return false;
        }
        try {
            port = Integer.parseInt(remoteBlocks[1].trim());
        }
        catch (NumberFormatException e) {
            callback.onException(e);
            return false;
        }
        try {
            socketChannel = AsynchronousSocketChannel.open();
            socketChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, keepAlive);
            isConnected = false;
            long now = System.nanoTime();
            Log.d("NOW", ""+now);
            long endTime = connTimeout * 1000000L + now;
            Log.d("endTime", ""+endTime);
            socketChannel.connect(new InetSocketAddress(address, port), endTime, new CompletionHandler<Void, Long>() {
                @Override
                public void completed(Void result, Long attachment) {
                    long now = System.nanoTime();
                    Log.d("CON", ""+now);
                    if (now > attachment) {
                        // 超时了，不要继续连接
                        try {
                            socketChannel.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    else {
                        isConnected = true;
                        synchronized (connLock) {
                            connLock.notifyAll();
                        }
                        callback.onConnected();
                    }
                }

                @Override
                public void failed(Throwable exc, Long attachment) {
                    exc.printStackTrace();
                }
            });

            synchronized (connLock) {
                connLock.wait(connTimeout);
            }

            if (!isConnected) {
                socketChannel.close();
                return false;
            }

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public void disconnect() {
        try {
            socketChannel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void beginRead() {
        recvThread = new Thread(){
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    ByteBuffer buffer = ByteBuffer.allocate(1024);
                    socketChannel.read(buffer, buffer, new CompletionHandler<Integer, ByteBuffer>() {
                        @Override
                        public void completed(Integer result, ByteBuffer attachment) {
                            if (result > 0) {
                                byte[] bytes = new byte[result];
                                attachment.flip();
                                attachment.get(bytes);
                                callback.onReceived(bytes);
                            }
                            else if (result < 0) {
                                callback.onDisconnected();
                            }
                            synchronized (readLock) {
                                readLock.notifyAll();
                            }
                        }

                        @Override
                        public void failed(Throwable exc, ByteBuffer attachment) {
                            callback.onDisconnected();
                            synchronized (readLock) {
                                readLock.notifyAll();
                            }
                        }
                    });
                    synchronized (readLock) {
                        try {
                            readLock.wait();
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            }
        };
        recvThread.setPriority(Thread.MAX_PRIORITY);
        recvThread.setName("AIO worker");
        recvThread.start();
    }

    @Override
    public void stopRead() {
        if (recvThread != null) {
            recvThread.interrupt();
        }
    }

    @Override
    public void write(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        socketChannel.write(buffer, null, new CompletionHandler<Integer, Object>() {
            @Override
            public void completed(Integer result, Object attachment) {

            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                callback.onException(exc);
            }
        });
    }
}
