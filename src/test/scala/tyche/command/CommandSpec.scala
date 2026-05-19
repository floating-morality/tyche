package tyche.command

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CommandSpec extends AnyWordSpec, Matchers:

  "Command.matches" should {

    "match the exact command" in {
      Command.matches("/foo", "/foo") shouldBe true
    }

    "match the command followed by a space and arguments" in {
      Command.matches("/foo bar baz", "/foo") shouldBe true
    }

    "match the command with a bot mention suffix" in {
      Command.matches("/foo@my_bot", "/foo") shouldBe true
    }

    "not match a different command with a shared prefix" in {
      Command.matches("/foobar", "/foo") shouldBe false
    }

    "not match a completely different command" in {
      Command.matches("/bar", "/foo") shouldBe false
    }
  }
