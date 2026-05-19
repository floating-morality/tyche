package tyche.command

object CommandHint:
  val AddItems =
    s"""Use:
       |${SetItems.Cmd}
       |${AddItem.Cmd} <item>""".stripMargin

  val NoItems =
    s"No items set. $AddItems"
