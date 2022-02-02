package xyz.mibon.remittance
package tools

import java.time.Instant

object Clock {
  val default: Clock = new Clock
}

class Clock {

  def now(): Instant = Instant.now()

}
