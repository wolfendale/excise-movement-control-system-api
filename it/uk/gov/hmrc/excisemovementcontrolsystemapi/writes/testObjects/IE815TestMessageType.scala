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

object IE815TestMessageType extends TestMessageType {

  override def json1: JsValue = Json.parse("{\"Header\":{\"MessageSender\":\"NDEA.GB\",\"MessageRecipient\":\"NDEA.GB\",\"DateOfPreparation\":\"2023-09-09\",\"TimeOfPreparation\":\"03:22:47\",\"MessageIdentifier\":\"6de1b822562c43fb9220d236e487c920\",\"CorrelationIdentifier\":\"PORTAL6de1b822562c43fb9220d236e487c920\"},\"Body\":{\"SubmittedDraftOfEADESAD\":{\"AttributesValue\":{\"SubmissionMessageType\":\"1\"},\"ConsigneeTrader\":{\"Traderid\":\"GBWKQOZ8OVLYR\",\"TraderName\":\"WFlgUjfC\",\"StreetName\":\"xoL0NsNyDi\",\"StreetNumber\":\"67\",\"Postcode\":\"A1 1AA\",\"City\":\"l8WSaHS9\",\"attributes\":{\"@language\":\"en\"}},\"ConsignorTrader\":{\"TraderExciseNumber\":\"GBWK002281023\",\"TraderName\":\"DIAGEO PLC\",\"StreetName\":\"msfvZUL1Oe\",\"StreetNumber\":\"25\",\"Postcode\":\"A1 1AA\",\"City\":\"QDHwPa61\",\"attributes\":{\"@language\":\"en\"}},\"PlaceOfDispatchTrader\":{\"ReferenceOfTaxWarehouse\":\"GB00DO459DMNX\",\"TraderName\":\"2z0waekA\",\"StreetName\":\"MhO1XtDIVr\",\"StreetNumber\":\"25\",\"Postcode\":\"A1 1AA\",\"City\":\"zPCc6skm\",\"attributes\":{\"@language\":\"en\"}},\"DeliveryPlaceTrader\":{\"Traderid\":\"GB00AIP67RAO3\",\"TraderName\":\"BJpWdv2N\",\"StreetName\":\"C24vvUqCw6\",\"StreetNumber\":\"43\",\"Postcode\":\"A1 1AA\",\"City\":\"A9ZlElxP\",\"attributes\":{\"@language\":\"en\"}},\"CompetentAuthorityDispatchOffice\":{\"ReferenceNumber\":\"GB004098\"},\"FirstTransporterTrader\":{\"VatNumber\":\"123798354\",\"TraderName\":\"Mr Delivery place trader 4\",\"StreetName\":\"Delplace Avenue\",\"StreetNumber\":\"05\",\"Postcode\":\"FR5 4RN\",\"City\":\"Delville\",\"attributes\":{\"@language\":\"en\"}},\"DocumentCertificate\":[{\"DocumentType\":\"9\",\"DocumentReference\":\"DPdQsYktZEJEESpc7b32Ig0U6B34XmHmfZU\"}],\"HeaderEadEsad\":{\"DestinationTypeCode\":\"1\",\"JourneyTime\":\"D07\",\"TransportArrangement\":\"1\"},\"TransportMode\":{\"TransportModeCode\":\"3\"},\"MovementGuarantee\":{\"GuarantorTypeCode\":\"1\",\"GuarantorTrader\":[]},\"BodyEadEsad\":[{\"BodyRecordUniqueReference\":\"1\",\"ExciseProductCode\":\"B000\",\"CnCode\":\"22030001\",\"Quantity\":2000,\"GrossMass\":20000,\"NetMass\":19999,\"AlcoholicStrengthByVolumeInPercentage\":0.5,\"FiscalMarkUsedFlag\":\"0\",\"PackageValue\":[{\"KindOfPackages\":\"BA\",\"NumberOfPackages\":\"2\"}]}],\"EadEsadDraft\":{\"LocalReferenceNumber\":\"LRNQA20230909022221\",\"InvoiceNumber\":\"Test123\",\"InvoiceDate\":\"2023-09-09\",\"OriginTypeCode\":\"1\",\"DateOfDispatch\":\"2023-09-09\",\"TimeOfDispatch\":\"12:00:00\",\"ImportSad\":[]},\"TransportDetails\":[{\"TransportUnitCode\":\"1\",\"IdentityOfTransportUnits\":\"100\"}]}}}")
}