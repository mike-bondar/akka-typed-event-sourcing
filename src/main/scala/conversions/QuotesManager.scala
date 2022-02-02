package xyz.mibon.remittance
package conversions

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import conversions.QuotesManager.{Command, Response}
import tools.Clock

import java.time.Instant
import java.util.Currency

object QuotesManager {

  sealed trait Command

  object Command {
    case class GetQuote(
        sell: Currency,
        buy: Currency,
        amount: BigDecimal,
        quotedAt: Instant,
        replyTo: ActorRef[Response]
    ) extends Command
  }

  sealed trait Response

  object Response {
    case class Quote(
        sell: Currency,
        buy: Currency,
        conversionRate: BigDecimal,
        quotedAt: Instant,
        validUntil: Instant
    ) extends Response
  }
}

class QuotesManager(clock: Clock) {

  def build(): Behavior[QuotesManager.Command] = {
    Behaviors.receiveMessage { case cmd: Command.GetQuote =>
      cmd.replyTo.tell(
        Response.Quote(cmd.sell, cmd.buy, 1.2, clock.now(), clock.now().plusSeconds(60 * 30))
      )
      Behaviors.same
    }
  }

}
