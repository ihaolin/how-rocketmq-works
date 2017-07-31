# RocketMQ服务之NameServer

之前的讲述中，已经提到**RocketMQ**中的**NameServer**是专门设计的轻量级**名称服务**，其具有简单、可集群横吐扩展、无状态等特点。其主要负责：

1. **Broker**集群管理；
2. **路由**信息管理。

本文将从实现上探索这些功能的设计与实现。

## 服务启动

**NameServer**服务的启动入口在类[NamesrvStartup](../namesrv/src/main/java/org/apache/rocketmq/namesrv/NamesrvStartup.java)中：

```java
// NamesrvStartup.java
public static NamesrvController main0(String[] args) {

    // 设置RPC调用中的MQ版本号
    System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));

    if (null == System.getProperty(NettySystemConfig.COM_ROCKETMQ_REMOTING_SOCKET_SNDBUF_SIZE)) {
        // socket 发送buffer大小
        NettySystemConfig.socketSndbufSize = 4096;
    }

    if (null == System.getProperty(NettySystemConfig.COM_ROCKETMQ_REMOTING_SOCKET_RCVBUF_SIZE)) {
        // socket 接收buffer大小
        NettySystemConfig.socketRcvbufSize = 4096;
    }

    try {

        Options options = ServerUtil.buildCommandlineOptions(new Options());
        commandLine =
            ServerUtil.parseCmdLine("mqnamesrv", args, buildCommandlineOptions(options),
                new PosixParser());
        if (null == commandLine) {
            System.exit(-1);
            return null;
        }

        // NameServer相关配置
        final NamesrvConfig namesrvConfig = new NamesrvConfig();

        // NettyServer相关配置
        final NettyServerConfig nettyServerConfig = new NettyServerConfig();

        // 服务监听端口
        nettyServerConfig.setListenPort(9876);

        if (commandLine.hasOption('c')) {

            // 是否有配置文件
            String file = commandLine.getOptionValue('c');
            if (file != null) {
                InputStream in = new BufferedInputStream(new FileInputStream(file));
                properties = new Properties();
                properties.load(in);

                // 将配置文件中的配置
                MixAll.properties2Object(properties, namesrvConfig);
                MixAll.properties2Object(properties, nettyServerConfig);

                namesrvConfig.setConfigStorePath(file);

                System.out.printf("load config properties file OK, " + file + "%n");
                in.close();
            }
        }

        if (commandLine.hasOption('p')) {

            // 打印可配置项
            MixAll.printObjectProperties(null, namesrvConfig);
            MixAll.printObjectProperties(null, nettyServerConfig);
            System.exit(0);
        }

        // 将命令行参数设置到namesrvConfig中
        MixAll.properties2Object(ServerUtil.commandLine2Properties(commandLine), namesrvConfig);

        if (null == namesrvConfig.getRocketmqHome()) {
            // RocketMQ Home not set
            System.out.printf("Please set the " + MixAll.ROCKETMQ_HOME_ENV
                + " variable in your environment to match the location of the RocketMQ installation%n");
            System.exit(-2);
        }

        // 日志配置
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(lc);
        lc.reset();
        // $ROCKETMQ_HOME/conf/logback_namesrv.xml为日志配置文件
        configurator.doConfigure(namesrvConfig.getRocketmqHome() + "/conf/logback_namesrv.xml");
        final Logger log = LoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);

        // 打印配置
        MixAll.printObjectProperties(log, namesrvConfig);
        MixAll.printObjectProperties(log, nettyServerConfig);

        // 构建NamesrvController
        final NamesrvController controller = new NamesrvController(namesrvConfig, nettyServerConfig);

        // remember all configs to prevent discard
        controller.getConfiguration().registerConfig(properties);

        // 初始化NamesrvController，初始化内部各种组件
        boolean initResult = controller.initialize();
        if (!initResult) {
            // 初始化失败，关闭
            controller.shutdown();
            System.exit(-3);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            private volatile boolean hasShutdown = false;
            private AtomicInteger shutdownTimes = new AtomicInteger(0);

            @Override
            public void run() {
                synchronized (this) {
                    log.info("shutdown hook was invoked, " + this.shutdownTimes.incrementAndGet());
                    if (!this.hasShutdown) {
                        this.hasShutdown = true;
                        long begineTime = System.currentTimeMillis();
                        controller.shutdown();
                        long consumingTimeTotal = System.currentTimeMillis() - begineTime;
                        log.info("shutdown hook over, consuming time total(ms): " + consumingTimeTotal);
                    }
                }
            }
        }, "ShutdownHook"));

        // 启动NamesrvController
        controller.start();

        String tip = "The Name Server boot success. serializeType=" + RemotingCommand.getSerializeTypeConfigInThisServer();
        log.info(tip);
        System.out.printf(tip + "%n");

        return controller;
    } catch (Throwable e) {
        e.printStackTrace();
        System.exit(-1);
    }

    return null;
}
```

其中核心初始化工作由[NamesrvController](../namesrv/src/main/java/org/apache/rocketmq/namesrv/NamesrvController.java)完成：

```java
// NamesrvController.java
public class NamesrvController {
    
    // ...
    
    // 初始化
    public boolean initialize() {

        // 加载kv配置，及kvConfig.json文件
        this.kvConfigManager.load();

        // 初始化通信组件Server
        this.remotingServer = new NettyRemotingServer(this.nettyServerConfig, this.brokerHousekeepingService);

        // 初始化请求处理线程池
        this.remotingExecutor =
            Executors.newFixedThreadPool(nettyServerConfig.getServerWorkerThreads(), new ThreadFactoryImpl("RemotingExecutorThread_"));

        // 注册请求处理器
        this.registerProcessor();

        // 每10秒扫描未存活的Broker
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                NamesrvController.this.routeInfoManager.scanNotActiveBroker();
            }
        }, 5, 10, TimeUnit.SECONDS);

        // 每10秒打印配置表
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                NamesrvController.this.kvConfigManager.printAllPeriodically();
            }
        }, 1, 10, TimeUnit.MINUTES);

        return true;
    }
}

// 注册请求处理器
private void registerProcessor() {
    if (namesrvConfig.isClusterTest()) {
        // 集群测试环境
        this.remotingServer.registerDefaultProcessor(new ClusterTestRequestProcessor(this, namesrvConfig.getProductEnvName()),
            this.remotingExecutor);
    } else {
        // 使用默认的请求处理器
        this.remotingServer.registerDefaultProcessor(new DefaultRequestProcessor(this), this.remotingExecutor);
    }
}
```

默认请求处理器`DefaultRequestProcessor`主要负责与**集群配置**，**Broker集群**等相关的工作，初始化完成后，则会执行启动方法`start()`，其主要是作Server通信的启动：

```java
// NettyRemotingServer.java
public class NettyRemotingServer extends NettyRemotingAbstract implements RemotingServer {
    
    // ...
    
    @Override
    public void start() {

        // 初始化netty线程池
        this.defaultEventExecutorGroup = new DefaultEventExecutorGroup(
            nettyServerConfig.getServerWorkerThreads(),
            new ThreadFactory() {

                private AtomicInteger threadIndex = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "NettyServerCodecThread_" + this.threadIndex.incrementAndGet());
                }
            });

        ServerBootstrap childHandler =
            this.serverBootstrap.group(this.eventLoopGroupBoss, this.eventLoopGroupSelector).channel(NioServerSocketChannel.class)
                // 最大积压的已经完成握手但还未被accept的连接数
                .option(ChannelOption.SO_BACKLOG, 1024)
                // 让端口释放后立即就可以被再次使用
                .option(ChannelOption.SO_REUSEADDR, true)
                // 开启KeepAlive
                .option(ChannelOption.SO_KEEPALIVE, false)
                // 防止当数据包太小时，发生ack delay问题，即禁用nagle算法
                .childOption(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_SNDBUF, nettyServerConfig.getServerSocketSndBufSize())
                .option(ChannelOption.SO_RCVBUF, nettyServerConfig.getServerSocketRcvBufSize())
                .localAddress(new InetSocketAddress(this.nettyServerConfig.getListenPort()))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(
                            defaultEventExecutorGroup,
                            // 协议编码，RemotingCommand -> byte[]
                            new NettyEncoder(),
                            // 协议解码，byte[] -> RemotingCommand
                            new NettyDecoder(),
                            new IdleStateHandler(0, 0, nettyServerConfig.getServerChannelMaxIdleTimeSeconds()),
                            // Netty连接事件相关处理
                            new NettyConnetManageHandler(),
                            // 该处理器用于接收消息前置处理
                            new NettyServerHandler());
                    }
                });

        if (nettyServerConfig.isServerPooledByteBufAllocatorEnable()) {
            childHandler.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        }

        try {
            // 开始监听
            ChannelFuture sync = this.serverBootstrap.bind().sync();
            InetSocketAddress addr = (InetSocketAddress) sync.channel().localAddress();
            this.port = addr.getPort();
        } catch (InterruptedException e1) {
            throw new RuntimeException("this.serverBootstrap.bind().sync() InterruptedException", e1);
        }

        if (this.channelEventListener != null) {
            // 启动Netty事件执行器
            // 用于将Netty通信中产生的各种事件，分发至listener
            // 最终就是分发至上述的BrokerHousekeepingService服务
            this.nettyEventExecutor.start();
        }

        this.timer.scheduleAtFixedRate(new TimerTask() {

            @Override
            public void run() {
                try {
                    // 启动扫描响应表的任务
                    NettyRemotingServer.this.scanResponseTable();
                } catch (Exception e) {
                    log.error("scanResponseTable exception", e);
                }
            }
        }, 1000 * 3, 1000);
    }
    
}
```
以上，则是**NameServer**服务启动的相关细节，之后则是处理来自其他组件的请求。

## Broker及路由信息管理

在**NameServer**中，Broker及路由信息是有Broker主动发起的，主要通过[BrokerController](../broker/src/main/java/org/apache/rocketmq/broker/BrokerController.java)的`registerBrokerAll()`方法实现：

```java
// BrokerController.java
public synchronized void registerBrokerAll(final boolean checkOrderConfig, boolean oneway) {

    // 构建本地topic信息
    TopicConfigSerializeWrapper topicConfigWrapper = this.getTopicConfigManager().buildTopicConfigSerializeWrapper();

    if (!PermName.isWriteable(this.getBrokerConfig().getBrokerPermission())
        || !PermName.isReadable(this.getBrokerConfig().getBrokerPermission())) {
        // Broker不可读，或不可写时
        // 更新当前Broker所有topic的权限
        ConcurrentHashMap<String, TopicConfig> topicConfigTable = new ConcurrentHashMap<String, TopicConfig>();
        for (TopicConfig topicConfig : topicConfigWrapper.getTopicConfigTable().values()) {
            TopicConfig tmp =
                new TopicConfig(topicConfig.getTopicName(), topicConfig.getReadQueueNums(), topicConfig.getWriteQueueNums(),
                    this.brokerConfig.getBrokerPermission());
            topicConfigTable.put(topicConfig.getTopicName(), tmp);
        }
        topicConfigWrapper.setTopicConfigTable(topicConfigTable);
    }

    // 发起注册请求
    RegisterBrokerResult registerBrokerResult = this.brokerOuterAPI.registerBrokerAll(
        this.brokerConfig.getBrokerClusterName(),               // 集群名称
        this.getBrokerAddr(),                                   // Broker对外地址
        this.brokerConfig.getBrokerName(),                      // Broker名称
        this.brokerConfig.getBrokerId(),                        // BrokerId
        this.getHAServerAddr(),                                 // Broker对内地址
        topicConfigWrapper,                                     // Topic信息
        this.filterServerManager.buildNewFilterServerList(),    // FilterServer地址列表
        oneway,                                                 // 是否是Oneway调用
        this.brokerConfig.getRegisterBrokerTimeoutMills());     // 注册超时时间(毫秒)

    if (registerBrokerResult != null) {
        // 非Oneway调用时
        if (this.updateMasterHAServerAddrPeriodically && registerBrokerResult.getHaServerAddr() != null) {
            // 更新haServer地址
            this.messageStore.updateHaMasterAddress(registerBrokerResult.getHaServerAddr());
        }

        // Slave设置Broker地址
        this.slaveSynchronize.setMasterAddr(registerBrokerResult.getMasterAddr());

        if (checkOrderConfig) {
            // 更新本地topic信息
            this.getTopicConfigManager().updateOrderTopicConfig(registerBrokerResult.getKvTable());
        }
    }
}
```
```java
// BrokerOuterAPI.java
public RegisterBrokerResult registerBrokerAll(
    final String clusterName,
    final String brokerAddr,
    final String brokerName,
    final long brokerId,
    final String haServerAddr,
    final TopicConfigSerializeWrapper topicConfigWrapper,
    final List<String> filterServerList,
    final boolean oneway,
    final int timeoutMills) {
    RegisterBrokerResult registerBrokerResult = null;

    // 向各NameServer发起注册请求
    List<String> nameServerAddressList = this.remotingClient.getNameServerAddressList();
    if (nameServerAddressList != null) {
        for (String namesrvAddr : nameServerAddressList) {
            try {

                // 注册Broker
                RegisterBrokerResult result = this.registerBroker(namesrvAddr, clusterName, brokerAddr, brokerName, brokerId,
                    haServerAddr, topicConfigWrapper, filterServerList, oneway, timeoutMills);

                if (result != null) {
                    registerBrokerResult = result;
                }

                log.info("register broker to name server {} OK", namesrvAddr);
            } catch (Exception e) {
                log.warn("registerBroker Exception, {}", namesrvAddr, e);
            }
        }
    }

    return registerBrokerResult;
}

private RegisterBrokerResult registerBroker(
    final String namesrvAddr,
    final String clusterName,
    final String brokerAddr,
    final String brokerName,
    final long brokerId,
    final String haServerAddr,
    final TopicConfigSerializeWrapper topicConfigWrapper,
    final List<String> filterServerList,
    final boolean oneway,
    final int timeoutMills
) throws RemotingCommandException, MQBrokerException, RemotingConnectException, RemotingSendRequestException, RemotingTimeoutException,
    InterruptedException {

    // 构造Request Header
    RegisterBrokerRequestHeader requestHeader = new RegisterBrokerRequestHeader();
    requestHeader.setBrokerAddr(brokerAddr);
    requestHeader.setBrokerId(brokerId);
    requestHeader.setBrokerName(brokerName);
    requestHeader.setClusterName(clusterName);
    requestHeader.setHaServerAddr(haServerAddr);
    RemotingCommand request = RemotingCommand.createRequestCommand(RequestCode.REGISTER_BROKER, requestHeader);

    // 构造Request Body
    RegisterBrokerBody requestBody = new RegisterBrokerBody();
    // Topic信息
    requestBody.setTopicConfigSerializeWrapper(topicConfigWrapper);
    // FilterServer列表
    requestBody.setFilterServerList(filterServerList);
    // JSON编码
    request.setBody(requestBody.encode());

    if (oneway) {
        try {
            // Oneway调用
            this.remotingClient.invokeOneway(namesrvAddr, request, timeoutMills);
        } catch (RemotingTooMuchRequestException e) {
            // Ignore
        }
        // 不关心响应结果
        return null;
    }

    // 同步调用
    RemotingCommand response = this.remotingClient.invokeSync(namesrvAddr, request, timeoutMills);
    assert response != null;
    switch (response.getCode()) {
        case ResponseCode.SUCCESS: {

            // 获取相应结果
            RegisterBrokerResponseHeader responseHeader =
                (RegisterBrokerResponseHeader) response.decodeCommandCustomHeader(RegisterBrokerResponseHeader.class);
            RegisterBrokerResult result = new RegisterBrokerResult();
            // 设置master
            result.setMasterAddr(responseHeader.getMasterAddr());
            // 设置haServer
            result.setHaServerAddr(responseHeader.getHaServerAddr());
            if (response.getBody() != null) {
                // 设置kv配置
                result.setKvTable(KVTable.decode(response.getBody(), KVTable.class));
            }
            return result;
        }
        default:
            break;
    }

    throw new MQBrokerException(response.getCode(), response.getRemark());
}
```

当**NameServer**接收到请求后，会实时更新**Broker**及路由信息，主要由[DefaultRequestProcessor](../namesrv/src/main/java/org/apache/rocketmq/namesrv/processor/DefaultRequestProcessor.java)的`registerBrokerWithFilterServer()`方法处理：

```java
// DefaultRequestProcessor.java
public RemotingCommand registerBrokerWithFilterServer(ChannelHandlerContext ctx, RemotingCommand request)
        throws RemotingCommandException {

    // 构建Response
    final RemotingCommand response = RemotingCommand.createResponseCommand(RegisterBrokerResponseHeader.class);

    // 构建Response Header
    final RegisterBrokerResponseHeader responseHeader = (RegisterBrokerResponseHeader) response.readCustomHeader();

    // 反解Request Header
    final RegisterBrokerRequestHeader requestHeader =
        (RegisterBrokerRequestHeader) request.decodeCommandCustomHeader(RegisterBrokerRequestHeader.class);

    // 反解Request Body
    RegisterBrokerBody registerBrokerBody = new RegisterBrokerBody();
    if (request.getBody() != null) {
        registerBrokerBody = RegisterBrokerBody.decode(request.getBody(), RegisterBrokerBody.class);
    } else {
        registerBrokerBody.getTopicConfigSerializeWrapper().getDataVersion().setCounter(new AtomicLong(0));
        registerBrokerBody.getTopicConfigSerializeWrapper().getDataVersion().setTimestamp(0);
    }

    // 注册Broker
    RegisterBrokerResult result = this.namesrvController.getRouteInfoManager().registerBroker(
        requestHeader.getClusterName(),
        requestHeader.getBrokerAddr(),
        requestHeader.getBrokerName(),
        requestHeader.getBrokerId(),
        requestHeader.getHaServerAddr(),
        registerBrokerBody.getTopicConfigSerializeWrapper(),
        registerBrokerBody.getFilterServerList(),
        ctx.channel());

    responseHeader.setHaServerAddr(result.getHaServerAddr());
    responseHeader.setMasterAddr(result.getMasterAddr());

    // 返回Topic配置信息
    byte[] jsonValue = this.namesrvController.getKvConfigManager().getKVListByNamespace(NamesrvUtil.NAMESPACE_ORDER_TOPIC_CONFIG);
    response.setBody(jsonValue);

    response.setCode(ResponseCode.SUCCESS);
    response.setRemark(null);
    return response;
}
```
**路由信息**主要由[RouteInfoManager](../namesrv/src/main/java/org/apache/rocketmq/namesrv/routeinfo/RouteInfoManager.java)实现：
```java
// RouteInfoManager.java
public RegisterBrokerResult registerBroker(
    final String clusterName,
    final String brokerAddr,
    final String brokerName,
    final long brokerId,
    final String haServerAddr,
    final TopicConfigSerializeWrapper topicConfigWrapper,
    final List<String> filterServerList,
    final Channel channel) {
    RegisterBrokerResult result = new RegisterBrokerResult();
    try {
        try {

            // 加写锁
            this.lock.writeLock().lockInterruptibly();

            // 获取集群下的所有broker
            Set<String> brokerNames = this.clusterAddrTable.get(clusterName);
            if (null == brokerNames) {
                // 不存在，则创建集群
                brokerNames = new HashSet<String>();
                this.clusterAddrTable.put(clusterName, brokerNames);
            }
            // 添加broker
            brokerNames.add(brokerName);

            // broker是否首次注册
            boolean registerFirst = false;
            // 获取broker数据信息
            BrokerData brokerData = this.brokerAddrTable.get(brokerName);
            if (null == brokerData) {

                // 不存在，则创建
                registerFirst = true;
                brokerData = new BrokerData();
                brokerData.setBrokerName(brokerName);
                HashMap<Long, String> brokerAddrs = new HashMap<Long, String>();
                brokerData.setBrokerAddrs(brokerAddrs);

                this.brokerAddrTable.put(brokerName, brokerData);
            }
            // 添加当前broker到映射表
            String oldAddr = brokerData.getBrokerAddrs().put(brokerId, brokerAddr);
            registerFirst = registerFirst || (null == oldAddr);

            if (null != topicConfigWrapper && MixAll.MASTER_ID == brokerId) {
                // 如果topic配置信息不为空，且为master

                if (this.isBrokerTopicConfigChanged(brokerAddr, topicConfigWrapper.getDataVersion()) || registerFirst) {
                    // topic配置发生变化，或者是broker首次注册

                    ConcurrentHashMap<String, TopicConfig> tcTable = topicConfigWrapper.getTopicConfigTable();
                    if (tcTable != null) {
                        for (Map.Entry<String, TopicConfig> entry : tcTable.entrySet()) {
                            // 创建或更新队列信息
                            this.createAndUpdateQueueData(brokerName, entry.getValue());
                        }
                    }
                }
            }

            // 更新存活Broker表
            BrokerLiveInfo prevBrokerLiveInfo = this.brokerLiveTable.put(brokerAddr,
                new BrokerLiveInfo(
                    System.currentTimeMillis(),
                    topicConfigWrapper.getDataVersion(),
                    channel,
                    haServerAddr));
            if (null == prevBrokerLiveInfo) {
                // 第一次注册
                log.info("a new broker registered, {} HAServer: {}", brokerAddr, haServerAddr);
            }

            if (filterServerList != null) {
                // 更新FilterServer
                if (filterServerList.isEmpty()) {
                    this.filterServerTable.remove(brokerAddr);
                } else {
                    this.filterServerTable.put(brokerAddr, filterServerList);
                }
            }

            if (MixAll.MASTER_ID != brokerId) {
                // 如果是slave
                // 在相应结果中返回master和haServer
                String masterAddr = brokerData.getBrokerAddrs().get(MixAll.MASTER_ID);
                if (masterAddr != null) {
                    BrokerLiveInfo brokerLiveInfo = this.brokerLiveTable.get(masterAddr);
                    if (brokerLiveInfo != null) {
                        result.setHaServerAddr(brokerLiveInfo.getHaServerAddr());
                        result.setMasterAddr(masterAddr);
                    }
                }
            }
        } finally {
            // 释放写锁
            this.lock.writeLock().unlock();
        }
    } catch (Exception e) {
        log.error("registerBroker Exception", e);
    }

    return result;
}
```
**Broker**启动之后，会每隔30s注册本地信息到**NameServer**，若超过120s未注册过，则认为该Broker已失效。

