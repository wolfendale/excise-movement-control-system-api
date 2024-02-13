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

package uk.gov.hmrc.excisemovementcontrolsystemapi.writes.testObjects
import play.api.libs.json.{JsValue, Json}

import scala.xml.NodeSeq

object IE837TestMessageType extends TestMessageType {

  override def json1: JsValue = Json.parse("{\"Header\":{\"MessageSender\":\"NDEA.GB\",\"MessageRecipient\":\"NDEA.EU\",\"DateOfPreparation\":\"2023-08-10\",\"TimeOfPreparation\":\"09:56:40.695540\",\"MessageIdentifier\":\"GB100000000302681\",\"CorrelationIdentifier\":\"a2f65a81-c297-4117-bea5-556129529463\"},\"Body\":{\"ExplanationOnDelayForDelivery\":{\"AttributesValue\":{\"SubmitterIdentification\":\"GBWK240176600\",\"SubmitterType\":\"1\",\"ExplanationCode\":\"6\",\"ComplementaryInformation\":{\"value\":\"Accident on M5\",\"attributes\":{\"@language\":\"en\"}},\"MessageRole\":\"1\",\"DateAndTimeOfValidationOfExplanationOnDelay\":\"2023-08-10T10:56:42\"},\"ExciseMovement\":{\"AdministrativeReferenceCode\":\"16GB00000000000192223\",\"SequenceNumber\":\"2\"}}}}")
}