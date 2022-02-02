package xyz.mibon.remittance
package bankaccounts

import crm.Customer

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import tools.CodecBuilder

import java.util.Currency
import json.Implicits._
import crm.json.Implicits._

import java.time.Instant

trait ManagerEvent

object ManagerEvent {

  implicit val codec: Codec[ManagerEvent] = CodecBuilder.`enum`(
    {
      case m: Created  => Created.codec(m)  -> "Created"
      case m: Approved => Approved.codec(m) -> "Approved"
      case m: Rejected => Rejected.codec(m) -> "Rejected"
    },
    {
      case "Created"  => Created.codec.map(_.asInstanceOf[ManagerEvent])
      case "Approved" => Approved.codec.map(_.asInstanceOf[ManagerEvent])
      case "Rejected" => Rejected.codec.map(_.asInstanceOf[ManagerEvent])

    }
  )

  object Created {
    val codec: Codec[Created] = deriveCodec
  }

  case class Created(owner: Customer.Id, currency: Currency, details: BankAccount.Details, address: BankAccount.Address)
      extends ManagerEvent

  object Approved {
    val codec: Codec[Approved] = deriveCodec
  }

  case class Approved(at: Instant) extends ManagerEvent

  object Rejected {
    val codec: Codec[Rejected] = deriveCodec
  }

  case class Rejected(at: Instant) extends ManagerEvent
}
