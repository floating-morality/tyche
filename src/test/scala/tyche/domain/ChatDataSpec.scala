package tyche.domain

import cats.data.Chain
import io.circe.parser.decode
import io.circe.syntax.*
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ChatDataSpec extends AnyWordSpec, Matchers, EitherValues:

  "ChatData JSON codec" when {

    "messageTemplate is present" should {
      "round-trip" in {
        val data = ChatData(
          MessageTemplate("The Sorting Hat has decided... {item} goes to Gryffindor!"),
          Chain("Justine", "Monika")
        )
        val json = data.asJson.noSpaces
        decode[ChatData](json).value shouldBe data
      }
    }

    "messageTemplate is absent in JSON" should {
      "decode with the default template" in {
        val json    = """{"items":["Justine", "Monika"]}"""
        val decoded = decode[ChatData](json)
        decoded shouldBe Right(ChatData(MessageTemplate.Default, Chain("Justine", "Monika")))
      }
    }
  }
