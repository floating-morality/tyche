package tyche

import cats.Show
import cats.syntax.show.*
import telegramium.bots.User

object UserShow:
  given Show[User] = Show.show { user =>
    user.username
      .fold(s"User(id=${user.id})") { username =>
        s"User(id=${user.id}, @$username)"
      }
  }

  extension (from: Option[User]) def auditString: String = from.fold("unknown user")(_.show)
