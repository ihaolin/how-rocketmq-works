# RocketMQ服务之BrokerServer

**Broker**作为**RocketMQ**最为核心的服务组件，其职责也相对相对较多，如：

+ **Remoting Module**，broker的入口，处理来自客户端的请求；
+ **Client Manager**，管理客户端(生产者/消费者)，并维护消费者的Topic订阅信息；
+ **Store Service**，提供简单的API，用于存储或查询消息；
+ **HA Service**，负责**Broker**Master和Slave之间的数据同步；
+ **Index Service**，通过特定的Key，为消息构建索引，便于提供快速的消息查询；
+ ...。

本文将从部分功能出发，了解其内部的基本实现。

## 服务启动

**Broker**启动的入口为[BrokerStartup](../broker/src/main/java/org/apache/rocketmq/broker/BrokerStartup.java)类：

```java
// BrokerStartup.java
public static void main(String[] args) {
    start(createBrokerController(args));
}

public static BrokerController start(BrokerController controller) {
    try {
        // 启动BrokerController
        controller.start();
        return controller;
    } catch (Throwable e) {
        e.printStackTrace();
        System.exit(-1);
    }
    return null;
}

public static BrokerController createBrokerController(String[] args) {

    System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));

    if (null == System.getProperty(NettySystemConfig.COM_ROCKETMQ_REMOTING_SOCKET_SNDBUF_SIZE)) {
        // send buffer: 128k
        NettySystemConfig.socketSndbufSize = 131072;
    }

    if (null == System.getProperty(NettySystemConfig.COM_ROCKETMQ_REMOTING_SOCKET_RCVBUF_SIZE)) {
        // receive buffer: 128k
        NettySystemConfig.socketRcvbufSize = 131072;
    }

    try {

        Options options = ServerUtil.buildCommandlineOptions(new Options());
        commandLine = ServerUtil.parseCmdLine("mqbroker", args, buildCommandlineOptions(options),
            new PosixParser());
        if (null == commandLine) {
            System.exit(-1);
        }

        // Broker配置
        final BrokerConfig brokerConfig = new BrokerConfig();

        // NettyServer配置
        final NettyServerConfig nettyServerConfig = new NettyServerConfig();
        nettyServerConfig.setListenPort(10911);

        // NettyClient配置
        final NettyClientConfig nettyClientConfig = new NettyClientConfig();

        // MessageStore配置
        final MessageStoreConfig messageStoreConfig = new MessageStoreConfig();

        if (BrokerRole.SLAVE == messageStoreConfig.getBrokerRole()) {
            int ratio = messageStoreConfig.getAccessMessageInMemoryMaxRatio() - 10;
            messageStoreConfig.setAccessMessageInMemoryMaxRatio(ratio);
        }

        // 仅打印当前配置信息
        // ...

        if (commandLine.hasOption('c')) {
            // 从配置文件中读取配置
            String file = commandLine.getOptionValue('c');
            if (file != null) {
                configFile = file;
                InputStream in = new BufferedInputStream(new FileInputStream(file));
                properties = new Properties();
                properties.load(in);

                parseProperties2SystemEnv(properties);
                // ...

                BrokerPathConfigHelper.setBrokerConfigPath(file);
                in.close();
            }
        }

        // 从命令行获取配置
        MixAll.properties2Object(ServerUtil.commandLine2Properties(commandLine), brokerConfig);

        if (null == brokerConfig.getRocketmqHome()) {
            System.out.printf("Please set the " + MixAll.ROCKETMQ_HOME_ENV
                + " variable in your environment to match the location of the RocketMQ installation");
            System.exit(-2);
        }

        // 校验nameServer地址
        String namesrvAddr = brokerConfig.getNamesrvAddr();
        if (null != namesrvAddr) {
            try {
                String[] addrArray = namesrvAddr.split(";");
                for (String addr : addrArray) {
                    RemotingUtil.string2SocketAddress(addr);
                }
            } catch (Exception e) {
                System.out.printf(
                    "The Name Server Address[%s] illegal, please set it as follows, \"127.0.0.1:9876;192.168.0.1:9876\"%n",
                    namesrvAddr);
                System.exit(-3);
            }
        }

        // 校验Broker角色
        switch (messageStoreConfig.getBrokerRole()) {
            case ASYNC_MASTER:
            case SYNC_MASTER:
                // 根据brokerRole，设置brokerId
                brokerConfig.setBrokerId(MixAll.MASTER_ID);
                break;
            case SLAVE:
                if (brokerConfig.getBrokerId() <= 0) {
                    System.out.printf("Slave's brokerId must be > 0");
                    System.exit(-3);
                }

                break;
            default:
                break;
        }

        // 设置Ha监听端口，listenPort + 1
        messageStoreConfig.setHaListenPort(nettyServerConfig.getListenPort() + 1);

        // 初始化日志配置，${ROCKETMQ_HOME}/conf/logback_broker.xml
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(lc);
        lc.reset();
        configurator.doConfigure(brokerConfig.getRocketmqHome() + "/conf/logback_broker.xml");
        log = LoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);

        // ...

        // 构建BrokerController
        final BrokerController controller = new BrokerController(brokerConfig, nettyServerConfig, nettyClientConfig, messageStoreConfig);

        // remember all configs to prevent discard
        controller.getConfiguration().registerConfig(properties);

        // 初始化BrokerController
        boolean initResult = controller.initialize();
        if (!initResult) {
            controller.shutdown();
            System.exit(-3);
        }

        // Shutdown Hook

        return controller;
    } catch (Throwable e) {
        e.printStackTrace();
        System.exit(-1);
    }

    return null;
}

```
**BrokerController**主要作了**配置解析**，**日志初始化**等工作，其他服务启动主要由[BrokerController](../broker/src/main/java/org/apache/rocketmq/broker/BrokerController.java)完成：

```java
// BrokerController.java
// 初始化各组件
public boolean initialize() throws CloneNotSupportedException {

    boolean result = true;

    // 加载topic信息
    result = result && this.topicConfigManager.load();

    // 加载consumer消费进度信息
    result = result && this.consumerOffsetManager.load();

    // 加载订阅组信息
    result = result && this.subscriptionGroupManager.load();

    if (result) {
        try {
            // 初始化MessageStore
            this.messageStore =
                new DefaultMessageStore(this.messageStoreConfig, this.brokerStatsManager, this.messageArrivingListener, this.brokerConfig);

            // 初始化Broker状态
            this.brokerStats = new BrokerStats((DefaultMessageStore) this.messageStore);

            //load plugin
            MessageStorePluginContext context = new MessageStorePluginContext(messageStoreConfig, brokerStatsManager, messageArrivingListener, brokerConfig);
            this.messageStore = MessageStoreFactory.build(context, this.messageStore);
        } catch (IOException e) {
            result = false;
            e.printStackTrace();
        }
    }

    // 加载存储信息
    result = result && this.messageStore.load();

    if (result) {

        // 初始化通信服务Server
        this.remotingServer = new NettyRemotingServer(this.nettyServerConfig, this.clientHousekeepingService);

        // 初始化VIP Channel的通信服务Server
        NettyServerConfig fastConfig = (NettyServerConfig) this.nettyServerConfig.clone();
        fastConfig.setListenPort(nettyServerConfig.getListenPort() - 2);
        this.fastRemotingServer = new NettyRemotingServer(fastConfig, this.clientHousekeepingService);

        //// 初始化各类请求执行线程池

        // 消息生产请求处理线程池
        this.sendMessageExecutor = new BrokerFixedThreadPoolExecutor(
            this.brokerConfig.getSendMessageThreadPoolNums(),
            this.brokerConfig.getSendMessageThreadPoolNums(),
            1000 * 60,
            TimeUnit.MILLISECONDS,
            this.sendThreadPoolQueue,
            new ThreadFactoryImpl("SendMessageThread_"));

        // 消息消费请求处理线程池
        this.pullMessageExecutor = new BrokerFixedThreadPoolExecutor(
            this.brokerConfig.getPullMessageThreadPoolNums(),
            this.brokerConfig.getPullMessageThreadPoolNums(),
            1000 * 60,
            TimeUnit.MILLISECONDS,
            this.pullThreadPoolQueue,
            new ThreadFactoryImpl("PullMessageThread_"));

        // 默认请求处理线程池
        this.adminBrokerExecutor =
            Executors.newFixedThreadPool(this.brokerConfig.getAdminBrokerThreadPoolNums(), new ThreadFactoryImpl(
                "AdminBrokerThread_"));

        // Client管理线程池
        this.clientManageExecutor = new ThreadPoolExecutor(
            this.brokerConfig.getClientManageThreadPoolNums(),
            this.brokerConfig.getClientManageThreadPoolNums(),
            1000 * 60,
            TimeUnit.MILLISECONDS,
            this.clientManagerThreadPoolQueue,
            new ThreadFactoryImpl("ClientManageThread_"));

        // 消费者管理线程池
        this.consumerManageExecutor =
            Executors.newFixedThreadPool(this.brokerConfig.getConsumerManageThreadPoolNums(), new ThreadFactoryImpl(
                "ConsumerManageThread_"));

        // 注册各类请求处理器
        this.registerProcessor();

        //// 启动各类调度任务

        // 定时记录Broker状态
        final long initialDelay = UtilAll.computNextMorningTimeMillis() - System.currentTimeMillis();
        final long period = 1000 * 60 * 60 * 24;
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    BrokerController.this.getBrokerStats().record();
                } catch (Throwable e) {
                    log.error("schedule record error.", e);
                }
            }
        }, initialDelay, period, TimeUnit.MILLISECONDS);

        // 定时持久化消费者消费进度
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    BrokerController.this.consumerOffsetManager.persist();
                } catch (Throwable e) {
                    log.error("schedule persist consumerOffset error.", e);
                }
            }
        }, 1000 * 10, this.brokerConfig.getFlushConsumerOffsetInterval(), TimeUnit.MILLISECONDS);

        // 
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    BrokerController.this.protectBroker();
                } catch (Exception e) {
                    log.error("protectBroker error.", e);
                }
            }
        }, 3, 3, TimeUnit.MINUTES);

        // 定时打印 WATER MARK
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    BrokerController.this.printWaterMark();
                } catch (Exception e) {
                    log.error("printWaterMark error.", e);
                }
            }
        }, 10, 1, TimeUnit.SECONDS);

        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    log.info("dispatch behind commit log {} bytes", BrokerController.this.getMessageStore().dispatchBehindBytes());
                } catch (Throwable e) {
                    log.error("schedule dispatchBehindBytes error.", e);
                }
            }
        }, 1000 * 10, 1000 * 60, TimeUnit.MILLISECONDS);

        if (this.brokerConfig.getNamesrvAddr() != null) {
            this.brokerOuterAPI.updateNameServerAddressList(this.brokerConfig.getNamesrvAddr());
            log.info("Set user specified name server address: {}", this.brokerConfig.getNamesrvAddr());
        } else if (this.brokerConfig.isFetchNamesrvAddrByAddressServer()) {
            // 定时从ws获取NameServer地址
            this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {
                    try {
                        BrokerController.this.brokerOuterAPI.fetchNameServerAddr();
                    } catch (Throwable e) {
                        log.error("ScheduledTask fetchNameServerAddr exception", e);
                    }
                }
            }, 1000 * 10, 1000 * 60 * 2, TimeUnit.MILLISECONDS);
        }

        if (BrokerRole.SLAVE == this.messageStoreConfig.getBrokerRole()) {
            // 若该Broker是Slave

            if (this.messageStoreConfig.getHaMasterAddress() != null && this.messageStoreConfig.getHaMasterAddress().length() >= 6) {
                // 设置HA地址
                this.messageStore.updateHaMasterAddress(this.messageStoreConfig.getHaMasterAddress());
                this.updateMasterHAServerAddrPeriodically = false;
            } else {
                this.updateMasterHAServerAddrPeriodically = true;
            }

            // 定时同步相关数据，如Topic，消费进度等
            this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {
                    try {
                        BrokerController.this.slaveSynchronize.syncAll();
                    } catch (Throwable e) {
                        log.error("ScheduledTask syncAll slave exception", e);
                    }
                }
            }, 1000 * 10, 1000 * 60, TimeUnit.MILLISECONDS);

        } else {

            // 定时打印Master/Slave同步差距
            this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {
                    try {
                        BrokerController.this.printMasterAndSlaveDiff();
                    } catch (Throwable e) {
                        log.error("schedule printMasterAndSlaveDiff error.", e);
                    }
                }
            }, 1000 * 10, 1000 * 60, TimeUnit.MILLISECONDS);
        }
    }

    return result;
}

// 注册不同请求处理器
public void registerProcessor() {

    // 消息发送请求处理器
    SendMessageProcessor sendProcessor = new SendMessageProcessor(this);
    sendProcessor.registerSendMessageHook(sendMessageHookList);
    sendProcessor.registerConsumeMessageHook(consumeMessageHookList);

    this.remotingServer.registerProcessor(RequestCode.SEND_MESSAGE, sendProcessor, this.sendMessageExecutor);
    this.remotingServer.registerProcessor(RequestCode.SEND_MESSAGE_V2, sendProcessor, this.sendMessageExecutor);
    this.remotingServer.registerProcessor(RequestCode.CONSUMER_SEND_MSG_BACK, sendProcessor, this.sendMessageExecutor);
    this.fastRemotingServer.registerProcessor(RequestCode.SEND_MESSAGE, sendProcessor, this.sendMessageExecutor);
    this.fastRemotingServer.registerProcessor(RequestCode.SEND_MESSAGE_V2, sendProcessor, this.sendMessageExecutor);
    this.fastRemotingServer.registerProcessor(RequestCode.CONSUMER_SEND_MSG_BACK, sendProcessor, this.sendMessageExecutor);

    // 消息拉取请求处理器
    this.remotingServer.registerProcessor(RequestCode.PULL_MESSAGE, this.pullMessageProcessor, this.pullMessageExecutor);
    this.pullMessageProcessor.registerConsumeMessageHook(consumeMessageHookList);

    // 消息查询请求处理器
    NettyRequestProcessor queryProcessor = new QueryMessageProcessor(this);
    this.remotingServer.registerProcessor(RequestCode.QUERY_MESSAGE, queryProcessor, this.pullMessageExecutor);
    this.remotingServer.registerProcessor(RequestCode.VIEW_MESSAGE_BY_ID, queryProcessor, this.pullMessageExecutor);
    this.fastRemotingServer.registerProcessor(RequestCode.QUERY_MESSAGE, queryProcessor, this.pullMessageExecutor);
    this.fastRemotingServer.registerProcessor(RequestCode.VIEW_MESSAGE_BY_ID, queryProcessor, this.pullMessageExecutor);

    // 客户端管理请求处理器
    ClientManageProcessor clientProcessor = new ClientManageProcessor(this);
    this.remotingServer.registerProcessor(RequestCode.HEART_BEAT, clientProcessor, this.clientManageExecutor);
    this.remotingServer.registerProcessor(RequestCode.UNREGISTER_CLIENT, clientProcessor, this.clientManageExecutor);

    this.fastRemotingServer.registerProcessor(RequestCode.HEART_BEAT, clientProcessor, this.clientManageExecutor);
    this.fastRemotingServer.registerProcessor(RequestCode.UNREGISTER_CLIENT, clientProcessor, this.clientManageExecutor);

    // 消费者管理请求处理器
    ConsumerManageProcessor consumerManageProcessor = new ConsumerManageProcessor(this);
    this.remotingServer.registerProcessor(RequestCode.GET_CONSUMER_LIST_BY_GROUP, consumerManageProcessor, this.consumerManageExecutor);
    this.remotingServer.registerProcessor(RequestCode.UPDATE_CONSUMER_OFFSET, consumerManageProcessor, this.consumerManageExecutor);
    this.remotingServer.registerProcessor(RequestCode.QUERY_CONSUMER_OFFSET, consumerManageProcessor, this.consumerManageExecutor);
    this.fastRemotingServer.registerProcessor(RequestCode.GET_CONSUMER_LIST_BY_GROUP, consumerManageProcessor, this.consumerManageExecutor);
    this.fastRemotingServer.registerProcessor(RequestCode.UPDATE_CONSUMER_OFFSET, consumerManageProcessor, this.consumerManageExecutor);
    this.fastRemotingServer.registerProcessor(RequestCode.QUERY_CONSUMER_OFFSET, consumerManageProcessor, this.consumerManageExecutor);

    // 事务结束请求处理器
    this.remotingServer.registerProcessor(RequestCode.END_TRANSACTION, new EndTransactionProcessor(this), this.sendMessageExecutor);
    this.fastRemotingServer.registerProcessor(RequestCode.END_TRANSACTION, new EndTransactionProcessor(this), this.sendMessageExecutor);

    // 默认请求处理器
    AdminBrokerProcessor adminProcessor = new AdminBrokerProcessor(this);
    this.remotingServer.registerDefaultProcessor(adminProcessor, this.adminBrokerExecutor);
    this.fastRemotingServer.registerDefaultProcessor(adminProcessor, this.adminBrokerExecutor);
}

// Broker启动
public void start() throws Exception {

    if (this.messageStore != null) {
        // 启动消息存储服务
        this.messageStore.start();
    }

    if (this.remotingServer != null) {
        // 启动通信服务
        this.remotingServer.start();
    }

    if (this.fastRemotingServer != null) {
        // 启动VIP通道通信服务
        this.fastRemotingServer.start();
    }

    if (this.brokerOuterAPI != null) {
        // 启动外部客户端RPC调用服务
        this.brokerOuterAPI.start();
    }

    if (this.pullRequestHoldService != null) {
        this.pullRequestHoldService.start();
    }

    if (this.clientHousekeepingService != null) {
        // 启动Client事件服务
        this.clientHousekeepingService.start();
    }

    if (this.filterServerManager != null) {
        // 启动FilterServer管理器
        this.filterServerManager.start();
    }

    // 注册本地Topic等信息到NameServer
    this.registerBrokerAll(true, false);

    // 10s后，每隔30s注册一次Broker信息
    this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

        @Override
        public void run() {
            try {
                BrokerController.this.registerBrokerAll(true, false);
            } catch (Throwable e) {
                log.error("registerBrokerAll Exception", e);
            }
        }
    }, 1000 * 10, 1000 * 30, TimeUnit.MILLISECONDS);

    if (this.brokerStatsManager != null) {
        // 启动Broker状态管理器
        this.brokerStatsManager.start();
    }

    if (this.brokerFastFailure != null) {
        // 启动Broker快速失败服务
        this.brokerFastFailure.start();
    }
}
```

## 客户端管理

**Broker**中，主要由[ProducerManager](../broker/src/main/java/org/apache/rocketmq/broker/client/ProducerManager.java)，[ConsumerManager](../broker/src/main/java/org/apache/rocketmq/broker/client/ConsumerManager.java)等实现，当客户端启动时，会通过心跳(**HEART_BEAT**)方式，注册自己的信息到Broker，
主要由[MQClientAPIImpl](../client/src/main/java/org/apache/rocketmq/client/impl/MQClientAPIImpl.java)类实现：

```java
// MQClientAPIImpl.java
public void sendHeartbeatToAllBrokerWithLock() {

    // 获取心跳锁
    if (this.lockHeartbeat.tryLock()) {
        try {
            // 对所有Broker发送心跳
            this.sendHeartbeatToAllBroker();
            // 上传FilterClass代码
            this.uploadFilterClassSource();
        } catch (final Exception e) {
            log.error("sendHeartbeatToAllBroker exception", e);
        } finally {
            // 释放心跳锁
            this.lockHeartbeat.unlock();
        }
    } else {
        log.warn("lock heartBeat, but failed.");
    }
}

private void sendHeartbeatToAllBroker() {

    // 构建心跳发送数据
    // 包括clientId，生产者，消费者信息
    final HeartbeatData heartbeatData = this.prepareHeartbeatData();

    final boolean producerEmpty = heartbeatData.getProducerDataSet().isEmpty();
    final boolean consumerEmpty = heartbeatData.getConsumerDataSet().isEmpty();
    if (producerEmpty && consumerEmpty) {
        log.warn("sending heartbeat, but no consumer and no producer");
        return;
    }

    long times = this.storeTimesTotal.getAndIncrement();
    Iterator<Entry<String, HashMap<Long, String>>> it = this.brokerAddrTable.entrySet().iterator();
    while (it.hasNext()) {
        Entry<String, HashMap<Long, String>> entry = it.next();
        String brokerName = entry.getKey();
        HashMap<Long, String> oneTable = entry.getValue();
        if (oneTable != null) {
            for (Map.Entry<Long, String> entry1 : oneTable.entrySet()) {
                Long id = entry1.getKey();
                String addr = entry1.getValue();
                if (addr != null) {
                    if (consumerEmpty) {
                        // 没有消费者
                        if (id != MixAll.MASTER_ID)
                            // 不是master，则不发送心跳数据
                            continue;
                    }

                    try {
                        // 发送心跳数据
                        this.mQClientAPIImpl.sendHearbeat(addr, heartbeatData, 3000);
                        // ...
                    } catch (Exception e) {
                        // ...
                    }
                }
            }
        }
    }
}

public void sendHearbeat(final String addr, final HeartbeatData heartbeatData, final long timeoutMillis)
        throws RemotingException, MQBrokerException, InterruptedException {

    // 构建RemotingCommand
    RemotingCommand request = RemotingCommand.createRequestCommand(RequestCode.HEART_BEAT, null);

    request.setBody(heartbeatData.encode());

    // 发送同步请求
    RemotingCommand response = this.remotingClient.invokeSync(addr, request, timeoutMillis);
    assert response != null;
    switch (response.getCode()) {
        case ResponseCode.SUCCESS: {
            return;
        }
        default:
            break;
    }

    throw new MQBrokerException(response.getCode(), response.getRemark());
}
```

当Broker接收到心跳请求后，将由[ClientManageProcessor](../broker/src/main/java/org/apache/rocketmq/broker/processor/ClientManageProcessor.java)处理：

```java
// ClientManageProcessor.java
public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request)
    throws RemotingCommandException {
    switch (request.getCode()) {
        case RequestCode.HEART_BEAT:
            // 处理客户端心跳
            return this.heartBeat(ctx, request);
        case RequestCode.UNREGISTER_CLIENT:
            // 处理客户端注销
            return this.unregisterClient(ctx, request);
        default:
            break;
    }
    return null;
}

public RemotingCommand heartBeat(ChannelHandlerContext ctx, RemotingCommand request) {

    // 构建Response
    RemotingCommand response = RemotingCommand.createResponseCommand(null);

    // 解析心跳数据
    HeartbeatData heartbeatData = HeartbeatData.decode(request.getBody(), HeartbeatData.class);

    // 构建客户端链接信息
    ClientChannelInfo clientChannelInfo = new ClientChannelInfo(
        ctx.channel(),
        heartbeatData.getClientID(),
        request.getLanguage(),
        request.getVersion()
    );

    // 注册Consumer
    for (ConsumerData data : heartbeatData.getConsumerDataSet()) {

        // 订阅组配置信息
        SubscriptionGroupConfig subscriptionGroupConfig =
            this.brokerController.getSubscriptionGroupManager().findSubscriptionGroupConfig(data.getGroupName());

        // 是否通知消费者ID发生变化
        boolean isNotifyConsumerIdsChangedEnable = true;

        if (null != subscriptionGroupConfig) {
            isNotifyConsumerIdsChangedEnable = subscriptionGroupConfig.isNotifyConsumerIdsChangedEnable();
            int topicSysFlag = 0;
            if (data.isUnitMode()) {
                topicSysFlag = TopicSysFlag.buildSysFlag(false, true);
            }

            // retryTopic：%RETRY%{CONSUMER_GROUP}
            String newTopic = MixAll.getRetryTopic(data.getGroupName());

            // 创建重试Topic
            this.brokerController.getTopicConfigManager().createTopicInSendMessageBackMethod(
                newTopic,
                subscriptionGroupConfig.getRetryQueueNums(),
                PermName.PERM_WRITE | PermName.PERM_READ, topicSysFlag);
        }

        // 注册Consumer
        boolean changed = this.brokerController.getConsumerManager().registerConsumer(
            data.getGroupName(),
            clientChannelInfo,
            data.getConsumeType(),
            data.getMessageModel(),
            data.getConsumeFromWhere(),
            data.getSubscriptionDataSet(),
            isNotifyConsumerIdsChangedEnable
        );

        if (changed) {
            log.info("registerConsumer info changed {} {}",
                data.toString(),
                RemotingHelper.parseChannelRemoteAddr(ctx.channel())
            );
        }
    }

    // 注册Producer
    for (ProducerData data : heartbeatData.getProducerDataSet()) {
        this.brokerController.getProducerManager().registerProducer(data.getGroupName(), clientChannelInfo);
    }

    response.setCode(ResponseCode.SUCCESS);
    response.setRemark(null);
    return response;
}
```
消费者注册处理：

```java
// ConsumerManager.java
public boolean registerConsumer(final String group, final ClientChannelInfo clientChannelInfo,
    ConsumeType consumeType, MessageModel messageModel, ConsumeFromWhere consumeFromWhere,
    final Set<SubscriptionData> subList, boolean isNotifyConsumerIdsChangedEnable) {

    ConsumerGroupInfo consumerGroupInfo = this.consumerTable.get(group);
    if (null == consumerGroupInfo) {
        // 创建Consumer组
        ConsumerGroupInfo tmp = new ConsumerGroupInfo(group, consumeType, messageModel, consumeFromWhere);
        ConsumerGroupInfo prev = this.consumerTable.putIfAbsent(group, tmp);
        consumerGroupInfo = prev != null ? prev : tmp;
    }

    // 更新Channel
    boolean r1 = consumerGroupInfo.updateChannel(clientChannelInfo, consumeType, messageModel, consumeFromWhere);

    // 更新订阅信息
    boolean r2 = consumerGroupInfo.updateSubscription(subList);

    if (r1 || r2) {
        // 若有更新
        if (isNotifyConsumerIdsChangedEnable) {
            // 通知消费者ID发生变化，作消费Rebalance
            this.consumerIdsChangeListener.consumerIdsChanged(group, consumerGroupInfo.getAllChannel());
        }
    }

    return r1 || r2;
}
```

生产者注册处理：

```java
// ProducerManager.java
public void registerProducer(final String group, final ClientChannelInfo clientChannelInfo) {
    try {
        ClientChannelInfo clientChannelInfoFound = null;

        if (this.groupChannelLock.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            try {
                // 生产者实例信息
                HashMap<Channel, ClientChannelInfo> channelTable = this.groupChannelTable.get(group);
                if (null == channelTable) {
                    channelTable = new HashMap<>();
                    this.groupChannelTable.put(group, channelTable);
                }

                clientChannelInfoFound = channelTable.get(clientChannelInfo.getChannel());
                if (null == clientChannelInfoFound) {
                    // 新实例
                    channelTable.put(clientChannelInfo.getChannel(), clientChannelInfo);
                    log.info("new producer connected, group: {} channel: {}", group,
                        clientChannelInfo.toString());
                }
            } finally {
                this.groupChannelLock.unlock();
            }

            if (clientChannelInfoFound != null) {
                // 设置更新时间
                clientChannelInfoFound.setLastUpdateTimestamp(System.currentTimeMillis());
            }
        } else {
            log.warn("ProducerManager registerProducer lock timeout");
        }
    } catch (InterruptedException e) {
        log.error("", e);
    }
}
```




