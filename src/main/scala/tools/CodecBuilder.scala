package xyz.mibon.remittance
package tools

import io.circe.{Codec, Decoder, DecodingFailure, Encoder, Json}

object CodecBuilder {

  def `enum`[A](encode: A => (Json, String), decode: String => Decoder[A]): Codec[A] = {

    val encoder: Encoder[A] = (a: A) => {
      val (json, name) = encode(a)
      json.mapObject(_.add("$type", Json.fromString(name)))
    }

    val decoder = Decoder.decodeJsonObject.flatMap { obj =>
      obj("$type").flatMap(_.asString) match {
        case Some(tpe) => decode(tpe)
        case None      => Decoder.failed[A](DecodingFailure("can't find the $type field", Nil))
      }
    }

    Codec.from(decoder, encoder)
  }

}
