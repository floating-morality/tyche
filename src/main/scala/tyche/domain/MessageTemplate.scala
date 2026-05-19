package tyche.domain

import cats.Show
import io.circe.{Decoder, Encoder}

opaque type MessageTemplate = String

object MessageTemplate:

  given Show[MessageTemplate] with
    override def show(t: MessageTemplate): String = t

  val Placeholder              = "{item}"
  val Default: MessageTemplate = s"I pick $Placeholder"

  def apply(s: String): MessageTemplate = s

  extension (t: MessageTemplate)
    def value: String                 = t
    def replace(name: String): String = t.replace(Placeholder, name)

  given Encoder[MessageTemplate] = Encoder.encodeString
  given Decoder[MessageTemplate] = Decoder.decodeString
