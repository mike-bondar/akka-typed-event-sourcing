package xyz.mibon.remittance
package conversions

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import crm.Customer
import tools.CodecBuilder

import java.time.Instant
import java.util.Currency
import crm.json.Implicits._

import bankaccounts.BankAccount
import bankaccounts.json.Implicits._

sealed trait ManagerEvent

object ManagerEvent {

  implicit val codec: Codec[ManagerEvent] =
    CodecBuilder.`enum`(
      {
        case evt: Quoted                => Quoted.codec(evt)                -> "Quoted"
        case evt: BeneficiarySet        => BeneficiarySet.codec(evt)        -> "BeneficiarySet"
        case evt: PayerSet              => PayerSet.codec(evt)              -> "PayerSet"
        case evt: ConversionInitialized => ConversionInitialized.codec(evt) -> "ConversionInitialized"
        case evt: Confirmed             => Confirmed.codec(evt)             -> "Confirmed"
      },
      {
        case "Quoted"                => Quoted.codec.map(_.asInstanceOf[ManagerEvent])
        case "BeneficiarySet"        => BeneficiarySet.codec.map(_.asInstanceOf[ManagerEvent])
        case "PayerSet"              => PayerSet.codec.map(_.asInstanceOf[BeneficiarySet])
        case "ConversionInitialized" => ConversionInitialized.codec.map(_.asInstanceOf[ManagerEvent])
        case "Confirmed"             => Confirmed.codec.map(_.asInstanceOf[ManagerEvent])
      }
    )

  object ConversionInitialized {
    val codec: Codec[ConversionInitialized] = deriveCodec
  }

  case class ConversionInitialized(customerId: Customer.Id) extends ManagerEvent

  object Quoted {
    val codec: Codec[Quoted] = deriveCodec
  }

  case class Quoted(
      sell: Currency,
      buy: Currency,
      sellAmount: BigDecimal,
      conversionRate: BigDecimal,
      validUntil: Instant
  ) extends ManagerEvent

  object BeneficiarySet {
    val codec: Codec[BeneficiarySet] = deriveCodec
  }

  case class BeneficiarySet(beneficiary: BankAccount) extends ManagerEvent

  object PayerSet {
    val codec: Codec[PayerSet] = deriveCodec
  }

  case class PayerSet(payer: BankAccount) extends ManagerEvent

  object Confirmed {
    val codec: Codec[Confirmed] = deriveCodec
  }

  case class Confirmed(beneficiary: BankAccount, payer: BankAccount, confirmedAt: Instant) extends ManagerEvent
}
