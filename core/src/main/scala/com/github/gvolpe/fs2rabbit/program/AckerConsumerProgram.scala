/*
 * Copyright 2017 Fs2 Rabbit
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

package com.github.gvolpe.fs2rabbit.program

import cats.effect.{Async, IO}
import com.github.gvolpe.fs2rabbit.algebra.{AMQPClient, AMQPInternals, AckerConsumer}
import com.github.gvolpe.fs2rabbit.arguments.Arguments
import com.github.gvolpe.fs2rabbit.config.Fs2RabbitConfig
import com.github.gvolpe.fs2rabbit.model._
import com.github.gvolpe.fs2rabbit.typeclasses.StreamEval
import com.rabbitmq.client.Channel
import fs2.{Pipe, Sink, Stream}

import scala.concurrent.ExecutionContext

class AckerConsumerProgram[F[_]](config: Fs2RabbitConfig, AMQP: AMQPClient[Stream[F, ?]])(implicit F: Async[F],
                                                                                          SE: StreamEval[F],
                                                                                          ec: ExecutionContext)
    extends AckerConsumer[Stream[F, ?]] {

  private[fs2rabbit] def resilientConsumer: Pipe[F, Either[Throwable, AmqpEnvelope], AmqpEnvelope] =
    _.flatMap {
      case Left(err)  => Stream.raiseError(err)
      case Right(env) => SE.pure[AmqpEnvelope](env)
    }

  override def createAcker(channel: Channel): Sink[F, AckResult] =
    _.flatMap {
      case Ack(tag)  => AMQP.basicAck(channel, tag, multiple = false)
      case NAck(tag) => AMQP.basicNack(channel, tag, multiple = false, config.requeueOnNack)
    }

  override def createConsumer(queueName: QueueName,
                              channel: Channel,
                              basicQos: BasicQos,
                              autoAck: Boolean = false,
                              noLocal: Boolean = false,
                              exclusive: Boolean = false,
                              consumerTag: String = "",
                              args: Arguments = Map.empty): StreamConsumer[F] =
    for {
      internalQ <- Stream.eval(F.liftIO(fs2.async.boundedQueue[IO, Either[Throwable, AmqpEnvelope]](500)))
      internals = AMQPInternals(Some(internalQ))
      _         <- AMQP.basicQos(channel, basicQos)
      _         <- AMQP.basicConsume(channel, queueName, autoAck, consumerTag, noLocal, exclusive, args)(internals)
      consumer  <- Stream.repeatEval(internalQ.dequeue1.to[F]) through resilientConsumer
    } yield consumer

}
