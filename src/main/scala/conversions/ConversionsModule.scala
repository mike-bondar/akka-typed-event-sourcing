package xyz.mibon.remittance
package conversions

import eventsourcing.EventSourcedBehaviour

import akka.actor.typed.ActorRef
import tools.{ActorFactory, Clock}

import bankaccounts.BankAccountsModule

class ConversionsModule(
    eventSourcedBehaviour: EventSourcedBehaviour,
    clock: Clock,
    bankAccountsModule: BankAccountsModule,
    actorFactory: ActorFactory
) {

  val quotesManager: ActorRef[QuotesManager.Command] =
    actorFactory.spawn(new QuotesManager(clock).build(), "conversions-quotes-manager")

  val conversionsIndex: ActorRef[Index.Command] = {
    actorFactory.spawn(new Index(eventSourcedBehaviour).build(), "conversions-index")
  }

  val conversionsManager =
    new ConversionsManager(
      clock,
      eventSourcedBehaviour,
      quotesManager,
      conversionsIndex,
      bankAccountsModule.bankAccountSupervisor
    )

  val conversionsSupervisor: ActorRef[ConversionsSupervisor.Command] = {
    actorFactory.spawn(
      new ConversionsSupervisor(eventSourcedBehaviour, conversionsManager).build(),
      "conversions-supervisor"
    )
  }

}
