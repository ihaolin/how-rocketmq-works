# RocketMQ 通信

RocketMQ中的通信是基于**Netty 4.0.36.Final**，在其上作了一些网络消息封装(即**协议**)，再加上相应的**序列化方式**，即可作**RPC**调用。

### 通信协议

RocketMQ中的协议格式，如下图所示：

![](screenshots/rmq-msg-protocol.png)


其实现主要在[Remote](../remoting)模块中，协议通过[RemotingCommand](../remoting/src/main/java/org/apache/rocketmq/remoting/protocol/RemotingCommand.java)类体现，请求(**Request**)和响应(**Response**)均会使用该类：

```java
public class RemotingCommand {

	///// 以下为请求头字段
	
	/**
	 * 请求码，
	 */
	private int code;
	
	/**
	 * 语言类型，可为后期做一些语言特性处理或兼容
	 */
	private LanguageCode language = LanguageCode.JAVA;
	
	/**
	 * 版本号
	 */
	private int version = 0;
	
	/**
	 * 请求ID
	 */
	private int opaque = requestId.getAndIncrement();
	
	/**
	 * 标记
	 */
	private int flag = 0;
	
	/**
	 * 备注
	 */
	private String remark;
	
	/**
	 * 扩展字段
	 */
	private HashMap<String, String> extFields;
	
	/**
	 * 序列化类型，默认为JSON
	 */
	private SerializeType serializeTypeCurrentRPC = serializeTypeConfigInThisServer;
	
	/**
	 * 不同请求类型特性的Header类，序列化时会将对象字段放入extFields
	 */
	private transient CommandCustomHeader customHeader;
	    

	//// 请求body数据
	private transient byte[] body;
	
	// ...
}

```

如上述代码片段，除了`customHeader`和`body`被`transient`修饰，其他字段均可序列化，请求头字段概述：

header字段名    |  Request      | Response
-------------  | ------------- | -------------
code  | 请求操作码，请求接收方根据该值做不同的操作 | 应答结果码，0表示成功，非0表示各种错误码
language  | 请求方实现语言，默认JAVA | 应答接收方实现语言
version  | 请求发起方程序版本 | 应答接收方程序版本
opaque | 标识一次请求，可用于异步处理 | 应答方不做修改，直接返回
flag | 通信标志位，如RPC类型 | 通信标志位，如RPC类型
remark | 自定义文本信息 | 错误详细描述信息
extFields | 各类请求的自定义字段 | 各类响应的自定义字段

### 序列化与反序列化

请求/响应命令序列化与反序列化均在`RemotingCommand`中实现。

#### 序列化

序列化主要在`RemotingCommand`的`encode`方法中实现：

```java
// RemotingCommand.java
public ByteBuffer encode() {

    // 计算整个command需要的字节数
    // header数据长度需要4个字节
    int length = 4;

    // 编码header
    byte[] headerData = this.headerEncode();
    // header真实数据字节数
    length += headerData.length;

    // 3> body真实数据字节数
    if (this.body != null) {
        length += body.length;
    }

    // 申请缓冲区，加上的4个字节用于存放数据总长度
    ByteBuffer result = ByteBuffer.allocate(4 + length);

    // 放入数据总长度
    result.putInt(length);

    // header length = 序列化类型(1 byte) + header数据长度(3 byte)
    // 注意，这里存放header长度的4个字节中，第1个字节存放了序列化类型，剩下3个字节存放header的数据长度，
    // 即header数据长度最大为2^24，即16M
    result.put(markProtocolType(headerData.length, serializeTypeCurrentRPC));

    // 放入header数据
    result.put(headerData);

    // 放入body数据
    if (this.body != null) {
        result.put(this.body);
    }

    // 将ByteBuffer切换到读模式
    result.flip();

    return result;
}

// 编码header数据
private byte[] headerEncode() {

    // 将定制header放入extFields
    this.makeCustomHeaderToNet();

    if (SerializeType.ROCKETMQ == serializeTypeCurrentRPC) {
        // ROCKETMQ序列化
        return RocketMQSerializable.rocketMQProtocolEncode(this);
    } else {
        // JSON序列化
        return RemotingSerializable.encode(this);
    }
}

// 设置扩展头字段
public void makeCustomHeaderToNet() {

    if (this.customHeader != null) {
        // 反射获取请求命令对象自定义的字段
        Field[] fields = getClazzFields(customHeader.getClass());
        if (null == this.extFields) {
            this.extFields = new HashMap<String, String>();
        }

        for (Field field : fields) {
            // 取非static的字段
            if (!Modifier.isStatic(field.getModifiers())) {
                String name = field.getName();
                if (!name.startsWith("this")) {
                    // 非this开头的字段
                    Object value = null;
                    try {
                        field.setAccessible(true);
                        value = field.get(this.customHeader);
                    } catch (IllegalArgumentException e) {
                    } catch (IllegalAccessException e) {
                    }

                    if (value != null) {
                        // 非空，则放入扩展字段
                        this.extFields.put(name, value.toString());
                    }
                }
            }
        }
    }
}

// 设置序列化类型
public static byte[] markProtocolType(int source, SerializeType type) {

    byte[] result = new byte[4];

    // e.g. source = 100 (00000000 00000000 00000000 1100100)

    result[0] = type.getCode();
    result[1] = (byte) ((source >> 16) & 0xFF);
    result[2] = (byte) ((source >> 8) & 0xFF);
    result[3] = (byte) (source & 0xFF);

    // [00000000, 00000000, 00000000, 1100100]
    return result;
}
```

#### 反序列化

反序列化主要在`RemotingCommand`的`decode`方法中实现：

