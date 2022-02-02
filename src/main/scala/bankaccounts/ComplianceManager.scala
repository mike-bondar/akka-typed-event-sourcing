package xyz.mibon.remittance
package bankaccounts

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._

object ComplianceManager {

  sealed trait Command

  object Command {
    case class StartCompliance(bankAccount: BankAccount, onDone: ActorRef[ComplianceResult]) extends Command
  }

  sealed trait ComplianceResult

  object ComplianceResult {
    case object Approved extends ComplianceResult
    case object Rejected extends ComplianceResult
  }

}

class ComplianceManager {
  import ComplianceManager._

  def build(): Behavior[Command] = {
    Behaviors.receive { case (ctx, Command.StartCompliance(bankAccount, replyTo)) =>
      ctx.log.info(s"Approving ${bankAccount.id}")
      // Test delay
      ctx.scheduleOnce(100.millis, replyTo, ComplianceResult.Approved)
      Behaviors.same
    }
  }

}
