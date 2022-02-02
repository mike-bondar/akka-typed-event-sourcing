package xyz.mibon.remittance
package bankaccounts.json

import bankaccounts.BankAccount

import io.circe.{Codec, Decoder, DecodingFailure, Encoder}
import crm.json.Implicits._

import io.circe.generic.semiauto.deriveCodec
import tools.CodecBuilder

object Implicits extends Implicits {}

trait Implicits {
  implicit val bankAccountIdCodec: Codec[BankAccount.Id] =
    Codec.from(
      Decoder.decodeUUID.map(BankAccount.Id.apply),
      Encoder.encodeUUID.contramap(_.id)
    )

  implicit val bankAccountStatusCodec: Codec[BankAccount.Status] = Codec.from(
    Decoder.decodeString.flatMap {
      case "Active"  => Decoder.const(BankAccount.Status.Active)
      case "Pending" => Decoder.const(BankAccount.Status.Pending)
      case other     => Decoder.failed(DecodingFailure(s"Unknown bank account status $other", Nil))
    },
    Encoder.encodeString.contramap {
      case BankAccount.Status.Active  => "Active"
      case BankAccount.Status.Pending => "Pending"
    }
  )

  implicit val detailsCodec: Codec[BankAccount.Details] = {
    val companyCodec    = deriveCodec[BankAccount.Details.Company]
    val individualCodec = deriveCodec[BankAccount.Details.Individual]

    CodecBuilder.`enum`[BankAccount.Details](
      {
        case company: BankAccount.Details.Company       => companyCodec(company)       -> "Company"
        case individual: BankAccount.Details.Individual => individualCodec(individual) -> "Individual"
      },
      {
        case "Company"    => companyCodec.map(_.asInstanceOf[BankAccount.Details])
        case "Individual" => individualCodec.map(_.asInstanceOf[BankAccount.Details])
      }
    )
  }

  implicit val addressCodec: Codec[BankAccount.Address] = deriveCodec

  implicit val bankAccountCodec: Codec[BankAccount] = deriveCodec[BankAccount]
}
