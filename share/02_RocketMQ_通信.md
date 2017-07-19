# RocketMQ 通信

RocketMQ中的通信是基于**Netty 4.0.36.Final**，在其上作了一些网络消息封装(即**协议**)，再加上相应的**序列化方式**，即可作**RPC**调用。

### 通信协议

RocketMQ通信相关的是实现，主要在[Remote](../remoting)模块中，协议通过[RemotingCommand](../remoting/src/main/java/org/apache/rocketmq/remoting/protocol/RemotingCommand.java)类体现：

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