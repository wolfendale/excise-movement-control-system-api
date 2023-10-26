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

package uk.gov.hmrc.excisemovementcontrolsystemapi.controllers.actions

import generated.IE818Type
import org.mockito.ArgumentMatchersSugar.{any, eqTo}
import org.mockito.MockitoSugar.{verify, when}
import org.scalatest.{BeforeAndAfterAll, EitherValues}
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json
import play.api.mvc.Results.BadRequest
import play.api.test.FakeRequest
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import scalaxb.ParserFailure
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.auth.{EnrolmentRequest, ParsedXmlRequestIE818}
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.{EmcsUtils, ErrorResponse}
import uk.gov.hmrc.excisemovementcontrolsystemapi.services.XmlParserIE818
import uk.gov.hmrc.play.bootstrap.tools.Stubs.stubMessagesControllerComponents

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class ParseIE818XmlActionSpec extends PlaySpec with EitherValues with BeforeAndAfterAll {

  implicit val emcsUtils: EmcsUtils = mock[EmcsUtils]

  private val xmlParser = mock[XmlParserIE818]
  private val controller = new ParseIE818XmlActionImpl(xmlParser, stubMessagesControllerComponents())

  private val currentDateTime = LocalDateTime.of(2023, 10, 18, 15, 33, 33)

  private val xmlStr =
    """<?xml version="1.0" encoding="UTF-8"?>
      |<note>
      |  <to>Tove</to>
      |  <from>Jani</from>
      |  <heading>Reminder</heading>
      |  <body>Don't forget me this weekend!</body>
      |</note>""".stripMargin

  override def beforeAll(): Unit = {
    super.beforeAll()
    when(emcsUtils.getCurrentDateTime).thenReturn(currentDateTime)
  }

  "parseXML" should {
    "return 400 if no body supplied" in {
      val request = EnrolmentRequest(FakeRequest().withBody(None), Set.empty, "123")
      val result = await(controller.refine(request))

      result.left.value mustBe BadRequest(Json.toJson(ErrorResponse(currentDateTime, "XML error", "XML is empty")))

    }

    "return a request with the IE815Types object when supplied XML Node Sequence" in {

      val obj = mock[IE818Type]
      when(xmlParser.fromXml(any)).thenReturn(obj)

      val body = scala.xml.XML.loadString(xmlStr)
      val fakeRequest = FakeRequest().withBody(body)
      val request = EnrolmentRequest(fakeRequest, Set.empty, "123")

      val result = await(controller.refine(request))

      verify(xmlParser).fromXml(eqTo(body))
      result mustBe Right(ParsedXmlRequestIE818(request, obj, Set.empty, "123"))
    }

    "return a Bad Request supplied XML Node Sequence that is not an IE818" in {

      when(xmlParser.fromXml(any)).thenThrow(new ParserFailure("Not valid"))

      val body = scala.xml.XML.loadString(xmlStr)
      val fakeRequest = FakeRequest().withBody(body)
      val request = EnrolmentRequest(fakeRequest, Set.empty, "123")

      val result = await(controller.refine(request))

      result.left.value mustBe BadRequest(Json.toJson(ErrorResponse(currentDateTime, "XML formatting error", "Not valid IE818 message: Not valid")))

    }

    "return 400 if body supplied is a string" in {
      val request = EnrolmentRequest(FakeRequest().withBody("<xml>asdasd</xml>"), Set.empty, "123")
      val result = await(controller.refine(request))

      result.left.value mustBe BadRequest(Json.toJson(ErrorResponse(currentDateTime, "XML error", "Value supplied is not XML")))

    }
  }
}