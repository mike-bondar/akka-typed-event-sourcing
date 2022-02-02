package xyz.mibon.remittance
package conversions.json

import io.circe.{Codec, Decoder, Encoder}

import conversions.Conversion

object Implicits extends Implicits

trait Implicits {

  implicit val conversionIdCodec: Codec[Conversion.Id] =
    Codec.from(
      Decoder.decodeUUID.map(Conversion.Id.apply),
      Encoder.encodeUUID.contramap(_.id)
    )

}
