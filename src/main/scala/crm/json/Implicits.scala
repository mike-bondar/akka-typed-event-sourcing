package xyz.mibon.remittance
package crm.json

import io.circe.{Codec, Decoder, Encoder}
import crm.Customer

object Implicits extends Implicits

trait Implicits {

  implicit val customerIdCodec: Codec[Customer.Id] =
    Codec.from(
      Decoder.decodeUUID.map(Customer.Id.apply),
      Encoder.encodeUUID.contramap(_.id)
    )

}
