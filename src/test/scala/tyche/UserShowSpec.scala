package tyche

import cats.syntax.option.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import telegramium.bots.User
import tyche.UserShow.auditString

class UserShowSpec extends AnyWordSpec, Matchers:

  private def user(id: Long, username: Option[String]): User =
    User(id = id, isBot = false, firstName = "Test", username = username)

  "UserShow.auditString" should {

    "render the username when present" in {
      user(42L, "justine".some).some.auditString shouldBe "User(id=42, @justine)"
    }

    "render only the id when no username" in {
      user(42L, none).some.auditString shouldBe "User(id=42)"
    }

    "return 'unknown user' when there is no user" in {
      none.auditString shouldBe "unknown user"
    }
  }
