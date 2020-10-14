package tech.yaog.utils.aioclient.app;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;

import tech.yaog.utils.aioclient.AbstractHandler;
import tech.yaog.utils.aioclient.Bootstrap;
import tech.yaog.utils.aioclient.StringDecoder;
import tech.yaog.utils.aioclient.encoder.StringEncoder;
import tech.yaog.utils.aioclient.io.BIO;
import tech.yaog.utils.aioclient.io.NIO;
import tech.yaog.utils.aioclient.splitter.DelimiterSplitter;
import tech.yaog.utils.aioclient.splitter.TimestampSplitter;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bootstrap bootstrap = new Bootstrap();
                try {
                    bootstrap.addEncoder(new StringEncoder(Charset.forName("UTF-8")))
                            .addDecoder(new StringDecoder(Charset.forName("UTF-8")))
                            .addHandler(new AbstractHandler<String>() {
                                @Override
                                public boolean handle(String msg) {
                                    Log.d("Recv", msg);
                                    bootstrap.send("Re "+msg);
                                    return true;
                                }
                            })
                            .exceptionHandler(new Bootstrap.ExceptionHandler() {
                                @Override
                                public void onExceptionTriggered(Throwable t) {
                                    t.printStackTrace();
                                }
                            })
                            .tcpioClass(NIO.class)
                            .connTimeout(30000)
                            .onEvent(new Bootstrap.Event() {
                                @Override
                                public void onConnected() {

                                }

                                @Override
                                public void onDisconnected() {
                                    Log.e("Conn", "disconnected!!");
                                    bootstrap.disconnect();
                                }

                                @Override
                                public void onSent() {

                                }

                                @Override
                                public void onReceived() {

                                }
                            })
//                            .splitter(new DelimiterSplitter("1".getBytes()))
                            .splitter(new TimestampSplitter(50))
                            .connect(InetAddress.getByName("192.168.101.2"), 6000);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
}