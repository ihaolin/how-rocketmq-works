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


## 参考文献

+ [Apache RocketMQ背后的设计思路与最佳实践](http://jm.taobao.org/2017/03/09/20170309/)；

+ [专访RocketMQ联合创始人：项目思路、技术细节和未来规划](http://www.infoq.com/cn/news/2017/02/RocketMQ-future-idea)；

+ [RocketMQ官网](https://rocketmq.incubator.apache.org/)；

+ [RocketMQ开发指南](rokectmq_dev_guide.pdf)；

+ [随机读 vs 顺序读](http://www.violin-memory.com/blog/understanding-io-random-vs-sequential/)。