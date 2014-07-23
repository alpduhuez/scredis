package scredis.io

import com.typesafe.scalalogging.slf4j.LazyLogging
import com.codahale.metrics.MetricRegistry

import akka.actor._
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import akka.event.LoggingReceive

import scredis.protocol.{ Protocol, Request }
import scredis.protocol.requests.ConnectionRequests.{ Auth, Select, Quit }
import scredis.protocol.requests.ServerRequests.Shutdown
import scredis.protocol.requests.PubSubRequests.{ Subscribe => SubscribeRequest, PSubscribe }
import scredis.exceptions.RedisIOException

import scala.util.{ Success, Failure }
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._

import java.util.LinkedList
import java.net.InetSocketAddress

class IOActor(
  listenerActor: ActorRef,
  remote: InetSocketAddress
) extends Actor with LazyLogging {
  
  import Tcp._
  import IOActor._
  import context.system
  import context.dispatcher
  
  private val scheduler = context.system.scheduler
  
  private val bufferPool = new scredis.util.BufferPool(1)
  
  private var batch: Seq[Request[_]] = Nil
  private var writeId = 0
  private var retries = 0
  private var canWrite = false
  private var isConnecting: Boolean = false
  private var isAuthenticating = false
  private var isClosing: Boolean = false
  private var timeoutCancellableOpt: Option[Cancellable] = None
  
  protected val requests = new LinkedList[Request[_]]()
  protected var connection: ActorRef = _
  
  protected def incrementWriteId(): Unit = {
    if (writeId == Integer.MAX_VALUE) {
      writeId = 1
    } else {
      writeId += 1
    }
  }
  
  protected def connect(): Unit = if (!isConnecting) {
    logger.info(s"Connecting to $remote")
    IO(Tcp) ! Connect(
      remoteAddress = remote,
      options = List[akka.io.Inet.SocketOption](
        SO.KeepAlive(true),
        SO.TcpNoDelay(true),
        SO.ReuseAddress(true),
        SO.SendBufferSize(5000000),
        SO.ReceiveBufferSize(500000)
      ),
      timeout = Some(2 seconds)
    )
    isConnecting = true
    timeoutCancellableOpt = Some {
      scheduler.scheduleOnce(2 seconds, self, ConnectTimeout)
    }
  }
  
  protected def failAllQueuedRequests(throwable: Throwable): Unit = {
    requeueBatch()
    val count = requests.size
    while (!requests.isEmpty) {
      requests.pop().failure(throwable)
    }
    listenerActor ! ListenerActor.Remove(count)
  }
  
  protected def failBatch(throwable: Throwable): Unit = {
    val count = batch.size
    batch.foreach(_.failure(throwable))
    batch = Nil
    listenerActor ! ListenerActor.Remove(count)
  }
  
  protected def requeueBatch(): Unit = {
    batch.foreach(requests.push)
    batch = Nil
  }
  
  protected def abort(): Unit = {
    listenerActor ! ListenerActor.Abort
    become(awaitingAbort)
  }
  
  protected def stop(): Unit = {
    logger.trace("Stopping Actor...")
    //listenerActor ! PoisonPill
    context.stop(self)
  }
  
  protected def encode(request: Request[_]): Int = {
    request.encode()
    request.encoded match {
      case Left(bytes) => bytes.length
      case Right(buffer) => buffer.remaining
    }
  }
  
  protected def write(requests: Seq[Request[_]], lengthOpt: Option[Int] = None): Unit = {
    val length = lengthOpt.getOrElse {
      requests.foldLeft(0)((length, request) => length + encode(request))
    }
    val buffer = bufferPool.acquire(length)
    requests.foreach { request =>
      request.encoded match {
        case Left(bytes) => buffer.put(bytes)
        case Right(buff) => {
          buffer.put(buff)
          Protocol.releaseBuffer(buff)
        }
      }
    }
    buffer.flip()
    val data = ByteString(buffer)
    logger.trace(s"Writing data: ${data.decodeString("UTF-8")}")
    connection ! Write(data, WriteAck)
    bufferPool.release(buffer)
    canWrite = false
    incrementWriteId()
    this.batch = requests
    /*
    timeoutCancellableOpt = Some {
      scheduler.scheduleOnce(2 seconds, self, WriteTimeout(writeId))
    }*/
  }
  
  protected def write(): Unit = {
    if (!this.batch.isEmpty) {
      requeueBatch()
    }
    if (requests.isEmpty) {
      canWrite = true
      return
    }
    
    var length = 0
    val batch = ListBuffer[Request[_]]()
    while (!requests.isEmpty && length < 50000) {
      val request = requests.pop()
      length += encode(request)
      batch += request
    }
    write(batch.toList, Some(length))
  }
  
  protected def always: Receive = {
    case Terminated(_) => {
      failAllQueuedRequests(RedisIOException("Connection has been shutdown"))
      listenerActor ! ListenerActor.Shutdown
      become(awaitingShutdown)
    }
  }
  
  protected def fail: Receive = {
    case x => logger.error(s"Received unhandled message: $x")
  }
  
  protected def become(state: Receive): Unit = context.become(always orElse state orElse fail)
  
  override def preStart(): Unit = {
    connect()
    become(connecting)
  }
  
  def receive: Receive = fail
  
  def connecting: Receive = {
    case Connected(remote, local) => {
      logger.info(s"Connected to $remote")
      connection = sender
      connection ! Register(listenerActor)
      listenerActor ! ListenerActor.Connected
      context.watch(connection)
      timeoutCancellableOpt.foreach(_.cancel())
      isConnecting = false
      canWrite = true
      retries = 0
      requeueBatch()
      write()
      become(connected)
    }
    case CommandFailed(_: Connect) => {
      logger.error(s"Could not connect to $remote: Command failed")
      failAllQueuedRequests(RedisIOException(s"Could not connect to $remote: Command failed"))
      timeoutCancellableOpt.foreach(_.cancel())
      context.stop(self)
    }
    case ConnectTimeout => {
      logger.error(s"Could not connect to $remote: Connect timeout")
      failAllQueuedRequests(RedisIOException(s"Could not connect to $remote: Connect timeout"))
      context.stop(self)
    }
  }
  
  def connected: Receive = {
    case request: Request[_] => {
      requests.addLast(request)
      if (canWrite) {
        write()
      }
    }
    case WriteAck => {
      batch = Nil
      retries = 0
      timeoutCancellableOpt.foreach(_.cancel())
      write()
    }
    case WriteTimeout(writeId) => if (writeId == this.writeId) {
      failAllQueuedRequests(RedisIOException(s"Write timeout"))
      abort()
    }
    case CommandFailed(cmd: Write) => {
      logger.error(s"Command failed: $cmd")
      timeoutCancellableOpt.foreach(_.cancel())
      if (retries >= 2) {
        failAllQueuedRequests(RedisIOException(s"Write failed"))
        abort()
      } else {
        retries += 1
        if (isAuthenticating) {
          canWrite = true
        } else {
          write()
        }
      }
    }
  }
  
  def awaitingShutdown: Receive = {
    case request: Request[_] => {
      request.failure(RedisIOException("Connection is beeing shutdown"))
      listenerActor ! ListenerActor.Remove(1)
    }
    case WriteAck =>
    case CommandFailed(cmd: Write) =>
    case ShutdownAck => context.stop(self)
  }
  
  def awaitingAbort: Receive = {
    case request: Request[_] => {
      request.failure(RedisIOException("Connection is beeing reset"))
      listenerActor ! ListenerActor.Remove(1)
    }
    case WriteAck =>
    case CommandFailed(cmd: Write) =>
    case AbortAck => {
      connection ! Abort
      timeoutCancellableOpt = Some {
        scheduler.scheduleOnce(3 seconds, self, AbortTimeout)
      }
      become(aborting)
    }
  }
  
  def aborting: Receive = {
    case WriteAck =>
    case CommandFailed(cmd: Write) =>
    case Terminated(connection) => {
      logger.info(s"Connection has been reset")
      timeoutCancellableOpt.foreach(_.cancel())
      context.stop(self)
    }
    case AbortTimeout => {
      logger.error(s"A timeout occurred while resetting the connection")
      context.stop(connection)
    }
  }
  
}

object IOActor {
  object WriteAck extends Tcp.Event
  case object ConnectTimeout
  case class WriteTimeout(writeId: Int)
  case object AbortTimeout
  case object AbortAck
  case object ShutdownAck
}
