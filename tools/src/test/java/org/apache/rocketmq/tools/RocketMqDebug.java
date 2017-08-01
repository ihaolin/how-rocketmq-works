package org.apache.rocketmq.tools;

import java.io.UnsupportedEncodingException;
import java.util.List;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.srvutil.ServerUtil;
import org.apache.rocketmq.tools.command.topic.UpdateTopicSubCommand;
import org.junit.Test;

/**
 * Author: linhao
 * Email: linhao09@meituan.com
 */
public class RocketMqDebug {

  private static final String CLUSTER_NAME = "RMQ-CLUSTER-DEBUG";

  private static final String NAME_SERVERS = "127.0.0.1:9876";

  private static final String BROKER_1_0_ADDR = "127.0.0.1:11110";

  private static final String DEBUG_TOPIC = "DEBUG-TOPIC";

  private static final String DEBUG_PRODUCER_GROUP = "RMQ-PRODUCER-GROUP-DEBUG";

  private static final String DEBUG_COMSUMER_GROUP = "RMQ-CONSUMER-GROUP-DEBUG";

  @Test
  public void testUpdateTopic(){

    UpdateTopicSubCommand cmd = new UpdateTopicSubCommand();
    Options options = ServerUtil.buildCommandlineOptions(new Options());
    String[] args = new String[] {
      "-b " + BROKER_1_0_ADDR,
      "-c " + CLUSTER_NAME,
      "-t " + DEBUG_TOPIC
    };

    final CommandLine commandLine =
        ServerUtil.parseCmdLine("mqadmin " + cmd.commandName(), args, cmd.buildCommandlineOptions(options), new PosixParser());

    cmd.execute(commandLine, options, null);

  }

  @Test
  public void testSendMsg() throws Exception {

    DefaultMQProducer producer = new DefaultMQProducer(DEBUG_PRODUCER_GROUP);

    producer.setNamesrvAddr(NAME_SERVERS);

    producer.start();

    Message msg = new Message(DEBUG_TOPIC, "", "Hello, RocketMQ".getBytes("UTF-8"));

    SendResult r = producer.send(msg);

    System.out.println(r);

  }

  @Test
  public void testRecvMsg() throws Exception {

    final DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(DEBUG_COMSUMER_GROUP);

    consumer.setNamesrvAddr(NAME_SERVERS);

    consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);

    consumer.subscribe(DEBUG_TOPIC, "*");

    consumer.setMessageListener(new MessageListenerConcurrently() {
      @Override
      public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
          ConsumeConcurrentlyContext context) {

        try {

          for (MessageExt msg : msgs){
            System.err.println("Receive msg: msgId=" + msg.getMsgId() + ", content=" + new String(msg.getBody(), "UTF-8"));
          }

        } catch (UnsupportedEncodingException e) {
          e.printStackTrace();
        }

        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
      }
    });

    consumer.start();

    Thread.sleep(10000000000L);

  }
}
