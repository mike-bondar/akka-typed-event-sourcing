package xyz.mibon.remittance
package bankaccounts

import eventsourcing.{EventSourcedBehaviour, PersistenceId}

import akka.actor.typed.{ActorRef, Behavior}
import crm.Customer
import eventsourcing.EventSourcedBehaviour.Effect
import tools.Clock

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import bankaccounts.BankAccountManager.Command.ComplianceChecked
import bankaccounts.ComplianceManager.ComplianceResult

import java.util.Currency

object BankAccountManager {
  sealed trait Command

  object Command {
    case class Create(
        owner: Customer.Id,
        currency: Currency,
        details: BankAccount.Details,
        address: BankAccount.Address,
        replyTo: ActorRef[BankAccount.Id]
    ) extends Command

    case class Get(replyTo: ActorRef[Option[BankAccount]]) extends Command

    case object StartComplianceCheck                       extends Command
    case class ComplianceChecked(result: ComplianceResult) extends Command
  }

  sealed trait State

  object State {
    case object Empty                            extends State
    case class Running(bankAccount: BankAccount) extends State
  }
}

class BankAccountManager(
    es: EventSourcedBehaviour,
    complianceManager: ActorRef[ComplianceManager.Command],
    clock: Clock
) {
  import BankAccountManager._

  def build(
      id: BankAccount.Id,
      parent: ActorRef[BankAccountSupervisor.InternalCommand]
  ): Behavior[BankAccountManager.Command] =
    Behaviors.setup { ctx =>
      es.build[ManagerEvent, BankAccountManager.Command, BankAccountManager.State](
        PersistenceId(s"bank-account-${id.id.toString}"),
        BankAccountManager.State.Empty,
        commandHandler(id, parent, ctx),
        eventHandler(id, ctx),
        onRecoveryCompleted(ctx)
      )
    }

  def onRecoveryCompleted(ctx: ActorContext[Command])(state: State) =
    state match {
      case State.Running(bankAccount) if bankAccount.status == BankAccount.Status.Pending =>
        ctx.self.tell(BankAccountManager.Command.StartComplianceCheck)
      case _ =>
    }

  def commandHandler(
      id: BankAccount.Id,
      parent: ActorRef[BankAccountSupervisor.InternalCommand],
      ctx: ActorContext[Command]
  ): (Command, State) => Effect[ManagerEvent, State] = {
    case (cmd: BankAccountManager.Command.Create, BankAccountManager.State.Empty) =>
      Effect.persist(ManagerEvent.Created(cmd.owner, cmd.currency, cmd.details, cmd.address)).thenRun { _ =>
        parent.tell(BankAccountSupervisor.InternalCommand.BankAccountCreated(id, ctx.self))
        cmd.replyTo.tell(id)
        ctx.self.tell(Command.StartComplianceCheck)
      }

    case (Command.StartComplianceCheck, state: State.Running) =>
      val adapter = ctx.messageAdapter(result => ComplianceChecked(result))
      val command = ComplianceManager.Command.StartCompliance(state.bankAccount, adapter)
      complianceManager.tell(command)
      Effect.none

    case (Command.ComplianceChecked(result), _: State.Running) =>
      val now = clock.now()
      result match {
        case ComplianceResult.Approved => Effect.persist(ManagerEvent.Approved(now))
        case ComplianceResult.Rejected => Effect.persist(ManagerEvent.Rejected(now))
      }

    case (cmd: Command.Get, state: State.Running) =>
      cmd.replyTo.tell(Some(state.bankAccount))
      Effect.none

    case (cmd: Command.Get, State.Empty) =>
      cmd.replyTo.tell(None)
      Effect.none

    case (cmd: Command.Create, _: State.Running) =>
      cmd.replyTo.tell(id)
      Effect.none
  }

  def eventHandler(id: BankAccount.Id, ctx: ActorContext[Command]): (ManagerEvent, State) => State = {
    case (create: ManagerEvent.Created, BankAccountManager.State.Empty) =>
      val now = clock.now()
      val bankAccount = BankAccount(
        id,
        create.owner,
        now,
        now,
        create.currency,
        create.details,
        create.address,
        BankAccount.Status.Pending
      )

      BankAccountManager.State.Running(bankAccount)
    case (_: ManagerEvent.Created, state: BankAccountManager.State.Running) =>
      ctx.log.warn("can't handle Created when in state different from Empty")
      state
    case (_: ManagerEvent.Approved, state: State.Running) =>
      state.copy(bankAccount = state.bankAccount.copy(status = BankAccount.Status.Active))
    case (_: ManagerEvent.Rejected, state: State.Running) =>
      state.copy(bankAccount = state.bankAccount.copy(status = BankAccount.Status.Rejected))
    case (event, state) =>
      ctx.log.warn(s"can't apply ${event.getClass.getName} while in ${state.getClass.getName}")
      state
  }

}
