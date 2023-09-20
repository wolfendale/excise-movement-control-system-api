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

package uk.gov.hmrc.excisemovementcontrolsystemapi.connector



import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchersSugar.eqTo
import org.mockito.MockitoSugar.{reset, verify, when}
import org.scalatest.{BeforeAndAfterEach, EitherValues}
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.play.PlaySpec
import play.api.http.Status.BAD_REQUEST
import play.api.http.{ContentTypes, HeaderNames}
import play.api.libs.json.Json
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.excisemovementcontrolsystemapi.config.AppConfig
import uk.gov.hmrc.excisemovementcontrolsystemapi.connectors.MovementMessageConnector
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.EisUtils
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.eis.{EISErrorResponse, EISRequest, EISResponse}
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

class MovementMessageConnectorSpec extends PlaySpec with BeforeAndAfterEach with EitherValues{

  protected implicit val hc: HeaderCarrier = HeaderCarrier()
  protected implicit val ec: ExecutionContext = ExecutionContext.global

  private val mockHttpClient = mock[HttpClient]
  private val eisUtils = mock[EisUtils]
  private val appConfig = mock[AppConfig]
  private val connector = new MovementMessageConnector(mockHttpClient, eisUtils, appConfig)
  private val emcsCorrelationId = "1234566"
  private val message = "<IE815></IE815>"
  private val messageType = "IE815"
  private val jsonResponse = Json.obj(
    "status" -> "ok",
    "message" -> "Success",
    "emcsCorrelationId" -> emcsCorrelationId
  )

  override def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockHttpClient, appConfig)

    when(eisUtils.getCurrentDateTimeString).thenReturn("2023-09-17T09:32:50.345Z")
    when(eisUtils.generateCorrelationId).thenReturn(emcsCorrelationId)
  }

  "post" should {
    "return successful EISResponse" in {
      when(mockHttpClient.POST[Any, Any](any, any, any)(any, any, any, any))
        .thenReturn(Future.successful(HttpResponse(200, jsonResponse.toString())))

      val result = await(connector.post(message, messageType))

      result mustBe Right(EISResponse("ok", "Success", emcsCorrelationId))
    }

    "use the right request parameters in http client" in {
      when(mockHttpClient.POST[Any, Any](any, any, any)(any, any, any, any))
        .thenReturn(Future.successful(HttpResponse(200, jsonResponse.toString())))
      when(appConfig.emcsReceiverMessageUrl).thenReturn("/eis/path")

      val eisRequest = EISRequest(emcsCorrelationId, "2023-09-17T09:32:50.345Z", messageType, "APIP", "user1", message)

      await(connector.post(message, messageType))

      verify(appConfig).emcsReceiverMessageUrl
      verify(mockHttpClient).POST(
        eqTo("/eis/path"),
        eqTo(eisRequest),
        eqTo(expectedHeader)
      )(any, any, any, any)
    }

    "return an error" in {
      val errorResponse = Json.obj(
        "dateTime" -> "2021-12-17T09:30:47Z",
        "status" -> BAD_REQUEST,
        "message" -> "any error",
        "debugMessage" -> "error message",
        "emcsCorrelationId" -> emcsCorrelationId
      )
      when(mockHttpClient.POST[Any, Any](any, any, any)(any, any, any, any))
        .thenReturn(Future.successful(HttpResponse(BAD_REQUEST, errorResponse.toString())))
      when(appConfig.emcsReceiverMessageUrl).thenReturn("/eis/path")

      val result = await(connector.post(message, messageType))

      result.left.value mustBe EISErrorResponse(
        LocalDateTime.of(2021, 12, 17, 9,30,47),
        BAD_REQUEST,
        "any error",
        "error message",
        emcsCorrelationId
      )
    }
  }

  def expectedHeader =
    Seq(HeaderNames.ACCEPT -> ContentTypes.JSON,
      HeaderNames.CONTENT_TYPE -> ContentTypes.JSON,
      "dateTime" -> "2023-09-17T09:32:50.345Z",
      "x-correlation-id" -> "1234566",
      "x-forwarded-host" -> "",
      "source" -> "APIP"
    )
}