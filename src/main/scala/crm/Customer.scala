package xyz.mibon.remittance
package crm

import java.util.UUID

object Customer {
  object Id {
    def unique(): Id = Id(UUID.randomUUID())
  }

  case class Id(id: UUID) extends AnyVal
}
