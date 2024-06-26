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

package uk.gov.hmrc.excisemovementcontrolsystemapi.connectors

import com.codahale.metrics.{MetricRegistry, Timer}
import org.mockito.ArgumentMatchersSugar.{any, eqTo}
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.MockitoSugar.{reset, verify, when}
import org.scalatest.{BeforeAndAfterEach, EitherValues}
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.play.PlaySpec
import play.api.http.Status.{INTERNAL_SERVER_ERROR, NOT_FOUND}
import play.api.libs.json.Json
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.excisemovementcontrolsystemapi.config.AppConfig
import uk.gov.hmrc.excisemovementcontrolsystemapi.fixture.EISHeaderTestSupport
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.{MessageReceiptFailResponse, MessageReceiptSuccessResponse}
import uk.gov.hmrc.excisemovementcontrolsystemapi.services.CorrelationIdService
import uk.gov.hmrc.excisemovementcontrolsystemapi.utils.DateTimeService
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class MessageReceiptConnectorSpec
  extends PlaySpec
    with BeforeAndAfterEach
    with EitherValues
    with EISHeaderTestSupport {

  protected implicit val ec: ExecutionContext = ExecutionContext.global
  protected implicit val hc: HeaderCarrier = HeaderCarrier()

  private val timerContext = mock[Timer.Context]
  private val httpClient = mock[HttpClient]
  private val appConfig = mock[AppConfig]
  private val metrics = mock[MetricRegistry](RETURNS_DEEP_STUBS)
  private val correlationIdService = mock[CorrelationIdService]
  private val dateTimeService = mock[DateTimeService]
  private val sut = new MessageReceiptConnector(httpClient, appConfig, correlationIdService, metrics, dateTimeService)

  private val timestamp = Instant.parse("2023-01-02T03:04:05.986456Z")
  private val response = MessageReceiptSuccessResponse(timestamp, "123", 10)

  private val messagesBearerToken = "messagesBearerToken"

  override def beforeEach(): Unit = {
    super.beforeEach()
    reset(httpClient, appConfig, metrics, timerContext)

    when(httpClient.PUTString[Any](any, any, any)(any, any, any))
      .thenReturn(Future.successful(HttpResponse(200, Json.toJson(response).toString())))
    when(dateTimeService.timestamp()).thenReturn(timestamp)
    when(correlationIdService.generateCorrelationId()).thenReturn("12345")
    when(appConfig.messageReceiptUrl(any)).thenReturn("/messageReceipt")
    when(appConfig.messagesBearerToken).thenReturn(messagesBearerToken)
    when(metrics.timer(any).time()) thenReturn timerContext
  }

  "put" should {
    "return a response" in {
      val result = await(sut.put("123"))

      result mustBe response
    }

    "should sent a request with the right parameters" in {
      await(sut.put("123"))


      verify(httpClient).PUTString[Any](
        eqTo("/messageReceipt"),
        eqTo(""),
        eqTo(expectedConsumptionHeaders("2023-01-02T03:04:05.986Z", "12345", messagesBearerToken))
      )(any, any, any)
    }

    "should start a timer" in {
      await(sut.put("123"))

      verify(metrics).timer(eqTo("emcs.messagereceipt.timer"))
      verify(metrics.timer(eqTo("emcs.messagereceipt.timer"))).time()
      verify(timerContext).stop()
    }

    "return an error" when {
      "eis api return an error" in {
        when(httpClient.PUTString[Any](any, any, any)(any, any, any))
          .thenReturn(Future.successful(HttpResponse(NOT_FOUND, "error")))

        val result = await(sut.put("123"))

        result mustBe MessageReceiptFailResponse(NOT_FOUND, timestamp, "error")
      }

      "can't parse Json" in {
        when(httpClient.PUTString[Any](any, any, any)(any, any, any))
          .thenReturn(Future.successful(HttpResponse(200, "error")))

        val result = await(sut.put("123"))

        result mustBe MessageReceiptFailResponse(INTERNAL_SERVER_ERROR, timestamp, "Exception occurred when Acknowledging messages for ern: 123")

      }
    }
  }
}