package org.aiotrade.lib.amqp

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.Consumer
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.ShutdownListener
import com.rabbitmq.client.ShutdownSignalException
import com.rabbitmq.utility.Utility
import java.io.IOException
import java.io.InvalidClassException
import java.util.Timer
import java.util.TimerTask
import java.util.logging.Logger
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.Level
/**
 * @Note we use plain sync Publisher/Reactor instead of actor based async model here, because:
 * 1. It seems that when actor model is mixed with a hard coded thread (AMQPConnection has a MainLoop thread),
 *    the scheduler of actor may deley delivery message, that causes unacceptable latency for amqp messages
 * 2. Unlick indicator, tser etc, we do not need async, parallel scale for amcp clients
 */
import org.aiotrade.lib.util.reactors.Event
import org.aiotrade.lib.util.reactors.Publisher
import org.aiotrade.lib.util.reactors.Reactor
import org.aiotrade.lib.amqp.datatype.ContentType

/*_ rabbitmqctl common usages:
 sudo rabbitmq-server -n rabbit@localhost &
 sudo rabbitmqctl -n rabbit@localhost stop

 sudo rabbitmqctl -n rabbit@localhost stop_app
 sudo rabbitmqctl -n rabbit@localhost reset
 sudo rabbitmqctl -n rabbit@localhost start_app
 sudo rabbitmqctl -n rabbit@localhost list_queues name messages messages_uncommitted messages_unacknowledged

 If encountered troubes when start the server up, since the tables in the mnesia
 database backing rabbitmq are locked (Don't know why this is the case). you can
 get this running again brute force styleee by deleting the database:

 sudo rm -rf /opt/local/var/lib/rabbitmq/mnesia/
 */

/*_
 * Option 1:
 * create one queue per consumer with several bindings, one for each stock.
 * Prices in this case will be sent with a topic routing key.
 *
 * Option 2:
 * Another option is to create one queue per stock. each consumer will be
 * subscribed to several queues. Messages will be sent with a direct routing key.
 *
 * Best Practice:
 * Option 1: should work fine, except there is no need to use a topic exchange.
 * Just use a direct exchange, one queue per user and for each of the stock
 * symbols a user is interested in create a binding between the user's queue
 * and the direct exchange.
 *
 * Option 2: each quote would only go to one consumer, which is probably not
 * what you want. In an AMQP system, to get the same message delivered to N
 * consumers you need (at least) N queues. Exchanges *copy* messages to queues,
 * whereas queues *round-robin* message delivery to consumers.
 */

/**
 * Encapsulates an arbitrary message - simple "bean" holder structure.
 */
case class Delivery(body: Array[Byte], properties: AMQP.BasicProperties, envelope: Envelope) extends Event

/**
 * @param content A deserialized value received via AMQP.
 * @param props
 *
 * Messages received from AMQP are wrapped in this case class. When you
 * register a listener, this is the case class that you will be matching on.
 */
object AMQPMessage {
  def apply(body: Any, props: AMQP.BasicProperties = null, envelope: Envelope = null) = new AMQPMessage(body, props, envelope)
  def unapply(x: AMQPMessage): Option[(Any, AMQP.BasicProperties)] = Some((x.body, x.props))
}
@serializable
class AMQPMessage(val body: Any, val props: AMQP.BasicProperties, val envelope: Envelope) extends Event

object RpcResponse {
  def apply(body: Any, props: AMQP.BasicProperties = null, envelope: Envelope = null) = new RpcResponse(body, props, envelope)
  def unapply(x: RpcResponse): Option[(Any, AMQP.BasicProperties)] = Some((x.body, x.props))
}
@serializable
class RpcResponse(val body: Any, val props: AMQP.BasicProperties, val envelope: Envelope) extends Event

case object RpcTimeout extends RpcResponse("RPC timeout", null, null)

case class RpcRequest(args: Any*) extends Event

case object AMQPConnected extends Event
case object AMQPDisconnected extends Event

object AMQPExchange {
  /**
   * Each AMQP broker declares one instance of each supported exchange type on it's
   * own (for every virtual host). These exchanges are named after the their type
   * with a prefix of amq., e.g. amq.fanout. The empty exchange name is an alias
   * for amq.direct. For this default direct exchange (and only for that) the broker
   * also declares a binding for every queue in the system with the binding key
   * being identical to the queue name.
   *
   * This behaviour implies that any queue on the system can be written into by
   * publishing a message to the default direct exchange with it's routing-key
   * property being equal to the name of the queue.
   */
  val defaultDirect = "" // amp.direct

}

object AMQPDispatcher {
  // Marker object used to signal the queue is in shutdown mode.
  // It is only there to wake up consumers. The canonical representation
  // of shutting down is the presence of _shutdown.
  // Invariant: This is never on _queue unless _shutdown != null.
  private val POISON = Delivery(null, null, null)
}

/**
 * The dispatcher that listens over the AMQP message endpoint.
 * It manages a list of subscribers to the trade message and also sends AMQP
 * messages coming in to the queue/exchange to the list of observers.
 */
import AMQPDispatcher._
abstract class AMQPDispatcher(factory: ConnectionFactory, val exchange: String) extends Publisher with Serializer {
  private val log = Logger.getLogger(getClass.getName)

  case class State(connection: Option[Connection], channel: Option[Channel], consumer: Option[Consumer])
  private var state = State(None, None, None)

  private lazy val timer = new Timer("AMQPReconnectTimer")

  /**
   * Connect only when start, so we can control it to connect at a appropriate time,
   * for instance, all processors are ready. Otherwise, the messages may have been
   * consumered before processors ready.
   */
  def connect: this.type = {
    try {
      doConnect(3000)
    } catch {
      case _ => // don't log ex here, we hope ShutdownListener will give us the cause
    }

    this
  }

  def connection = state.connection
  def channel = state.channel
  def consumer = state.consumer

  @throws(classOf[IOException])
  private def doConnect(reconnectDelay: Long) {
    log.info("Begin to connect ...")

    (try {
        val conn = factory.newConnection
        // @Note: Should listen to connection instead of channel on ShutdownSignalException,
        // @see com.rabbitmq.client.impl.AMQPConnection.MainLoop
        conn.addShutdownListener(new ShutdownListener {
            def shutdownCompleted(cause: ShutdownSignalException) {
              publish(AMQPDisconnected)
              reconnect(reconnectDelay, cause)
            }
          })

        Left(conn)
      } catch {
        case ex => Right(ex)
      }
    ) match {
      case Left(conn) =>
        // we won't catch exceptions thrown during the following procedure, since we need them to fire ShutdownSignalException
        
        val channel = conn.createChannel
        val consumer = configure(channel)

        state = State(Option(conn), Option(channel), consumer)

        consumer match {
          case Some(qConsumer: QueueingConsumer) => QueueingConsumer.startConsumer(qConsumer)
          case _ =>
        }

        log.info("Successfully connected at: " + conn.getHost + ":" + conn.getPort)
        publish(AMQPConnected)

      case Right(ex) =>
        // @Note **only** when there is no created connection, we'll try to reconnect here,
        // let shutdown listener to handle all other reconnetion needs
        publish(AMQPDisconnected)
        reconnect(reconnectDelay, ex)
    }
  }

  private def reconnect(delay: Long, cause: Throwable) {
    log.warning("Will try to reconnect in " + delay + ", the cause is:")
    log.log(Level.WARNING, cause.getMessage, cause)

    disconnect
    
    timer.schedule(new TimerTask {
        def run {
          try {
            val nextDelay = if (delay == 0) 3000 else delay * 2
            doConnect(nextDelay)
          } catch {
            case _ => // don't log ex here, we hope ShutdownListener will give us the cause
          }
        }
      }, delay)
  }

  private def disconnect {
    channel foreach {chan =>
      try {
        consumer foreach {case x: DefaultConsumer => chan.basicCancel(x.getConsumerTag)}
        chan.close
      } catch {
        case _ =>
      }
    }

    connection foreach {conn =>
      if (conn.isOpen) {
        try {
          conn.close
          //log.log(Level.FINEST, "Disconnected AMQP connection at %s:%s [%s]", Array(factory.getHost, factory.getPort, this))
        } catch {
          case _ =>
        }
      }
    }
  }

  /**
   * Registers queue and consumer.
   * @throws IOException if an error is encountered
   * @return the newly created and registered (queue, consumer)
   */
  @throws(classOf[IOException])
  protected def configure(channel: Channel): Option[Consumer]

  @throws(classOf[IOException])
  def publish(exchange: String, routingKey: String, $props: AMQP.BasicProperties, content: Any) {
    channel foreach {chan =>
      import ContentType._

      val props = if ($props == null) new AMQP.BasicProperties else $props

      val contentType = props.getContentType match {
        case null | "" => JAVA_SERIALIZED_OBJECT
        case x => ContentType(x)
      }

      val body = contentType.mimeType match {
        case OCTET_STREAM.mimeType => content.asInstanceOf[Array[Byte]]
        case JAVA_SERIALIZED_OBJECT.mimeType => encodeJava(content)
        case JSON.mimeType => encodeJson(content)
        case _ => encodeJava(content)
      }

      val contentEncoding = props.getContentEncoding match {
        case null | "" => props.setContentEncoding("gzip"); "gzip"
        case x => x
      }
    
      val body1 = contentEncoding match {
        case "gzip" => gzip(body)
        case "lzma" => lzma(body)
        case _ => body
      }

      //println(content + " sent: routingKey=" + routingKey + " size=" + body.length)
      chan.basicPublish(exchange, routingKey, props, body1)
    }
  }

  class AMQPConsumer(channel: Channel) extends DefaultConsumer(channel) {
    private val log = Logger.getLogger(this.getClass.getName)

    // When this is non-null the queue is in shutdown mode and nextDelivery should
    // throw a shutdown signal exception.
    @volatile private var _shutdown: ShutdownSignalException = _

    override def handleShutdownSignal(consumerTag: String, sig: ShutdownSignalException ) {
      _shutdown = sig
    }

    @throws(classOf[IOException])
    override def handleDelivery(tag: String, env: Envelope, props: AMQP.BasicProperties, body: Array[Byte]) {
      import ContentType._

      log.info("Got amqp message: " + (body.length / 1024.0) + "k" )

      val body1 = props.getContentEncoding match {
        case "gzip" => ungzip(body)
        case "lzma" => unlzma(body)
        case _ => body
      }

      val contentType = props.getContentType match {
        case null | "" => JAVA_SERIALIZED_OBJECT
        case x => ContentType(x)
      }

      try {
        val content = contentType.mimeType match {
          case OCTET_STREAM.mimeType => body1
          case JSON.mimeType => decodeJson(body1)
          case JAVA_SERIALIZED_OBJECT.mimeType => decodeJava(body1)
          case _ => decodeJava(body1)
        }

        // send back to interested observers for further relay
        publish(AMQPMessage(content, props))
        //log.info("Fired amqp message: " + content)
       
        // Parameters:
        //   deliveryTag - the tag from the received AMQP.Basic.GetOk or AMQP.Basic.Deliver
        //   multiple - true to acknowledge all messages up to and including the supplied delivery tag;
        //              false to acknowledge just the supplied delivery tag.
        //
        // if autoAck is set false, messages will be blocked until an ack to broker,
        // so it's better always ack it. (Although prefetch may deliver more than
        // one message to consumer)
        channel.basicAck(env.getDeliveryTag, false)
        //log.info(processors.map(_.getState.toString).mkString("(", ",", ")"))
      } catch {
        // should catch it when old version classes were sent by old version of clients.
        case ex: InvalidClassException => log.log(Level.WARNING, ex.getMessage, ex)
        case ex => log.log(Level.WARNING, ex.getMessage, ex)
      }
    }
  }

  object QueueingConsumer {
    def startConsumer(consumer: QueueingConsumer) {
      val consumerRunner = new Runnable {
        def run {
          while (true) {
            val delivery = consumer.nextDelivery // blocked here
            consumer.relay(delivery)
          }
        }
      }
      (new Thread(consumerRunner)).start
    }
  }
  class QueueingConsumer(channle: Channel) extends DefaultConsumer(channle) {

    private val _queue: BlockingQueue[Delivery] = new LinkedBlockingQueue[Delivery]

    // When this is non-null the queue is in shutdown mode and nextDelivery should
    // throw a shutdown signal exception.
    @volatile private var _shutdown: ShutdownSignalException = _

    override def handleShutdownSignal(consumerTag: String, sig: ShutdownSignalException ) {
      _shutdown = sig
      _queue.add(POISON)
    }

    @throws(classOf[IOException])
    override def handleDelivery(consumerTag: String,
                                envelope: Envelope ,
                                props: AMQP.BasicProperties,
                                body: Array[Byte]
    ) {
      checkShutdown
      log.info("Got amqp message: " + (body.length / 1024.0) + "k" )

      this._queue.add(Delivery(body, props, envelope))
    }

    /**
     * Check if we are in shutdown mode and if so throw an exception.
     */
    private def checkShutdown {
      if (_shutdown != null) throw Utility.fixStackTrace(_shutdown)
    }

    /**
     * If this is a non-POISON non-null delivery simply return it.
     * If this is POISON we are in shutdown mode, throw _shutdown
     * If this is null, we may be in shutdown mode. Check and see.
     */
    private def handle(delivery: Delivery): Delivery = {
      if (delivery == POISON || delivery == null && _shutdown != null) {
        if (delivery == POISON) {
          _queue.add(POISON)
          if (_shutdown == null) {
            throw new IllegalStateException(
              "POISON in queue, but null _shutdown. " +
              "This should never happen, please report as a BUG")
          }
        }
        throw Utility.fixStackTrace(_shutdown)
      }
      delivery
    }

    /**
     * Main application-side API: wait for the next message delivery and return it.
     * @return the next message
     * @throws InterruptedException if an interrupt is received while waiting
     * @throws ShutdownSignalException if the connection is shut down while waiting
     */
    @throws(classOf[InterruptedException])
    @throws(classOf[ShutdownSignalException])
    def nextDelivery: Delivery = {
      handle(_queue.take)
    }

    /**
     * Main application-side API: wait for the next message delivery and return it.
     * @param timeout timeout in millisecond
     * @return the next message or null if timed out
     * @throws InterruptedException if an interrupt is received while waiting
     * @throws ShutdownSignalException if the connection is shut down while waiting
     */
    @throws(classOf[InterruptedException])
    @throws(classOf[ShutdownSignalException])
    def nextDelivery(timeout: Long): Delivery = {
      handle(_queue.poll(timeout, TimeUnit.MILLISECONDS))
    }

    def relay(delivery: Delivery) {
      delivery match {
        case Delivery(body, props, env) =>
          val body1 = props.getContentEncoding match {
            case "gzip" => ungzip(body)
            case "lzma" => unlzma(body)
            case _ => body
          }

          import ContentType._
          val contentType = props.getContentType match {
            case null | "" => JAVA_SERIALIZED_OBJECT
            case x => ContentType(x)
          }

          try {
            val content = contentType.mimeType match {
              case OCTET_STREAM.mimeType => body1
              case JAVA_SERIALIZED_OBJECT.mimeType => decodeJava(body1)
              case JSON.mimeType => decodeJson(body1)
              case _ => decodeJava(body1)
            }

            publish(AMQPMessage(content, props))
          } catch {
            // should catch it when old version classes were sent by old version of clients.
            case e: InvalidClassException =>
            case _ =>
          }
        case _ =>
      }
    }
  }

  /**
   * Hold strong refs of processors to avoid them to be GCed
   */
  var processors: List[Processor] = Nil
  
  /**
   * Processor that will automatically added as listener of this AMQPDispatcher
   * and process AMQPMessage via process(msg)
   */
  abstract class Processor extends Reactor {
    processors ::= this
    
    reactions += {
      case msg: AMQPMessage => process(msg)
    }
    listenTo(AMQPDispatcher.this)

    protected def process(msg: AMQPMessage)
  }

}
