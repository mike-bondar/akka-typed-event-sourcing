package xyz.mibon.remittance
package conversions

import eventsourcing.{EventSourcedBehaviour, PersistenceId}

import akka.actor.typed.{ActorRef, Behavior}
import crm.Customer
import eventsourcing.EventSourcedBehaviour.Effect

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import tools.CodecBuilder

import json.Implicits._
import crm.json.Implicits._

import bankaccounts.BankAccount
import bankaccounts.json.Implicits._

object Index {
  sealed trait Command

  object Command {
    case class AddCustomerId(conversionId: Conversion.Id, customerId: Customer.Id)               extends Command
    case class AddBeneficiaryId(conversionId: Conversion.Id, beneficiaryId: BankAccount.Id)      extends Command
    case class AddPayerId(conversionId: Conversion.Id, payerId: BankAccount.Id)                  extends Command
    case class FindByCustomerId(customerId: Customer.Id, replyTo: ActorRef[List[Conversion.Id]]) extends Command
  }

  sealed trait Event

  object Event {
    implicit val codec: Codec[Event] = {
      CodecBuilder.`enum`(
        {
          case evt: CustomerIdAdded    => CustomerIdAdded.codec(evt)    -> "CustomerIdAdded"
          case evt: PayerIdAdded       => PayerIdAdded.codec(evt)       -> "PayerIdAdded"
          case evt: BeneficiaryIdAdded => BeneficiaryIdAdded.codec(evt) -> "BeneficiaryIdAdded"
        },
        {
          case "CustomerIdAdded"    => CustomerIdAdded.codec.map(_.asInstanceOf[Event])
          case "PayerIdAdded"       => PayerIdAdded.codec.map(_.asInstanceOf[Event])
          case "BeneficiaryIdAdded" => BeneficiaryIdAdded.codec.map(_.asInstanceOf[Event])
        }
      )
    }

    object CustomerIdAdded {
      val codec: Codec[CustomerIdAdded] = deriveCodec
    }

    case class CustomerIdAdded(conversionId: Conversion.Id, customerId: Customer.Id) extends Event

    object PayerIdAdded {
      val codec: Codec[PayerIdAdded] = deriveCodec
    }

    case class PayerIdAdded(conversionId: Conversion.Id, bankAccountId: BankAccount.Id) extends Event

    object BeneficiaryIdAdded {
      val codec: Codec[BeneficiaryIdAdded] = deriveCodec
    }

    case class BeneficiaryIdAdded(conversionId: Conversion.Id, bankAccountId: BankAccount.Id) extends Event
  }

  object State {
    val empty: State = State(Map.empty, Map.empty, Map.empty)
  }

  case class State(
      customerIdIndex: Map[Customer.Id, List[Conversion.Id]],
      beneficiaryIdIndex: Map[BankAccount.Id, List[Conversion.Id]],
      payerIdIndex: Map[BankAccount.Id, List[Conversion.Id]]
  ) {

    def addCustomerId(customerId: Customer.Id, conversionId: Conversion.Id): State = {
      val updated = customerIdIndex.updatedWith(customerId) {
        case Some(list) => Some(conversionId :: list)
        case None       => Some(conversionId :: Nil)
      }
      copy(customerIdIndex = updated)
    }

    def addBeneficiaryId(bankAccountId: BankAccount.Id, conversionId: Conversion.Id): State = {
      val updated = beneficiaryIdIndex.updatedWith(bankAccountId) {
        case Some(list) => Some(conversionId :: list)
        case None       => Some(conversionId :: Nil)
      }
      copy(beneficiaryIdIndex = updated)
    }

    def addPayerId(bankAccountId: BankAccount.Id, conversionId: Conversion.Id): State = {
      val updated = payerIdIndex.updatedWith(bankAccountId) {
        case Some(list) => Some(conversionId :: list)
        case None       => Some(conversionId :: Nil)
      }
      copy(payerIdIndex = updated)
    }

    def findByCustomerId(customerId: Customer.Id): List[Conversion.Id] =
      customerIdIndex.getOrElse(customerId, Nil)
  }
}

class Index(eventSourcedBehaviour: EventSourcedBehaviour) {
  import Index._

  def build(id: String = "conversions-index-global"): Behavior[Command] =
    eventSourcedBehaviour.build[Event, Command, State](
      PersistenceId(id),
      State.empty,
      (command, state) =>
        command match {
          case Command.AddCustomerId(conversionId, customerId) =>
            Effect.persist(Event.CustomerIdAdded(conversionId, customerId))
          case Command.AddPayerId(conversionId, payerId) =>
            Effect.persist(Event.PayerIdAdded(conversionId, payerId))
          case Command.AddBeneficiaryId(conversionId, beneficiaryId) =>
            Effect.persist(Event.BeneficiaryIdAdded(conversionId, beneficiaryId))
          case Command.FindByCustomerId(customerId, replyTo) =>
            replyTo.tell(state.findByCustomerId(customerId))
            Effect.none
        },
      (event, state) => {
        event match {
          case Event.CustomerIdAdded(conversionId, customerId) =>
            state.addCustomerId(customerId, conversionId)
          case Event.BeneficiaryIdAdded(conversionId, bankAccountId) =>
            state.addBeneficiaryId(bankAccountId, conversionId)
          case Event.PayerIdAdded(conversionId, bankAccountId) =>
            state.addPayerId(bankAccountId, conversionId)
        }
      }
    )

}
