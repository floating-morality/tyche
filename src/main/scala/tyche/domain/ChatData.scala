package tyche.domain

import cats.data.Chain
import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}

final case class ChatData(messageTemplate: MessageTemplate, items: Chain[String])

object ChatData:
  given Encoder[ChatData] = deriveEncoder
  given Decoder[ChatData] = Decoder.instance { c =>
    for
      template <- c.getOrElse[MessageTemplate]("messageTemplate")(MessageTemplate.Default)
      items    <- c.get[Chain[String]]("items")
    yield ChatData(template, items)
  }

  val Empty: ChatData = ChatData(MessageTemplate.Default, Chain.empty)
