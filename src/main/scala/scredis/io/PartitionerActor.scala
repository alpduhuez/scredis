package scredis.io

import com.typesafe.scalalogging.slf4j.LazyLogging

import com.codahale.metrics.MetricRegistry

import akka.actor.{ Actor, ActorRef, Props }
import akka.routing._
import akka.util.ByteString

import scredis.{ Transaction, Subscription }
import scredis.protocol.{ Protocol, Request }
import scredis.protocol.requests.TransactionRequests.{ Multi, Exec }

import scala.util.{ Try, Success, Failure }
import scala.collection.mutable.{ Queue => MQueue, ListBuffer }
import scala.concurrent.{ ExecutionContext, Promise, Future }

import java.util.LinkedList
import java.nio.ByteBuffer

class PartitionerActor(ioActor: ActorRef) extends Actor with LazyLogging {
  
  import PartitionerActor._
  import DecoderActor._
  
  private var subscriptionOpt: Option[Subscription] = None
  
  private val requests = new LinkedList[Request[_]]()
  
  private val decoders = context.actorOf(
    SmallestMailboxPool(3).props(Props[DecoderActor]).withDispatcher("scredis.decoder-dispatcher")
  )
  
  private val tellTimer = scredis.protocol.Protocol.metrics.timer(
    MetricRegistry.name(getClass, "tellTimer")
  )
  
  private var remainingByteStringOpt: Option[ByteString] = None
  private var skip = 0
  
  def receive: Receive = {
    case request: Request[_] => {
      requests.addLast(request)
      ioActor ! request
    }
    case t @ Transaction(requests) => {
      this.requests.addLast(t.multiRequest)
      ioActor ! t.multiRequest
      requests.foreach { request =>
        this.requests.addLast(request)
        ioActor ! request
      }
      this.requests.addLast(t.execRequest)
      ioActor ! t.execRequest
    }
    case Push(request) => requests.push(request)
    case Remove(count) => for (i <- 1 to count) {
      requests.pop()
    }
    case Skip(count) => skip += count
    case PartitionerActor.Subscribe(subscription) => {
      subscriptionOpt = Some(subscription)
      decoders ! Broadcast(DecoderActor.Subscribe(subscription))
    }
    case data: ByteString => {
      logger.debug(s"Received data: ${data.decodeString("UTF-8").replace("\r\n", "\\r\\n")}")
      val completedData = remainingByteStringOpt match {
        case Some(remains) => {
          remainingByteStringOpt = None
          remains ++ data
        }
        case None => data
      }
      
      val buffer = completedData.asByteBuffer
      val repliesCount = Protocol.count(buffer)
      
      if (repliesCount > 0) {
        val position = buffer.position
        val skipCount = math.min(skip, repliesCount)
        skip -= skipCount
        
        val trimmedData = if (buffer.remaining > 0) {
          remainingByteStringOpt = Some(ByteString(buffer))
          completedData.take(position)
        } else {
          completedData
        }
        
        subscriptionOpt match {
          case Some(_) => decoders ! SubscribePartition(trimmedData)
          case None => {
            val requests = ListBuffer[Request[_]]()
            for (i <- 1 to repliesCount) {
              requests += this.requests.pop()
            }
            decoders ! Partition(trimmedData, requests.toList.iterator, skipCount)
          }
        }
      }
    }
  }
  
}

object PartitionerActor {
  case class Remove(count: Int)
  case class Skip(count: Int)
  case class Push(request: Request[_])
  case class Subscribe(subscription: Subscription)
}
