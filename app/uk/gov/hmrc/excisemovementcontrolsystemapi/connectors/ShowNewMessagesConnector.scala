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

import com.codahale.metrics.MetricRegistry
import play.api.Logging
import play.api.mvc.Result
import play.api.mvc.Results.InternalServerError
import uk.gov.hmrc.excisemovementcontrolsystemapi.config.AppConfig
import uk.gov.hmrc.excisemovementcontrolsystemapi.connectors.util.ResponseHandler
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.MessageTypes
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.eis.{EISConsumptionHeaders, EISConsumptionResponse, EISErrorMessage}
import uk.gov.hmrc.excisemovementcontrolsystemapi.services.CorrelationIdService
import uk.gov.hmrc.excisemovementcontrolsystemapi.utils.DateTimeService
import uk.gov.hmrc.excisemovementcontrolsystemapi.utils.DateTimeService.DateTimeFormat
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

@deprecated
class ShowNewMessagesConnector @Inject()(
                                          httpClient: HttpClient,
                                          appConfig: AppConfig,
                                          correlationIdService: CorrelationIdService,
                                          metrics: MetricRegistry,
                                          dateTimeService: DateTimeService
                                        )(implicit ec: ExecutionContext) extends EISConsumptionHeaders with ResponseHandler with Logging {

  def get(ern: String)(implicit hc: HeaderCarrier): Future[Either[Result, EISConsumptionResponse]] = {

    val timer = metrics.timer("emcs.shownewmessage.timer").time()
    val correlationId = correlationIdService.generateCorrelationId()
    val dateTime = dateTimeService.timestamp().asStringInMilliseconds

    httpClient.PUTString[HttpResponse](
        appConfig.showNewMessageUrl(ern),
        "",
        build(correlationId, dateTime, appConfig.messagesBearerToken)
      ).map { response =>

        extractIfSuccessful[EISConsumptionResponse](response) match {
          case Right(eisResponse) => Right(eisResponse)
          case Left(_) =>
            logger.warn(s"[ShowNewMessageConnector] - ${EISErrorMessage(dateTime, ern, response.body, correlationId, MessageTypes.IE_NEW_MESSAGES.value)}")
            Left(InternalServerError(response.body))
        }
      }
      .andThen { case _ => timer.stop() }
      .recover {
        case ex: Throwable =>
          logger.warn(s"[ShowNewMessageConnector] - ${EISErrorMessage(dateTime, ern, ex.getMessage, correlationId, MessageTypes.IE_NEW_MESSAGES.value)}")
          Left(InternalServerError(ex.getMessage))
      }
  }
}

