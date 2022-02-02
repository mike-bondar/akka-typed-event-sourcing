package xyz.mibon.remittance
package conversions

import bankaccounts.BankAccount
import crm.Customer

import java.time.Instant
import java.util.{Currency, UUID}

object Conversion {
  object Id {
    def unique(): Id = Id(UUID.randomUUID())
  }

  case class Id(id: UUID) extends AnyVal

  sealed trait Status

  object Status {
    case object Pending extends Status
  }
}

case class Conversion(
    id: Conversion.Id,
    customerId: Customer.Id,
    sell: Currency,
    buy: Currency,
    sellAmount: BigDecimal,
    conversionRate: BigDecimal,
    beneficiary: BankAccount,
    payer: BankAccount,
    status: Conversion.Status,
    startedAt: Instant
)
