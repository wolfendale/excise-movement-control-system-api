/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.excisemovementcontrolsystemapi.controllers

import cats.data.EitherT
import play.api.libs.json.Json
import play.api.mvc.{Action, ControllerComponents, Result}
import uk.gov.hmrc.excisemovementcontrolsystemapi.controllers.actions.{AuthAction, ParseXmlAction}
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.auth.ParsedXmlRequest
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.eis.EISSubmissionResponse
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.messages.{IE815Message, IEMessage}
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.notification.Constants
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.validation._
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.{ErrorResponse, ExciseMovementResponse}
import uk.gov.hmrc.excisemovementcontrolsystemapi.repository.model.Movement
import uk.gov.hmrc.excisemovementcontrolsystemapi.services.{MovementService, PushNotificationService, SubmissionMessageService, WorkItemService}
import uk.gov.hmrc.excisemovementcontrolsystemapi.utils.DateTimeService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

@Singleton
class DraftExciseMovementController @Inject()(
                                               authAction: AuthAction,
                                               xmlParser: ParseXmlAction,
                                               movementMessageService: MovementService,
                                               workItemService: WorkItemService,
                                               submissionMessageService: SubmissionMessageService,
                                               notificationService: PushNotificationService,
                                               messageValidator: MessageValidation,
                                               dateTimeService: DateTimeService,
                                               cc: ControllerComponents)(implicit ec: ExecutionContext)
  extends BackendController(cc) {

  def submit: Action[NodeSeq] =


    (authAction andThen xmlParser).async(parse.xml) {
      implicit request: ParsedXmlRequest[NodeSeq] =>

        val result = for {
          ie815Message <- getIe815Message(request.ieMessage)
          authorisedErn <- validateMessage(ie815Message, request.erns)
          boxId <- getBoxId(request)
          _ <- submitMessage(request, authorisedErn)
          movement <- saveMovement(ie815Message, boxId)
        } yield {
          Accepted(Json.toJson(ExciseMovementResponse(
            "Accepted",
            boxId,
            movement._id,
            movement.localReferenceNumber,
            movement.consignorId,
            movement.consigneeId)
          ))

        }

        result.merge
    }

  private def createMovementFomMessage(message: IE815Message, boxId: String): Movement = {
    Movement(
      boxId,
      message.localReferenceNumber,
      message.consignorId,
      message.consigneeId,
      None
    )
  }

  private def validateMessage(
                               message: IE815Message,
                               authErns: Set[String]
                             ): EitherT[Future, Result, String] = {

    EitherT.fromEither(messageValidator.validateDraftMovement(authErns, message).left.map {
      x => messageValidator.convertErrorToResponse(x, dateTimeService.timestamp())
    })
  }

  private def getBoxId(request: ParsedXmlRequest[_])
                      (implicit hc: HeaderCarrier): EitherT[Future, Result, String] = {

    request.headers.get(Constants.XClientIdHeader) match {
      case Some(clientId) =>
        EitherT(notificationService.getBoxId(clientId)).map(s => s.boxId)

      case _ => EitherT.fromEither(Left(BadRequest(Json.toJson(
        ErrorResponse(
          Instant.now,
          s"ClientId error",
          s"Request header is missing ${Constants.XClientIdHeader}"
        )
        ))))

    }
  }

  private def submitMessage(request: ParsedXmlRequest[NodeSeq], authorisedErn: String)
                           (implicit hc: HeaderCarrier): EitherT[Future, Result, EISSubmissionResponse] = {

    EitherT(submissionMessageService.submit(request, authorisedErn))
  }

  private def saveMovement(ie815Message: IE815Message, boxId: String): EitherT[Future, Result, Movement] = {

    val newMovement: Movement = createMovementFomMessage(ie815Message, boxId)
    workItemService.addWorkItemForErn(newMovement.consignorId, fastMode = true)

    EitherT(movementMessageService.saveNewMovement(newMovement))
  }

  private def getIe815Message(message: IEMessage): EitherT[Future, Result, IE815Message] = {
    EitherT.fromEither(message match {
      case x: IE815Message => Right(x)
      case _ => Left(BadRequest(Json.toJson(
        ErrorResponse(
          dateTimeService.timestamp(),
          "Invalid message type",
          s"Message type ${message.messageType} cannot be sent to the draft excise movement endpoint"
        )
      )))
    })
  }

}
