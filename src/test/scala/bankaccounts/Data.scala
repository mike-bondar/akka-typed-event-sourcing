package xyz.mibon.remittance
package bankaccounts

import crm.Customer

import java.time.Instant
import java.util.{Currency, UUID}

object Data {

  def bankAccountAddress(): BankAccount.Address = BankAccount.Address(
    addressLine1 = "Street",
    addressLine2 = Some("21"),
    city = "City",
    country = "UA",
    state = "State"
  )

  def bankAccount(owner: Customer.Id = Customer.Id.unique()): BankAccount = BankAccount(
    BankAccount.Id(UUID.randomUUID()),
    owner = owner,
    createdAt = Instant.now,
    updatedAt = Instant.now,
    currency = Currency.getInstance("EUR"),
    details = BankAccount.Details.Company("Company"),
    address = bankAccountAddress(),
    status = BankAccount.Status.Active
  )

}
