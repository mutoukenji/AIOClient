package tech.yaog.utils.aioclient.io;

import androidx.annotation.Keep;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;

@Keep
public class BIO extends IO {

    private Socket socket;
    private Thread readerThread;

    public BIO(Callback callback) {
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
        socket = new Socket();
        try {
            socket.setKeepAlive(keepAlive);
        } catch (SocketException e) {
            e.printStackTrace();
        }
        InetSocketAddress inetSocketAddress = new InetSocketAddress(address, port);
        try {
            socket.connect(inetSocketAddress, connTimeout);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        callback.onConnected();
        return true;
    }

    @Override
    public void disconnect() {
        if (socket != null) {
            try {
                socket.close();
                socket = null;
            } catch (IOException e) {
                callback.onException(e);
            }
        }
    }

    @Override
    public void beginRead() {
        final InputStream is;
        try {
            is = socket.getInputStream();
        } catch (IOException e) {
            callback.onException(e);
            return;
        }
        readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int readFailed = 0;
                while (!Thread.interrupted()) {
                    try {
                        int read;
                        byte[] tmp = new byte[1024];
                        while ((read = is.read(tmp)) > 0) {
                            byte[] buffer = Arrays.copyOf(tmp, read);
                            callback.onReceived(buffer);
                        }
                        if (read == -1) {
                            readFailed++;
                            if (readFailed >= 10) {
                                callback.onDisconnected();
                                break;
                            }
                        }
                        else {
                            readFailed = 0;
                        }
                    } catch (IOException e) {
                        callback.onException(e);
                    }
                }
            }
        });

        readerThread.setPriority(Thread.MAX_PRIORITY);
        readerThread.setName(socket.toString()+"_Recv");
        readerThread.start();
    }

    @Override
    public void stopRead() {
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    @Override
    public void write(byte[] bytes) {
        try {
            OutputStream os = socket.getOutputStream();
            os.write(bytes);
            os.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
