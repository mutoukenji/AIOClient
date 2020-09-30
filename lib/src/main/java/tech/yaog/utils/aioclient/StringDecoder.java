package tech.yaog.utils.aioclient;

import java.nio.charset.Charset;

public class StringDecoder extends AbstractDecoder<String> {

    private Charset charset;

    public StringDecoder(Charset charset) {
        this.charset = charset;
    }

    @Override
    public String decode(byte[] byteBuffer) {
        return new String(byteBuffer, charset);
    }
}
