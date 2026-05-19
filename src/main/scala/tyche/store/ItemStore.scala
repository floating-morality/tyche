package tyche.store

import cats.data.Chain

trait ItemStore[F[_]]:
  def setItems(chatId: Long, items: Chain[String]): F[Unit]
  def getItems(chatId: Long): F[Chain[String]]
  def addItem(chatId: Long, item: String): F[Unit]
  def removeItemAt(chatId: Long, index: Int): F[Unit]
  def clear(chatId: Long): F[Unit]
