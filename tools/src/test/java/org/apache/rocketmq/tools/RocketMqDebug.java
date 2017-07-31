package org.apache.rocketmq.tools;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.srvutil.ServerUtil;
import org.apache.rocketmq.tools.command.topic.UpdateTopicSubCommand;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Author: linhao
 * Email: linhao09@meituan.com
 */
public class RocketMqDebug {

  private static final String CLUSTER_NAME = "RMQ-CLUSTER-DEBUG";

  private static final String BROKER_1_0_ADDR = "127.0.0.1:11110";

  private static final String DEBUG_TOPIC = "DEBUG-TOPIC";

  private static final String DEBUG_PRODUCER_GROUP = "RMQ-PRODUCER-GROUP-DEBUG";

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

    producer.setNamesrvAddr("127.0.0.1:9876");

    producer.start();

    Message msg = new Message(DEBUG_TOPIC, "", "Hello, RocketMQ".getBytes("UTF-8"));

    SendResult r = producer.send(msg);

    System.out.println(r);

  }
}
