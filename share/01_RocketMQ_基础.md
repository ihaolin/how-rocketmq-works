# RocketMQ 基础

## RocketMQ 简介

> RocketMQ是一款高性能、低延迟、高可靠及具备万亿级消息级别的分布式消息中间件，其采用了以**拉模式**为主，**推模式**为辅的消息引擎，并具有可靠重试、基于文件存储的分布式事务等特性，在阿里巴巴历经了多次双11的考验。现已成为Apache基金孵化项目，未来预计打造为Apache顶级项目。

## RocketMQ vs Kafka vs ActiveMQ

MQ产品  | Client语言 | 协议 | 顺序性 | 过滤 | Server端重试  | 持久化 | 追溯 | 优先级 | 追踪 | 配置化 | 运维工具
:-------------: | :-------------: | :-------------: | :-------------: | :-------------: | :-------------: | :-------------: | :-------------: | :-------------: | :-------------: | :-------------: | :-------------: | :-------------:
ActiveMQ | Java,.NET,C++,... | Push, JMS, MQTT, AMQP, ... | 独占队列有序 | ✅ | ❌ | 高性能(DB) | ✅ | ✅ | ❌ | 须优化 | ✅
Kafka | Java,Scala,... | Pull, TCP | 分区内有序 | ✅ | ❌ |  高性能(文件) | 支持offset指示 | ❌ | ❌ | kv配置 | 仅命令行
RocketMQ | Java, .NET, C++| Pull,TCP,JMS | 无热点严格有序 | ✅ | ✅ | 高性能，低延迟(文件) | 支持时间戳和offset | ❌ | ✅ | 少量配置 | web及命令行

## RocketMQ 架构设计

![](screenshots/rmq-basic-arc.png)

### NameServer 集群

> NameServer集群提供轻量的服务发现和路由能力，集群中每个NameServer都会记录完整的路由信息，并提供相应的读写服务，且支持快速的存储扩展。

**NameServer**主要提供两方面的功能：

1. **Broker管理**：接收来自Broker集群的注册请求，并通过心跳检测Broker的存活状态；
2. **路由管理**：每个NameServer都将存有Broker集群的路由信息，及供客户端查询的队列信息。

### Broker 集群

> Broker集群通过提供轻量的Topic和Queue机制来处理消息存储，其支持Push和Pull两种模式，并提供强大的峰值填充和堆积百亿消息容量的能力。除此外，Broker也提供灾难恢复、丰富的指标统计和警报机制，这些在传统的消息中间件里很少见。

**Broker**主要负责消息存储及分发，消息查询，HA的保证，其包含了几个重要的子模块：

![](screenshots/rmq-basic-component.png)

+ **Remoting Module**，broker的入口，处理来自客户端的请求；
+ **Client Manager**，管理客户端(生产者/消费者)，并维护消费者的Topic订阅信息；
+ **Store Service**，提供简单的API，用于存储或查询消息；
+ **HA Service**，负责**Broker**Master和Slave之间的数据同步；
+ **Index Service**，通过特定的Key，为消息构建索引，便于提供快速的消息查询。


### Producer 集群

> Producer集群支持分布式部署，Producer通过多种负载均衡模式发送消息到Broker集群，且发送过程支持快速失败及低延迟。

### Consumer 集群

> Consumer集群也支持分布式部署，以Push和Pull的方式获取消息(集群消费和广播消费)，并提供了实时的消息订阅机制。

## RocketMQ 关键特性

### 1.单机支持1万以上持久化队列

RocketMQ中消息存储的逻辑视图：

![](screenshots/rmq-msg-store-logic-view.png)

如图所示，消息存储方案大致为：

1. 所有数据单独存储到一个**Commit Log**，完全**顺序写**，**随机读**；
2. 对最终用户可见的消费队列(**Consume Queue**)，实际只存储消息在CommitLog的位置(**offset**)信息，并且**串行方式**刷盘；

这样做的优势在于：

1. 队列轻量化，单个队列数据量非常少；
2. 对磁盘的访问串行化，避免磁盘竟争，并且会因为队列增加导致IOWAIT增高。

但上述方案仍面临几个问题：

1. 对于**Commit Log**，写虽然是**顺序写**，但是读却发成了**随机读**；
2. 读一条消息，会先读**Consume Queue**，再读**Commit Log**，增加了开销；
3. 要保证**Commit Log**与**Consume Queue**完全的一致，增加了编程的复杂度。

如何解决上述几个问题：

1. **随机读问题**：尽可能让读命中**PAGECACHE**，减少IO读操作，所以系统内存越大越好。如果系统中堆积的消息过多，读数据要访问磁盘会不会由于随机读导致系统性能急剧下降，答案是否定的：

	+ 访问**PAGECACHE**时，即使只访问1k的消息，系统也会提前**预读**出更多数据，在下次读时，就有可能命中内存；
	+ 随机访问**Commit Log**磁盘数据，系统IO调度算法设置为[NOOP](https://en.wikipedia.org/wiki/Noop_scheduler)方式，会在一定程度上将**完全随机读**发成**顺序跳跃方式**，而顺序跳跃方式读较完全的随机读性能会高5倍以上。

## 参考文献

+ [Apache RocketMQ背后的设计思路与最佳实践](http://jm.taobao.org/2017/03/09/20170309/)；

+ [专访RocketMQ联合创始人：项目思路、技术细节和未来规划](http://www.infoq.com/cn/news/2017/02/RocketMQ-future-idea)；

+ [RocketMQ官网](https://rocketmq.incubator.apache.org/)；

+ [RocketMQ开发指南](rokectmq_dev_guide.pdf)；

+ [随机读 vs 顺序读](http://www.violin-memory.com/blog/understanding-io-random-vs-sequential/)；

+ [磁盘I/O那些事](https://tech.meituan.com/about-desk-io.html)。