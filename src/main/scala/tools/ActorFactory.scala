package xyz.mibon.remittance
package tools

import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.{ActorRef, Behavior}

trait ActorFactory {
  def spawn[A](behavior: Behavior[A], name: String): ActorRef[A]
}

object ActorFactory {
  def fromContext(ctx: ActorContext[_]): ActorFactory = {
    new ActorFactory {
      override def spawn[A](behavior: Behavior[A], name: String): ActorRef[A] = ctx.spawn(behavior, name)
    }
  }
}
