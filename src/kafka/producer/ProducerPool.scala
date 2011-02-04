/*
 * Copyright 2010 LinkedIn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package kafka.producer

import async.{AsyncProducerConfig, AsyncProducer}
import kafka.message.ByteBufferMessageSet
import java.util.Properties
import kafka.serializer.Encoder
import org.apache.log4j.Logger
import kafka.common.InvalidConfigException
import java.util.concurrent.{ConcurrentMap, ConcurrentHashMap}
import kafka.cluster.{Partition, Broker}
import kafka.api.ProducerRequest

class ProducerPool[V](private val config: ProducerConfig,
                      private val serializer: Encoder[V],
                      private val syncProducers: ConcurrentMap[Int, SyncProducer],
                      private val asyncProducers: ConcurrentMap[Int, AsyncProducer[V]]) {
  private val logger = Logger.getLogger(classOf[ProducerPool[V]])
  private var sync: Boolean = true
  config.producerType match {
    case "sync" =>
    case "async" => sync = false
    case _ => throw new InvalidConfigException("Valid values for producer.type are sync/async")
  }

  def this(config: ProducerConfig, serializer: Encoder[V]) = this(config, serializer,
                                                                  new ConcurrentHashMap[Int, SyncProducer](),
                                                                  new ConcurrentHashMap[Int, AsyncProducer[V]]())
  /**
   * add a new producer, either synchronous or asynchronous, connecting
   * to the specified broker 
   * @param bid the id of the broker
   * @param host the hostname of the broker
   * @param port the port of the broker
   */
  def addProducer(broker: Broker) {
    if(sync) {
        val props = new Properties()
        props.put("host", broker.host)
        props.put("port", broker.port.toString)
        props.put("buffer.size", config.bufferSize.toString)
        props.put("connect.timeout.ms", config.connectTimeoutMs.toString)
        props.put("reconnect.interval", config.reconnectInterval.toString)
        val producer = new SyncProducer(new SyncProducerConfig(props))
        logger.info("Creating sync producer for broker id = " + broker.id + " at " + broker.host + ":" + broker.port)
        syncProducers.put(broker.id, producer)
    } else {
        val props = new Properties()
        props.put("host", broker.host)
        props.put("port", broker.port.toString)
        props.put("serializer.class", config.serializerClass)
        val producer = new AsyncProducer[V](new AsyncProducerConfig(props))
        logger.info("Creating async producer for broker id = " + broker.id + " at " + broker.host + ":" + broker.port)
        asyncProducers.put(broker.id, producer)
    }
  }

  /**
   * selects either a synchronous or an asynchronous producer, for
   * the specified broker id and calls the send API on the selected
   * producer to publish the data to the specified broker partition
   * @param poolData the producer pool request object
   */
  def send(poolData: ProducerPoolData[V]*) {
    val distinctBrokers = poolData.map(pd => pd.getBidPid.brokerId).distinct
    var remainingRequests = poolData.toSeq
    distinctBrokers.foreach { bid =>
      val requestsForThisBid = remainingRequests partition (_.getBidPid.brokerId == bid)
      remainingRequests = requestsForThisBid._2

      if(sync) {
        val producerRequests = requestsForThisBid._1.map(req => new ProducerRequest(req.getTopic, req.getBidPid.partId,
          new ByteBufferMessageSet(req.getData.map(d => serializer.toMessage(d)): _*)))
        logger.debug("Fetching sync producer for broker id: " + bid)
        val producer = syncProducers.get(bid)
        if(producer != null) {
          if(producerRequests.size > 1)
            producer.multiSend(producerRequests.toArray)
          else
            producer.send(producerRequests(0).topic, producerRequests(0).partition, producerRequests(0).messages)
          logger.info("Sending message to broker " + bid)
        }
      }else {
        logger.debug("Fetching async producer for broker id: " + bid)
        val producer = asyncProducers.get(bid)
        if(producer != null)
          requestsForThisBid._1.foreach { req =>
            req.getData.foreach(d => producer.send(req.getTopic, d, req.getBidPid.partId))
          }
      }
    }
  }

  /**
   * Closes all the producers in the pool
   */
  def close() = {
    config.producerType match {
      case "sync" =>
        logger.info("Closing all sync producers")
        val iter = syncProducers.values.iterator
        while(iter.hasNext)
          iter.next.close
      case "async" =>
        logger.info("Closing all async producers")
        val iter = asyncProducers.values.iterator
        while(iter.hasNext)
          iter.next.close
    }
  }

  /**
   * This constructs and returns the request object for the producer pool
   * @param topic the topic to which the data should be published
   * @param bidPid the broker id and partition id
   * @param data the data to be published
   */
  def getProducerPoolData(topic: String, bidPid: Partition, data: Seq[V]): ProducerPoolData[V] = {
    new ProducerPoolData[V](topic, bidPid, data)
  }

  class ProducerPoolData[V](topic: String,
                            bidPid: Partition,
                            data: Seq[V]) {
    def getTopic: String = topic
    def getBidPid: Partition = bidPid
    def getData: Seq[V] = data
  }
}
