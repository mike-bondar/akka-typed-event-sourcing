package xyz.mibon.remittance
package conversions

import eventsourcing.{EventSourcedBehaviour, PersistenceId}

import akka.actor.typed.{ActorRef, Behavior}
import eventsourcing.EventSourcedBehaviour.Effect

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import conversions.ConversionsManager.Timeouts
import tools.Clock
import crm.Customer
import bankaccounts.{BankAccount, BankAccountManager, BankAccountSupervisor}

import java.time.Instant
import java.util.Currency
import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

object ConversionsManager {
  sealed trait Command

  sealed trait CommandWithReply extends Command { def replyTo: ActorRef[Response.UnknownCommand.type] }

  object Command {
    case class Initialize(customerId: Customer.Id, replyTo: ActorRef[Response.Initialized]) extends Command

    case class Quote(
        sell: Currency,
        buy: Currency,
        amount: BigDecimal,
        replyTo: ActorRef[QuotationResponse]
    ) extends CommandWithReply

    private[ConversionsManager] case class Quoted(
        sell: Currency,
        buy: Currency,
        conversionRate: BigDecimal,
        validUntil: Instant,
        sellAmount: BigDecimal,
        replyTo: ActorRef[QuotationResponse]
    ) extends CommandWithReply

    case class SetBeneficiary(id: BankAccount.Id, replyTo: ActorRef[SetBeneficiaryResponse]) extends CommandWithReply

    case class SetPayer(id: BankAccount.Id, replyTo: ActorRef[SetPayerResponse]) extends CommandWithReply

    case class Confirm(replyTo: ActorRef[ConfirmationResponse]) extends CommandWithReply

    private[ConversionsManager] case class QuotationTimeout(replyTo: ActorRef[QuotationResponse])
        extends CommandWithReply

    private[ConversionsManager] case class BeneficiaryLookup(
        result: Option[BankAccount],
        replyTo: ActorRef[SetBeneficiaryResponse]
    ) extends CommandWithReply

    private[ConversionsManager] case class PayerLookup(result: Option[BankAccount], replyTo: ActorRef[SetPayerResponse])
        extends CommandWithReply
  }

  sealed trait QuotationResponse

  sealed trait SetBeneficiaryResponse

  sealed trait SetPayerResponse

  sealed trait ConfirmationResponse

  object Response {
    case class Initialized(id: Conversion.Id)

    case class Quotation(conversionRate: BigDecimal, buyAmount: BigDecimal) extends QuotationResponse

    case object UnknownCommand
        extends QuotationResponse
        with SetBeneficiaryResponse
        with SetPayerResponse
        with ConfirmationResponse

    case object QuotationFailed extends QuotationResponse

    case object UnknownBankAccount extends SetBeneficiaryResponse with SetPayerResponse

    case object InactiveBankAccount extends SetBeneficiaryResponse with SetPayerResponse

    case object WrongCurrency extends SetBeneficiaryResponse with SetPayerResponse

    case object BankAccountSet extends SetBeneficiaryResponse with SetPayerResponse

    case class Confirmed(at: Instant) extends ConfirmationResponse

    case object DataIncomplete extends ConfirmationResponse

    case object QuotationExpired extends ConfirmationResponse
  }

  sealed trait State

  object State {
    case object Empty extends State

    case class Initialized(customerId: Customer.Id) extends State

    case class Quoted(
        customerId: Customer.Id,
        sell: Currency,
        buy: Currency,
        sellAmount: BigDecimal,
        conversionRate: BigDecimal,
        quotationValidUntil: Instant,
        beneficiary: Option[BankAccount],
        payer: Option[BankAccount]
    ) extends State {
      def buyAmount: BigDecimal = sellAmount * conversionRate
    }

    case class Running(conversion: Conversion) extends State {
      val id: Conversion.Id        = conversion.id
      val payer: BankAccount       = conversion.payer
      val beneficiary: BankAccount = conversion.beneficiary
      val customerId: Customer.Id  = conversion.customerId
    }
  }

  case class Timeouts(quotationTimeout: FiniteDuration)

  object Timeouts {
    val default: Timeouts = Timeouts(quotationTimeout = 1.second)
  }
}

class ConversionsManager(
    clock: Clock,
    eventSourcedBehaviour: EventSourcedBehaviour,
    quotesManager: ActorRef[QuotesManager.Command],
    index: ActorRef[Index.Command],
    bankAccountsSupervisor: ActorRef[bankaccounts.BankAccountSupervisor.Command],
    timeouts: Timeouts = Timeouts.default
) {

  import ConversionsManager._

  def build(id: Conversion.Id, parent: ActorRef[ConversionsSupervisor.InternalCommand]): Behavior[Command] =
    Behaviors.setup { ctx =>
      eventSourcedBehaviour.build[ManagerEvent, Command, State](
        PersistenceId(s"conversion-${id.id.toString}"),
        State.Empty,
        commandHandler(id, ctx, parent),
        eventHandler(id),
        recoveryCompleted
      )
    }

  private def recoveryCompleted(state: State): Unit =
    state match {
      case running: State.Running => addToIndex(index, running)
      case _                      =>
    }

  @nowarn private def commandHandler(
      id: Conversion.Id,
      ctx: ActorContext[Command],
      parent: ActorRef[ConversionsSupervisor.InternalCommand]
  ): (Command, State) => Effect[ManagerEvent, State] = { (command, state) =>
    val handler = state match {
      case State.Empty          => whenEmpty(id, ctx, parent)
      case _: State.Initialized => whenInitialized(ctx)
      case quoted: State.Quoted => whenQuoted(quoted, ctx)
    }

    handler(command)
  }

  private def whenEmpty(
      id: Conversion.Id,
      ctx: ActorContext[Command],
      parent: ActorRef[ConversionsSupervisor.InternalCommand]
  ): Command => Effect[ManagerEvent, State] = {
    case Command.Initialize(customerId, replyTo) =>
      val event = ManagerEvent.ConversionInitialized(customerId)
      Effect.persist(event).thenRun { _ =>
        parent.tell(ConversionsSupervisor.Command.ConversionCreated(id, ctx.self))
        replyTo.tell(Response.Initialized(id))
      }
    case other: CommandWithReply =>
      other.replyTo.tell(Response.UnknownCommand)
      Effect.none
  }

  private def whenInitialized(ctx: ActorContext[Command]): Command => Effect[ManagerEvent, State] = {
    case cmd: Command.Quote =>
      makeQuote(ctx, cmd)
    case cmd: Command.Quoted =>
      val event =
        ManagerEvent.Quoted(cmd.sell, cmd.buy, cmd.sellAmount, cmd.conversionRate, cmd.validUntil)
      Effect.persist(event).thenRun { case state: State.Quoted =>
        cmd.replyTo.tell(Response.Quotation(cmd.conversionRate, state.buyAmount))
      }
    case cmd: Command.QuotationTimeout =>
      Effect.none.thenRun { _ =>
        cmd.replyTo ! Response.QuotationFailed
      }
    case other: CommandWithReply =>
      other.replyTo.tell(Response.UnknownCommand)
      Effect.none
  }

  private def whenQuoted(
      state: State.Quoted,
      ctx: ActorContext[ConversionsManager.Command]
  ): Command => Effect[ManagerEvent, State] = {
    case Command.Confirm(replyTo) =>
      val now = clock.now()

      if (now.isAfter(state.quotationValidUntil)) {
        replyTo.tell(Response.QuotationExpired)
        Effect.none
      } else {
        val maybeEvent = for {
          beneficiary <- state.beneficiary
          payer       <- state.payer
        } yield ManagerEvent.Confirmed(beneficiary, payer, now)

        maybeEvent match {
          case Some(event) =>
            Effect.persist(event).thenRun { state =>
              state match {
                case running: State.Running => addToIndex(index, running)
                case other => throw new IllegalArgumentException(s"unexpected state $other after $event applied")
              }

              replyTo.tell(Response.Confirmed(now))
            }
          case None =>
            replyTo.tell(Response.DataIncomplete)
            Effect.none
        }
      }

    case Command.SetBeneficiary(id, replyTo) =>
      bankAccountLookup(id, ctx, Command.BeneficiaryLookup(_, replyTo))
      Effect.none

    case Command.SetPayer(id, replyTo) =>
      bankAccountLookup(id, ctx, Command.PayerLookup(_, replyTo))
      Effect.none

    case Command.PayerLookup(result, replyTo) =>
      result match {
        case Some(bankAccount) if bankAccount.currency != state.sell =>
          replyTo.tell(Response.WrongCurrency)
          Effect.none

        case Some(bankAccount) if bankAccount.owner != state.customerId =>
          replyTo.tell(Response.UnknownBankAccount)
          Effect.none

        case Some(bankAccount) if !bankAccount.active =>
          replyTo.tell(Response.InactiveBankAccount)
          Effect.none

        case Some(bankAccount) =>
          Effect.persist(ManagerEvent.PayerSet(bankAccount)).thenRun { _ =>
            replyTo.tell(Response.BankAccountSet)
          }

        case None =>
          replyTo.tell(Response.UnknownBankAccount)
          Effect.none
      }
    case Command.BeneficiaryLookup(result, replyTo) =>
      result match {
        case Some(bankAccount) if bankAccount.currency != state.buy =>
          replyTo.tell(Response.WrongCurrency)
          Effect.none

        case Some(bankAccount) if bankAccount.owner != state.customerId =>
          replyTo.tell(Response.UnknownBankAccount)
          Effect.none

        case Some(bankAccount) if !bankAccount.active =>
          replyTo.tell(Response.InactiveBankAccount)
          Effect.none

        case Some(bankAccount) =>
          Effect.persist(ManagerEvent.BeneficiarySet(bankAccount)).thenRun { _ =>
            replyTo.tell(Response.BankAccountSet)
          }

        case None =>
          replyTo.tell(Response.UnknownBankAccount)
          Effect.none
      }
    case other: CommandWithReply =>
      other.replyTo.tell(Response.UnknownCommand)
      Effect.none
  }

  private def bankAccountLookup(
      id: BankAccount.Id,
      ctx: ActorContext[Command],
      mapper: Option[BankAccount] => Command
  ): Unit = {
    val ref = ctx.messageAdapter[Option[BankAccount]](mapper)

    val notFound = ctx.messageAdapter[BankAccountSupervisor.Response.BankAccountNotFound.type] { _ =>
      mapper(None)
    }

    val request = BankAccountSupervisor.Command.Forward(id, BankAccountManager.Command.Get(ref), notFound)

    bankAccountsSupervisor.tell(request)
  }

  private def makeQuote(
      ctx: ActorContext[Command],
      command: Command.Quote
  ): Effect[ManagerEvent, State] = {
    val adapter = ctx.messageAdapter[QuotesManager.Response] { case quote: QuotesManager.Response.Quote =>
      Command.Quoted(
        quote.sell,
        quote.buy,
        quote.conversionRate,
        quote.validUntil,
        command.amount,
        command.replyTo
      )
    }

    ctx.scheduleOnce(timeouts.quotationTimeout, ctx.self, Command.QuotationTimeout(command.replyTo))

    val query = QuotesManager.Command.GetQuote(
      command.sell,
      command.buy,
      command.amount,
      clock.now(),
      adapter
    )

    quotesManager.tell(query)

    Effect.none
  }

  @nowarn private def eventHandler(id: Conversion.Id): (ManagerEvent, State) => State = { (event, state) =>
    state match {
      case State.Empty =>
        event match {
          case event: ManagerEvent.ConversionInitialized => State.Initialized(event.customerId)
        }
      case state: State.Quoted =>
        event match {
          case event: ManagerEvent.BeneficiarySet =>
            state.copy(beneficiary = Some(event.beneficiary))
          case event: ManagerEvent.PayerSet =>
            state.copy(payer = Some(event.payer))
          case event: ManagerEvent.Confirmed =>
            val conversion = Conversion(
              id,
              state.customerId,
              state.sell,
              state.buy,
              state.sellAmount,
              state.conversionRate,
              event.beneficiary,
              event.payer,
              Conversion.Status.Pending,
              event.confirmedAt
            )

            State.Running(conversion)
        }
      case state: State.Initialized =>
        event match {
          case event: ManagerEvent.Quoted =>
            State.Quoted(
              state.customerId,
              event.sell,
              event.buy,
              event.conversionRate,
              event.sellAmount,
              event.validUntil,
              None,
              None
            )
        }
    }
  }

  private def addToIndex(index: ActorRef[Index.Command], state: State.Running): Unit = {
    index.tell(Index.Command.AddPayerId(state.id, state.payer.id))
    index.tell(Index.Command.AddBeneficiaryId(state.id, state.beneficiary.id))
    index.tell(Index.Command.AddCustomerId(state.id, state.customerId))
  }

}
