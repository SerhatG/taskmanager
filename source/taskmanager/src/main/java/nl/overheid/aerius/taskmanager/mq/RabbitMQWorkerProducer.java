/*
 * Copyright the State of the Netherlands
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 */
package nl.overheid.aerius.taskmanager.mq;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;

import nl.overheid.aerius.taskmanager.adaptor.WorkerProducer;
import nl.overheid.aerius.taskmanager.client.BrokerConnectionFactory;
import nl.overheid.aerius.taskmanager.client.QueueConstants;
import nl.overheid.aerius.taskmanager.client.mq.QueueUpdateHandler;
import nl.overheid.aerius.taskmanager.client.mq.RabbitMQQueueMonitor;
import nl.overheid.aerius.taskmanager.domain.Message;

/**
 * RabbitMQ implementation of a {@link WorkerProducer}.
 */
class RabbitMQWorkerProducer implements WorkerProducer {

  protected static final String WORKER_REPLY_AFFIX = ".reply";

  private static final Logger LOG = LoggerFactory.getLogger(RabbitMQWorkerProducer.class);

  private static final int DEFAULT_RETRY_SECONDS = 10;

  private RabbitMQQueueMonitor wpManager;
  private final BrokerConnectionFactory factory;
  private final String workerQueueName;

  private WorkerFinishedHandler workerFinishedHandler;
  private boolean isShutdown;

  public RabbitMQWorkerProducer(final BrokerConnectionFactory factory, final String workerQueueName) {
    this.factory = factory;
    this.workerQueueName = workerQueueName;
  }

  @Override
  public void setWorkerFinishedHandler(final WorkerFinishedHandler workerFinishedHandler) {
    this.workerFinishedHandler = workerFinishedHandler;
  }

  @Override
  public void start(final ExecutorService executorService, final QueueUpdateHandler workerPool) throws IOException {
    wpManager = new RabbitMQQueueMonitor(factory.getConnectionConfiguration());
    wpManager.addQueueUpdateHandler(workerQueueName, workerPool);
    executorService.execute(wpManager);
    tryStartReplyConsumer();
  }

  @Override
  public void forwardMessage(final Message<?> message) throws IOException {
    final RabbitMQMessage rabbitMQMessage = (RabbitMQMessage) message;
    // Do we set the replyTo to something fake?
    // or do we expect worker to send instead of CC the message?
    final Channel channel = factory.getConnection().createChannel();
    try {
      channel.queueDeclare(workerQueueName, true, false, false, null);
      final BasicProperties.Builder forwardBuilder = rabbitMQMessage.getProperties().builder();
      // new header map (even in case of existing headers, original can be a UnmodifiableMap)
      final Map<String, Object> headers = rabbitMQMessage.getProperties().getHeaders() == null ? new HashMap<>()
          : new HashMap<>(rabbitMQMessage.getProperties().getHeaders());

      // we want to be notified when a worker has finished it's job.
      // To do this, we set our own property, replyCC.
      // It's the worker implementation (through taskmanager client) to use this property to return a message.
      // (either through RabbitMQ CC-mechanism or by sending an empty message to the replyQueue)
      headers.put(QueueConstants.TASKMANAGER_REPLY_QUEUE, getWorkerReplyQueue());
      forwardBuilder.headers(headers);
      final BasicProperties forwardProperties = forwardBuilder.deliveryMode(2).build();
      channel.basicPublish("", workerQueueName, forwardProperties, rabbitMQMessage.getBody());
    } finally {
      try {
        channel.close();
      } catch (final IOException e) {
        // eat error.
      }
    }
  }

  @Override
  public void shutdown() {
    isShutdown = true;
    wpManager.shutdown();
  }

  private String getWorkerReplyQueue() {
    return workerQueueName + WORKER_REPLY_AFFIX;
  }

  private void tryStartReplyConsumer() {
    try {
      boolean warn = true;
      while (!isShutdown) {
        try {
          final Connection connection = factory.getConnection();
          connection.addShutdownListener(new ShutdownListener() {

            @Override
            public void shutdownCompleted(final ShutdownSignalException cause) {
              tryStartReplyConsumer();
            }
          });
          startReplyConsumer(connection);
          LOG.info("Succesfully (re)started reply consumer for queue {}", workerQueueName);
          break;
        } catch (final ShutdownSignalException | IOException e1) {
          if (warn) {
            LOG.warn("(Re)starting reply consumer for queue {} failed, retrying in a while", workerQueueName, e1);
            warn = false;
          }
          Thread.sleep(TimeUnit.SECONDS.toMillis(DEFAULT_RETRY_SECONDS));
        }
      }
    } catch (final InterruptedException e2) {
      LOG.debug("Starting reply consumer interrupted", e2);
      Thread.currentThread().interrupt();
    }
  }

  private void startReplyConsumer(final Connection connection) throws IOException {
    final Channel replyChannel = connection.createChannel();
    // Create an exclusive reply queue with predefined name (so we can set
    // a replyCC header).
    // Queue will be deleted once taskmanager is down.
    // reply queue is not durable because the system will 'reboot' after connection problems anyway.
    // Making it durable would only make sense if we'd keep track of tasks-in-progress during shutdown/startup.
    final String workerReplyQueue = getWorkerReplyQueue();
    replyChannel.queueDeclare(workerReplyQueue, false, true, true, null);
    // ensure the worker queue is around as well (so we can retrieve number of customers later on).
    // Worker queue is durable and non-exclusive with autodelete off.
    replyChannel.queueDeclare(workerQueueName, true, false, false, null);
    replyChannel.basicConsume(workerReplyQueue, true, workerReplyQueue, new DefaultConsumer(replyChannel) {
      @Override
      public void handleDelivery(final String consumerTag, final Envelope envelope, final BasicProperties properties, final byte[] body) {
        workerFinishedHandler.onWorkerFinished(properties.getMessageId());
      }
    });
  }
}