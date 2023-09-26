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

package uk.gov.hmrc.excisemovementcontrolsystemapi.repository

import dispatch.Future
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import play.api.Logging
import play.api.libs.json.Json
import uk.gov.hmrc.excisemovementcontrolsystemapi.config.AppConfig
import uk.gov.hmrc.excisemovementcontrolsystemapi.repository.model.MovementMessage
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class MovementMessageRepository @Inject()(mongo: MongoComponent, appConfig: AppConfig)(implicit ec: ExecutionContext) extends
  PlayMongoRepository[MovementMessage](
    collectionName = "movements",
    mongoComponent = mongo,
    domainFormat = Json.format[MovementMessage],
    indexes = Seq(
      IndexModel(
        ascending(List("localReferenceNumber", "consignorId"): _*),
        IndexOptions()
          .name("lrn_consignor_index")
          .background(true)
          .unique(true)
      ),
      IndexModel(
        ascending(Seq("createdOn"): _*),
        IndexOptions()
          .name("create_on_ttl_idx")
          .expireAfter(appConfig.movementMessagesMongoExpirySeconds, TimeUnit.SECONDS)
          .background(true)
          .unique(false)
      )
    )
  ) with Logging {

  def saveMovementMessage(movementMessage: MovementMessage): Future[Boolean] = {
    collection.insertOne(movementMessage)
      .toFuture()
      .map(_ => true)
  }
}
