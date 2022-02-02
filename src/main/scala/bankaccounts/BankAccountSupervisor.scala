package xyz.mibon.remittance
package bankaccounts

import eventsourcing.{EventSourcedBehaviour, PersistenceId}
import eventsourcing.EventSourcedBehaviour.Effect

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import tools.CodecBuilder

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import json.Implicits._

object BankAccountSupervisor {
  sealed trait Event

  object Event {
    implicit val codec: Codec[Event] = CodecBuilder.`enum`(
      { case m: Created =>
        Created.codec(m) -> "Created"
      },
      { case "Created" =>
        Created.codec.map(_.asInstanceOf[Event])
      }
    )

    object Created {
      val codec: Codec[Created] = deriveCodec
    }

    case class Created(id: BankAccount.Id) extends Event
  }

  sealed trait Command

  object Command {
    case class Forward(
        id: BankAccount.Id,
        command: BankAccountManager.Command,
        onNotFound: ActorRef[Response.BankAccountNotFound.type]
    ) extends Command

    case class Create(command: BankAccountManager.Command.Create) extends Command
  }

  sealed trait InternalCommand extends Command

  object InternalCommand {
    case class BankAccountCreated(id: BankAccount.Id, ref: ActorRef[BankAccountManager.Command]) extends InternalCommand
  }

  sealed trait Response

  object Response {
    case object BankAccountNotFound extends Response
  }

  object State {
    val empty: State = State(Set.empty)
  }

  case class State(knownAccounts: Set[BankAccount.Id]) extends AnyVal {
    def add(id: BankAccount.Id): State = State(knownAccounts + id)

    def contains(id: BankAccount.Id): Boolean = knownAccounts.contains(id)
  }
}

class BankAccountSupervisor(es: EventSourcedBehaviour, manager: BankAccountManager) {
  import BankAccountSupervisor._

  private val routes: collection.mutable.Map[BankAccount.Id, ActorRef[BankAccountManager.Command]] =
    collection.mutable.Map.empty

  def build(): Behavior[Command] =
    Behaviors.setup { ctx =>
      es.build[Event, Command, State](
        PersistenceId("bank-accounts-supervisor"),
        BankAccountSupervisor.State.empty,
        commandHandler(ctx),
        handleEvent
      )
    }

  def commandHandler(ctx: ActorContext[Command]): (Command, State) => Effect[Event, State] = {
    case (InternalCommand.BankAccountCreated(id, ref), _) =>
      routes.update(id, ref)
      Effect.persist(Event.Created(id))

    case (Command.Create(cmd), _) =>
      val nextId                                    = BankAccount.Id.unique()
      val ref: ActorRef[BankAccountManager.Command] = ctx.spawnAnonymous(manager.build(nextId, ctx.self))
      ref.tell(cmd)
      Effect.none

    case (Command.Forward(id, command, ref), state) =>
      if (state.contains(id)) {
        routes.get(id) match {
          case Some(ref) => ref.tell(command)
          case None =>
            val ref: ActorRef[BankAccountManager.Command] = ctx.spawnAnonymous(manager.build(id, ctx.self))
            routes.update(id, ref)
            ref.tell(command)
        }
      } else {
        ref.tell(Response.BankAccountNotFound)
      }
      Effect.none
  }

  val handleEvent: (Event, State) => State = { case (Event.Created(id), state) =>
    state.add(id)
  }
}
