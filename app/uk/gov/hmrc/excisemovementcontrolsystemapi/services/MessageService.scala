/*
 * Copyright 2024 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.excisemovementcontrolsystemapi.services

import cats.syntax.all._
import org.apache.pekko.Done
import uk.gov.hmrc.excisemovementcontrolsystemapi.connectors.MessageConnector
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.messages.IEMessage
import uk.gov.hmrc.excisemovementcontrolsystemapi.repository.model.{Message, Movement}
import uk.gov.hmrc.excisemovementcontrolsystemapi.repository.{ErnRetrievalRepository, MovementRepository}
import uk.gov.hmrc.excisemovementcontrolsystemapi.utils.{DateTimeService, EmcsUtils}
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MessageService @Inject
(
  movementRepository: MovementRepository,
  ernRetrievalRepository: ErnRetrievalRepository,
  messageConnector: MessageConnector,
  dateTimeService: DateTimeService,
  emcsUtils: EmcsUtils
)(implicit executionContext: ExecutionContext) {

  def updateMessages(ern: String)(implicit hc: HeaderCarrier): Future[Done] = {
    for {
      _ <- ernRetrievalRepository.getLastRetrieved(ern)
      messages <- messageConnector.getNewMessages(ern)
      _ <- updateMovements(ern, messages)
      _ <- ernRetrievalRepository.save(ern)
    } yield Done
  }

  private def updateMovements(ern: String, messages: Seq[IEMessage]): Future[Done] = {
    if (messages.nonEmpty) {
      movementRepository.getAllBy(ern).map { movements =>
        messages.groupBy { message =>
          (movements.find(movement => message.lrnEquals(movement.localReferenceNumber)),
            movements.find(movement => movement.administrativeReferenceCode.exists(arc => message.administrativeReferenceCode.flatten.contains(arc))))
        }.toSeq.traverse {
          case ((Some(maybeLrn), _), messages) =>
            updateMovement(maybeLrn, messages)
          case ((_, Some(maybeArc)), messages) =>
            updateMovement(maybeArc, messages)
        }
      }
    }.as(Done) else {
      Future.successful(Done)
    }
  }

  private def updateMovement(movement: Movement, messages: Seq[IEMessage]) = {
    val updatedMovement = movement.copy(messages = messages.map(convertMessage))
    movementRepository.updateMovement(updatedMovement)
  }

  private def convertMessage(input: IEMessage): Message = {
    Message(
      encodedMessage = emcsUtils.encode(input.toXml.toString),
      messageType = input.messageType,
      messageId = input.messageIdentifier,
      createdOn = dateTimeService.timestamp()
    )
  }
}
