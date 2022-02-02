package xyz.mibon.remittance
package eventsourcing

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.Materializer
import io.circe.Codec

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

object EventSourcedBehaviour {

  private val StashSize: Int = 1_000

  sealed trait ESMessage

  object ESMessage {
    case class RecoverCompleted[State](data: State)      extends ESMessage
    case class RecoveryFailed(error: Throwable)          extends ESMessage
    case class ForwardCommand[Command](command: Command) extends ESMessage
    case class EventSaved[Event](event: Event)           extends ESMessage
    case class ErrorSavingEvent(error: Throwable)        extends ESMessage
  }

  sealed trait Effect[+Event, State] {
    def thenRun(fn: State => Any): Effect[Event, State] = this match {
      case none: Effect.None[State]            => none.copy(callback = Some(fn))
      case stop: Effect.Stop[State]            => stop.copy(callback = Some(fn))
      case apply: Effect.Persist[Event, State] => apply.copy(callback = Some(fn))
    }
  }

  object Effect {
    case class None[State](callback: Option[State => Any] = scala.None) extends Effect[Nothing, State]
    case class Stop[State](callback: Option[State => Any] = scala.None) extends Effect[Nothing, State]
    case class Persist[Event, State](event: Event, callback: Option[State => Any] = scala.None)
        extends Effect[Event, State]

    def none[State]: None[State]                                   = None()
    def stop[State]: Stop[Nothing]                                 = Stop()
    def persist[Event, State](event: Event): Persist[Event, State] = Persist(event)
  }
}

class EventSourcedBehaviour(storage: Storage)(implicit mat: Materializer) {
  import EventSourcedBehaviour._

  def build[Event, Command, State](
      persistentId: PersistenceId,
      emptyState: State,
      commandHandler: (Command, State) => Effect[Event, State],
      eventHandler: (Event, State) => State,
      onRecoveryCompleted: State => Unit = (_: State) => (): Unit
  )(implicit codec: Codec[Event], ct: ClassTag[Command]): Behavior[Command] = {

    def handleEvent(
        event: Event,
        currentState: State,
        whenDone: Option[State => Any]
    ): Behavior[ESMessage] = {
      Behaviors.setup[ESMessage] { ctx =>
        val savingEvent = storage.write(persistentId, event)

        ctx.pipeToSelf(savingEvent) {
          case Success(_)         => ESMessage.EventSaved(event)
          case Failure(throwable) => ESMessage.ErrorSavingEvent(throwable)
        }

        Behaviors.withStash[ESMessage](StashSize) { stash =>
          Behaviors.receiveMessagePartial {
            case ESMessage.EventSaved(`event`) =>
              val newState = eventHandler(event, currentState)
              whenDone.foreach(_.apply(newState))
              stash.unstashAll(running(newState))
            case ESMessage.ErrorSavingEvent(error) =>
              ctx.log.error("Error while saving an event", error)
              stash.clear()
              throw error
            case cmd: ESMessage.ForwardCommand[_] =>
              stash.stash(cmd)
              Behaviors.same
          }
        }
      }
    }

    def handleEffect(effect: Effect[Event, State], currentState: State): Behavior[ESMessage] =
      effect match {
        case Effect.None(fn)           => fn.foreach(_.apply(currentState)); Behaviors.same
        case Effect.Stop(fn)           => fn.foreach(_.apply(currentState)); Behaviors.stopped
        case Effect.Persist(event, fn) => handleEvent(event, currentState, fn)
      }

    def running(state: State): Behavior[ESMessage] = {
      Behaviors.setup { ctx =>
        Behaviors.receiveMessage {
          case fwd: ESMessage.ForwardCommand[Command] =>
            val effect = commandHandler(fwd.command, state)
            handleEffect(effect, state)
          case other =>
            ctx.log.warn(s"don't know how to handle $other")
            Behaviors.unhandled
        }
      }
    }

    def recovering(): Behavior[ESMessage] =
      Behaviors.setup[ESMessage] { ctx =>
        val future: Future[State] =
          storage
            .allEvents[Event](persistentId)
            .runFold(emptyState) { case (state, event) => eventHandler(event, state) }

        ctx.pipeToSelf(future) {
          case Success(state)     => ESMessage.RecoverCompleted(state)
          case Failure(exception) => ESMessage.RecoveryFailed(exception)
        }

        Behaviors.withStash[ESMessage](StashSize) { stash =>
          Behaviors.receiveMessage {
            case ESMessage.RecoverCompleted(state: State) =>
              onRecoveryCompleted(state)
              stash.unstashAll(running(state))
            case ESMessage.RecoverCompleted(_) =>
              Behaviors.stopped
            case ESMessage.RecoveryFailed(error) =>
              ctx.log.error(s"error during state recovery for ${persistentId.id}", error)
              stash.clear()
              Behaviors.stopped
            case fwd: ESMessage.ForwardCommand[Command] =>
              stash.stash(fwd)
              Behaviors.same
            case other =>
              ctx.log.error(s"unexpected message $other received. stopping an actor")
              Behaviors.stopped
          }
        }
      }

    recovering().transformMessages { case cmd: Command =>
      ESMessage.ForwardCommand(cmd)
    }
  }
}
