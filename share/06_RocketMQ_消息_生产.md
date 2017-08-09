# RocketMQ消息生产

RocketMQ中**消息生产**主要由[DefaultMQProducer](../client/src/main/java/org/apache/rocketmq/client/producer/DefaultMQProducer.java)发起，其内部封装了各种发送消息的方法，用于不同的场景，
使用时，应先了解各种方法的优缺点，再选择使用那种方法。**DefaultMQProducer**一旦启动后，就是**线程安全的**，通常业务应用只需保持**单例**即可。下文将分析下DefaultMQProducer的一些核心实现。

## Producer启动

**DefaultMQProducer**内部包装了一个[DefaultMQProducer](../client/src/main/java/org/apache/rocketmq/client/impl/producer/DefaultMQProducerImpl.java)实例，所有客户端请求将由该实例代理。
DefaultMQProducer还包含了一些基本配置：

```java
// DefaultMQProducer.java

// Producer实现代理
protected final transient DefaultMQProducerImpl defaultMQProducerImpl;

// 生产者组（在事务消息中使用）
private String producerGroup;

// 默认Topic队列数
private volatile int defaultTopicQueueNums = 4;

// 默认Topic，用于测试
private String createTopicKey = MixAll.DEFAULT_TOPIC;

// 消息发送超时
private int sendMsgTimeout = 3000;

// 消息超过该值，则压缩
private int compressMsgBodyOverHowmuch = 1024 * 4;

// 消息发送失败重试次数
private int retryTimesWhenSendFailed = 2;

// 消息异步发送失败重试次数
private int retryTimesWhenSendAsyncFailed = 2;

// 当Broker存储失败时，是否发送到其他Broker
private boolean retryAnotherBrokerWhenNotStoreOK = false;

// 最大消息大小，4M
private int maxMessageSize = 1024 * 1024 * 4; 

```

看看Producer的启动过程：

```java
// DefaultMQProducer.java
public void start() throws MQClientException {
    this.defaultMQProducerImpl.start();
}
```

```java
// DefaultMQProducerImpl.java
public void start() throws MQClientException {
    this.start(true);
}

public void start(final boolean startFactory) throws MQClientException {
    switch (this.serviceState) {
        case CREATE_JUST:

            this.serviceState = ServiceState.START_FAILED;

            // 检查必要的配置
            this.checkConfig();

            if (!this.defaultMQProducer.getProducerGroup().equals(MixAll.CLIENT_INNER_PRODUCER_GROUP)) {
                // 设置Producer instanceName为进程号
                this.defaultMQProducer.changeInstanceNameToPID();
            }

            // 创建客户端实例
            this.mQClientFactory = MQClientManager.getInstance().getAndCreateMQClientInstance(this.defaultMQProducer, rpcHook);

            // 注册Producer
            boolean registerOK = mQClientFactory.registerProducer(this.defaultMQProducer.getProducerGroup(), this);
            if (!registerOK) {
                this.serviceState = ServiceState.CREATE_JUST;
                throw new MQClientException("The producer group[" + this.defaultMQProducer.getProducerGroup()
                    + "] has been created before, specify another name please." + FAQUrl.suggestTodo(FAQUrl.GROUP_NAME_DUPLICATE_URL),
                    null);
            }

            this.topicPublishInfoTable.put(this.defaultMQProducer.getCreateTopicKey(), new TopicPublishInfo());

            if (startFactory) {
                // 启动客户端实例
                mQClientFactory.start();
            }

            log.info("the producer [{}] start OK. sendMessageWithVIPChannel={}", this.defaultMQProducer.getProducerGroup(),
                this.defaultMQProducer.isSendMessageWithVIPChannel());
            this.serviceState = ServiceState.RUNNING;

            break;
        case RUNNING:
        case START_FAILED:
        case SHUTDOWN_ALREADY:
            throw new MQClientException("The producer service state not OK, maybe started once, "//
                + this.serviceState//
                + FAQUrl.suggestTodo(FAQUrl.CLIENT_SERVICE_NOT_OK),
                null);
        default:
            break;
    }

    // 发送心跳给所有Broker
    this.mQClientFactory.sendHeartbeatToAllBrokerWithLock();
}
```

```java
public void start() throws MQClientException {

    synchronized (this) {
        switch (this.serviceState) {
            case CREATE_JUST:

                this.serviceState = ServiceState.START_FAILED;

                // 没有设置NameServer，从ws地址获取
                if (null == this.clientConfig.getNamesrvAddr()) {
                    this.mQClientAPIImpl.fetchNameServerAddr();
                }

                // 启动NettyClient
                this.mQClientAPIImpl.start();

                // 启动调度任务
                this.startScheduledTask();

                // 启动消息拉取服务，该服务会持续执行消息拉取请求
                this.pullMessageService.start();

                // 启动负载服务，该服务会定时作消费者balance
                this.rebalanceService.start();

                // 启动内部使用的Producer
                this.defaultMQProducer.getDefaultMQProducerImpl().start(false);

                log.info("the client factory [{}] start OK", this.clientId);
                this.serviceState = ServiceState.RUNNING;
                break;
            case RUNNING:
                break;
            case SHUTDOWN_ALREADY:
                break;
            case START_FAILED:
                throw new MQClientException("The Factory object[" + this.getClientId() + "] has been created before, and failed.", null);
            default:
                break;
        }
    }
}
```


## Producer消息发送