package xyz.mibon.remittance
package conversions

import eventsourcing.{EventSourcedBehaviour, PersistenceId}

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import eventsourcing.EventSourcedBehaviour.Effect
import tools.CodecBuilder

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import json.Implicits._

object ConversionsSupervisor {
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

    case class Created(id: Conversion.Id) extends Event
  }

  sealed trait Command

  sealed trait InternalCommand extends Command

  object Command {
    case class ConversionCreated(id: Conversion.Id, ref: ActorRef[ConversionsManager.Command]) extends InternalCommand
    case class Forward(
        id: Conversion.Id,
        message: ConversionsManager.Command,
        whenNotFound: ActorRef[Responses.ConversionNotFound.type]
    ) extends Command
    case class Start(conversionsManager: ConversionsManager.Command.Initialize) extends Command
  }

  object Responses {
    case object ConversionNotFound
  }

  object State {
    val empty = State(Set.empty)
  }

  case class State(knownConversions: Set[Conversion.Id]) extends AnyVal {
    def add(conversionId: Conversion.Id): State = State(knownConversions + conversionId)

    def contains(conversionId: Conversion.Id): Boolean = knownConversions.contains(conversionId)
  }
}

class ConversionsSupervisor(es: EventSourcedBehaviour, conversionsManager: ConversionsManager) {
  import ConversionsSupervisor._

  private val routes = collection.mutable.Map.empty[Conversion.Id, ActorRef[ConversionsManager.Command]]

  def build(): Behavior[Command] =
    Behaviors.setup { ctx =>
      es.build[Event, Command, State](
        PersistenceId("conversions-supervisor"),
        State.empty,
        commandHandler(ctx),
        eventHandler
      )
    }

  def commandHandler(ctx: ActorContext[Command]): (Command, State) => Effect[Event, State] = {
    case (Command.Start(init), _) =>
      val nextId = Conversion.Id.unique()
      val ref    = ctx.spawnAnonymous(conversionsManager.build(nextId, ctx.self))
      ref.tell(init)
      Effect.none
    case (Command.ConversionCreated(id, ref), _) =>
      Effect.persist(Event.Created(id)).thenRun { _ =>
        routes.put(id, ref)
      }
    case (Command.Forward(id, command, onNotFound), state) =>
      if (state.contains(id)) {
        routes.get(id) match {
          case Some(ref) => ref.tell(command)
          case None =>
            val ref = ctx.spawnAnonymous(conversionsManager.build(id, ctx.self))
            routes.put(id, ref)
            ref.tell(command)
        }
      } else {
        onNotFound.tell(Responses.ConversionNotFound)
      }
      Effect.none
  }

  val eventHandler: (Event, State) => State = { case (event: Event.Created, state) =>
    state.add(event.id)
  }

}
