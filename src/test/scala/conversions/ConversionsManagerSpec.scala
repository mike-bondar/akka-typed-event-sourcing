package xyz.mibon.remittance
package conversions

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.flatspec.AnyFlatSpecLike
import conversions.ConversionsManager.{Command, QuotationResponse, Response}
import eventsourcing.{EventSourcedBehaviour, Storage}
import tools.{ActorFactory, Clock}

import akka.actor.typed.{ActorRef, Behavior}
import crm.Customer
import bankaccounts.{BankAccount, BankAccountManager, BankAccountSupervisor, BankAccountsModule}

import java.util.Currency
import scala.concurrent.ExecutionContext.Implicits.global

object ConversionsManagerSpec {
  private def quoteCommand(replyTo: ActorRef[QuotationResponse]) =
    Command.Quote(Currency.getInstance("EUR"), Currency.getInstance("USD"), 200_000, replyTo)
}

class ConversionsManagerSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike {
  import ConversionsManagerSpec._

  private val config = Storage.FS.Config("db")

  private val actorFactory = new ActorFactory {
    override def spawn[A](behavior: Behavior[A], name: String): ActorRef[A] =
      ConversionsManagerSpec.this.spawn(behavior, name)
  }

  private val eventSourcedBehaviour = new EventSourcedBehaviour(new Storage.FS(config))

  private val bankAccountsModule = new BankAccountsModule(eventSourcedBehaviour, Clock.default, actorFactory)

  private val module = new ConversionsModule(eventSourcedBehaviour, Clock.default, bankAccountsModule, actorFactory)

  "ConversionManager" should "process quotation" in {
    val customerId      = Customer.Id.unique()
    val responseHandler = testKit.createTestProbe[Any]()
    val quote           = quoteCommand(responseHandler.ref)

    module.conversionsSupervisor.tell(
      ConversionsSupervisor.Command.Start {
        Command.Initialize(customerId, responseHandler.ref)
      }
    )

    val conversionId = responseHandler.expectMessageType[Response.Initialized].id

    module.conversionsSupervisor.tell(
      ConversionsSupervisor.Command.Forward(
        conversionId,
        quote,
        responseHandler.ref
      )
    )

    responseHandler.expectMessageType[Response.Quotation]

    module.conversionsSupervisor.tell(
      ConversionsSupervisor.Command.Forward(
        conversionId,
        Command.SetBeneficiary(BankAccount.Id.unique(), responseHandler.ref),
        responseHandler.ref
      )
    )

    responseHandler.expectMessage(Response.UnknownBankAccount)

    {
      val bankAccountData = bankaccounts.Data.bankAccount()
      bankAccountsModule.bankAccountSupervisor.tell(
        BankAccountSupervisor.Command.Create(
          BankAccountManager.Command.Create(
            customerId,
            quote.buy,
            bankAccountData.details,
            bankAccountData.address,
            responseHandler.ref
          )
        )
      )
    }

    val beneficiaryId = responseHandler.expectMessageType[BankAccount.Id]

    val setBeneficiaryCommand = ConversionsSupervisor.Command.Forward(
      conversionId,
      Command.SetBeneficiary(beneficiaryId, responseHandler.ref),
      responseHandler.ref
    )

    module.conversionsSupervisor.tell(setBeneficiaryCommand)

    responseHandler.expectMessage(Response.InactiveBankAccount)

    {
      val command = BankAccountSupervisor.Command.Forward(
        beneficiaryId,
        BankAccountManager.Command.Get(responseHandler.ref),
        responseHandler.ref
      )

      bankAccountsModule.bankAccountSupervisor.tell(command)
      while (!responseHandler.expectMessageType[Some[BankAccount]].get.active) {
        Thread.sleep(10)
        bankAccountsModule.bankAccountSupervisor.tell(command)
      }
    }

    module.conversionsSupervisor.tell(setBeneficiaryCommand)

    responseHandler.expectMessage(Response.BankAccountSet)

    module.conversionsSupervisor.tell(
      ConversionsSupervisor.Command.Forward(
        conversionId,
        Command.SetPayer(BankAccount.Id.unique(), responseHandler.ref),
        responseHandler.ref
      )
    )

    responseHandler.expectMessage(Response.UnknownBankAccount)

    module.conversionsSupervisor.tell(
      ConversionsSupervisor.Command.Forward(
        conversionId,
        Command.SetPayer(beneficiaryId, responseHandler.ref),
        responseHandler.ref
      )
    )

    responseHandler.expectMessage(Response.WrongCurrency)

    {
      val bankAccountData = bankaccounts.Data.bankAccount()
      bankAccountsModule.bankAccountSupervisor.tell(
        BankAccountSupervisor.Command.Create(
          BankAccountManager.Command.Create(
            customerId,
            quote.sell,
            bankAccountData.details,
            bankAccountData.address,
            responseHandler.ref
          )
        )
      )
    }

    val payerId = responseHandler.expectMessageType[BankAccount.Id]

    val setPayerCommand = ConversionsSupervisor.Command.Forward(
      conversionId,
      Command.SetPayer(payerId, responseHandler.ref),
      responseHandler.ref
    )

    module.conversionsSupervisor.tell(setPayerCommand)

    responseHandler.expectMessage(Response.InactiveBankAccount)

    {
      val command = BankAccountSupervisor.Command.Forward(
        payerId,
        BankAccountManager.Command.Get(responseHandler.ref),
        responseHandler.ref
      )

      bankAccountsModule.bankAccountSupervisor.tell(command)
      while (!responseHandler.expectMessageType[Some[BankAccount]].get.active) {
        Thread.sleep(50)
        bankAccountsModule.bankAccountSupervisor.tell(command)
      }
    }

    module.conversionsSupervisor.tell(setPayerCommand)

    responseHandler.expectMessage(Response.BankAccountSet)

    module.conversionsIndex.tell(Index.Command.FindByCustomerId(customerId, responseHandler.ref))
    responseHandler.expectMessage(Nil)

    module.conversionsSupervisor.tell(
      ConversionsSupervisor.Command.Forward(
        conversionId,
        Command.Confirm(responseHandler.ref),
        responseHandler.ref
      )
    )
    responseHandler.expectMessageType[Response.Confirmed]

    module.conversionsIndex.tell(Index.Command.FindByCustomerId(customerId, responseHandler.ref))
    responseHandler.expectMessage(List(conversionId))
  }

}
