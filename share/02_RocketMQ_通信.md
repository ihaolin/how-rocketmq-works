# RocketMQ 通信

RocketMQ中的通信是基于**Netty 4.0.36.Final**，在其上作了一些网络消息封装(即**协议**)，再加上相应的**序列化方式**，即可作**RPC**调用。

### 通信协议

RocketMQ通信相关的实现，主要在[Remote](../remoting)模块中，协议通过[RemotingCommand](../remoting/src/main/java/org/apache/rocketmq/remoting/protocol/RemotingCommand.java)类体现，请求(**Request**)和响应(**Response**)均会使用该类：

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
	    
	/**
	 * 请求body数据
	 */
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


