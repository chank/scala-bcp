/*
 * scala-bcp
 * Copyright 2014 深圳岂凡网络有限公司 (Shenzhen QiFun Network Corp., LTD)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qifun.bcp

import com.dongxiguo.fastring.Fastring.Implicits._
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import scala.annotation.tailrec
import scala.collection.mutable.WrappedArray
import scala.concurrent.duration.DurationInt
import scala.concurrent.stm.InTxn
import scala.concurrent.stm.Ref
import scala.concurrent.stm.TMap
import scala.concurrent.stm.TSet
import scala.concurrent.stm.Txn
import scala.concurrent.stm.atomic
import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.util.control.NoStackTrace
import scala.util.control.Exception.Catcher
import com.qifun.statelessFuture.util.io.SocketInputStream
import com.qifun.statelessFuture.util.io.SocketWritingQueue
import com.qifun.statelessFuture.Future
import scala.collection.immutable.Queue
import com.qifun.bcp.Bcp._
import BcpServer._
import com.qifun.bcp.BcpSession._
import com.dongxiguo.fastring.Fastring.Implicits._
import java.io.EOFException
import com.sun.jndi.ldap.pool.Connections

object BcpServer {

  private implicit val (logger, formatter, appender) = ZeroLoggerFactory.newLogger(this)

  private[BcpServer] final class Stream(socket: AsynchronousSocketChannel) extends BcpSession.Stream(socket) {
    // 服务端专有的数据结构
  }

  private[BcpServer] final class Connection extends BcpSession.Connection[Stream] {
    // 服务端专有的数据结构
  }

}

/**
 * 处理BCP协议的服务器。
 */
abstract class BcpServer {
  import BcpServer.logger
  import BcpServer.appender
  import BcpServer.formatter

  abstract class Session protected (sessionId: Array[Byte]) extends BcpSession[BcpServer.Stream, BcpServer.Connection] {

    override private[bcp] final def newConnection = new BcpServer.Connection

    override private[bcp] final def internalExecutor = executor

    override private[bcp] final def release()(implicit txn: InTxn) {
      val removedSessionOption = sessions.remove(sessionId)
      assert(removedSessionOption == Some(Session.this))
    }

    override private[bcp] final def busy(connection: BcpServer.Connection)(implicit txn: InTxn): Unit = {
    }

    override private[bcp] final def idle(connection: BcpServer.Connection)(implicit txn: InTxn): Unit = {
    }

    override private[bcp] def close(connection: BcpServer.Connection)(implicit txn: InTxn): Unit = {
    }

    /**
     * 每一次触发表示与对端建立了一次新的会话。
     */
    protected def accepted()

    private[BcpServer] final def internalAccepted(): Unit = {
      accepted()
    }

  }

  protected def executor: ScheduledExecutorService

  /**
   * @note [[Array.equals]]不支持比较内容，所以[[TMap]]的键必须使用[[BoxedSessionId]]。
   */
  val sessions = TMap.empty[BoxedSessionId, Session]

  protected def newSession(sessionId: Array[Byte]): Session

  protected final def addIncomingSocket(socket: AsynchronousSocketChannel) {
    logger.fine(fast"bcp server add incoming socket: ${socket}")
    val stream = new BcpServer.Stream(socket)
    val acceptFuture = Future {
      val head = BcpIo.receiveHead(stream).await
      val ConnectionHead(sessionId, isRenew, connectionId) = head
      logger.fine(
        fast"server received sessionId: ${sessionId.toSeq} , isRenew: ${isRenew}, connectionId: ${connectionId}")
      atomic { implicit txn =>
        val session = sessions.get(sessionId) match {
          case None => {
            val session = newSession(sessionId)
            sessions(sessionId) = session
            Txn.afterCommit(_ => session.internalAccepted())
            session
          }
          case Some(session) => {
            session
          }
        }
        if (isRenew) {
          session.renewSession()
        }
        session.addStream(connectionId, stream)
      }
    }
    implicit def catcher: Catcher[Unit] = {
      case e: Exception => {
        logger.info(e)
        socket.close()
      }
    }
    for (_ <- acceptFuture) {
      logger.fine("An connection is accepted.")
    }
  }

}
