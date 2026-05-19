package tyche.store

import tyche.domain.MessageTemplate

trait TemplateStore[F[_]]:
  def getTemplate(chatId: Long): F[MessageTemplate]
  def setTemplate(chatId: Long, template: MessageTemplate): F[Unit]
  def clear(chatId: Long): F[Unit]
