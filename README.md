# AIOClient Android用异步IO客户端
![](https://img.shields.io/bintray/v/mutoukenji/maven/aio-client)

主要用于TCP长通讯，可以自定义拆包、编码解码方法，并设置handler来处理收到的消息

## 使用
build.gradle中添加如下dependency
```
implementation 'tech.yaog.utils:aio-client:1.1.1'
```

new 一个 Bootstrap
```
final Bootstrap bootstrap = new Bootstrap();
```
设置拆包器、编解码器、数据处理
```
bootstrap
    .addEncoder(new StringEncoder(Charset.forName("UTF-8"))) // 默认提供String编码器，可自定义，可添加多个
    .addDecoder(new StringDecoder(Charset.forName("UTF-8"))) // 默认提供String解码器，可自定义，可添加多个
    .addHandler(new AbstractHandler<String>() {
        @Override
        public boolean handle(String msg) {
            // Do your work thing here
            return true;
        }
    }) // 依据泛型参数类型来匹配处理器，应与解码器的类型一一对应
    .splitter(new TimestampSplitter(50)) // 拆包器，不设置的话默认为10ms无数据自动断包，也可以用DelimiterSplitter或自定义
```
设置事件监听，异常监听 (可选)
```
bootstrap
    .exceptionHandler(new Bootstrap.ExceptionHandler() {
        @Override
        public void onExceptionTriggered(Throwable t) {
            t.printStackTrace();
        }
    })
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
```
设置参数，当前支持keepAlive, connTimeout (可选)
```
bootstrap
    .connTimeout(30000)
    .keepAlive(true)
```
连接
```
bootstrap.connect("192.168.101.2:6000");
```

## 更多用法
手动选择io类
```
bootstrap.ioClass(BIO.class)
```
默认提供BIO NIO AIO供选择，如果不设置的话，Android O以上使用AIO，否则使用NIO。

## 拓展
虽然原始设计为供TCP长连接使用，但也可以自定义io类以适用于其他情况。
比方用于串口连接，可以自行实现一个io类，并定义如`/dev/ttyXX:b9600:c8:s1:odd:hw`一类的地址作为连接目标同时指定串口参数。
具体实现此处不举例了