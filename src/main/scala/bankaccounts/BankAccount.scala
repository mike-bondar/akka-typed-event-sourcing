package xyz.mibon.remittance
package bankaccounts

import crm.Customer

import java.time.Instant
import java.util.{Currency, UUID}

object BankAccount {
  object Id {
    def unique(): Id = Id(UUID.randomUUID())
  }

  case class Id(id: UUID) extends AnyVal

  sealed trait Details

  object Details {
    case class Company(
        name: String
    ) extends Details

    case class Individual(
        name: String
    ) extends Details
  }

  case class Address(
      addressLine1: String,
      addressLine2: Option[String],
      city: String,
      state: String,
      country: String
  )

  sealed trait Status

  object Status {
    case object Pending  extends Status
    case object Active   extends Status
    case object Rejected extends Status
  }
}

case class BankAccount(
    id: BankAccount.Id,
    owner: Customer.Id,
    createdAt: Instant,
    updatedAt: Instant,
    currency: Currency,
    details: BankAccount.Details,
    address: BankAccount.Address,
    status: BankAccount.Status
) {

  val active: Boolean = status == BankAccount.Status.Active

}
