package xyz.mibon.remittance
package bankaccounts

import akka.actor.typed.ActorRef
import eventsourcing.EventSourcedBehaviour
import tools.{ActorFactory, Clock}

class BankAccountsModule(es: EventSourcedBehaviour, clock: Clock, factory: ActorFactory) {

  private val complianceManager: ActorRef[ComplianceManager.Command] =
    factory.spawn(new ComplianceManager().build(), "compliance-manager")

  private val manager = new BankAccountManager(es, complianceManager, clock)

  val bankAccountSupervisor: ActorRef[BankAccountSupervisor.Command] =
    factory.spawn(new BankAccountSupervisor(es, manager).build(), "bank-account-supervisor")

}
