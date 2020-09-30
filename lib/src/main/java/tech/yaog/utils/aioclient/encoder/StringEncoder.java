package tech.yaog.utils.aioclient.encoder;

import java.nio.charset.Charset;

import tech.yaog.utils.aioclient.AbstractEncoder;

public class StringEncoder extends AbstractEncoder<String> {

    private Charset charset;

    public StringEncoder(Charset charset) {
        this.charset = charset;
    }

    @Override
    public byte[] encode(String msg) {
        return msg.getBytes(charset);
    }
}
