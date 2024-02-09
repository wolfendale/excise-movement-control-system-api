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

package uk.gov.hmrc.excisemovementcontrolsystemapi.scheduling

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.{Scenario, StubMapping}
import org.mockito.MockitoSugar.when
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.{Application, Configuration}
import uk.gov.hmrc.excisemovementcontrolsystemapi.config.AppConfig
import uk.gov.hmrc.excisemovementcontrolsystemapi.data.{NewMessagesXml, SchedulingTestData}
import uk.gov.hmrc.excisemovementcontrolsystemapi.fixture.StringSupport
import uk.gov.hmrc.excisemovementcontrolsystemapi.fixtures.WireMockServerSpec
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.eis.EISConsumptionResponse
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.notification.Notification
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.notification.NotificationResponse.SuccessPushNotificationResponse
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.{MessageReceiptResponse, MessageTypes}
import uk.gov.hmrc.excisemovementcontrolsystemapi.repository.model.{ExciseNumberWorkItem, Message, Movement}
import uk.gov.hmrc.excisemovementcontrolsystemapi.repository.{ExciseNumberQueueWorkItemRepository, MovementRepository}
import uk.gov.hmrc.excisemovementcontrolsystemapi.utils.{DateTimeService, TestUtils}
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, DefaultPlayMongoRepositorySupport}
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DAYS, Duration, MINUTES}

class PollingNewMessagesWithWorkItemJobItSpec extends PlaySpec
  with DefaultPlayMongoRepositorySupport[WorkItem[ExciseNumberWorkItem]]
  with CleanMongoCollectionSupport
  with WireMockServerSpec
  with NewMessagesXml
  with MockitoSugar
  with ScalaFutures
  with StringSupport
  with Eventually
  with IntegrationPatience
  with BeforeAndAfterEach
  with BeforeAndAfterAll {

  protected implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
  private val showNewMessageUrl = "/emcs/messages/v1/show-new-messages?exciseregistrationnumber="
  private val messageReceiptUrl = "/emcs/messages/v1/message-receipt?exciseregistrationnumber="

  private lazy val timeService = mock[DateTimeService]
  // The DB truncates it to milliseconds so to make exact comparisons in the asserts we need to ditch the nanos
  private val availableBefore = Instant.now.truncatedTo(ChronoUnit.MILLIS)
  when(timeService.timestamp()).thenReturn(availableBefore)

  private val expectedMessage = Seq(
    createMessage(SchedulingTestData.ie801, MessageTypes.IE801.value),
    createMessage(SchedulingTestData.ie818, MessageTypes.IE818.value),
    createMessage(SchedulingTestData.ie802, MessageTypes.IE802.value)
  )


  // This is used by repository and movementRepository to set the databases before
  // the application start. Once the application has started, the app will load a real
  // instance of AppConfig using the application.test.conf
  private lazy val mongoAppConfig = mock[AppConfig]
  when(mongoAppConfig.movementTTL).thenReturn(Duration.create(30, DAYS))
  when(mongoAppConfig.workItemTTL).thenReturn(Duration.create(30, DAYS))
  when(mongoAppConfig.workItemInProgressTimeOut).thenReturn(Duration.create(5, MINUTES))

  protected override lazy val repository = new ExciseNumberQueueWorkItemRepository(
    mongoAppConfig,
    mongoComponent,
    timeService
  )

  override def configureServices: Map[String, Any] = {
    configureEisService ++
      Map(
        "mongodb.uri" -> mongoUri,
        "microservice.services.notification.host" -> wireHost,
        "microservice.services.notification.port" -> wireMock.port()
      )
  }
  protected def appBuilder: GuiceApplicationBuilder = {
    wireMock.start()
    WireMock.configureFor(wireHost, wireMock.port())

    GuiceApplicationBuilder()
      .configure(configureServices)
      .loadConfig(env => Configuration.load(env, Map("config.resource" -> "application.test.conf")))
      .overrides(
        bind[DateTimeService].to(timeService)
      )

  }

  lazy val app: Application = appBuilder.build()

  override def beforeEach(): Unit = {
    wireMock.resetAll()
    prepareDatabase()
    wireMock.resetAll()
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    app
  }

  override def afterAll(): Unit = {
    super.afterAll()
    dropDatabase()
  }

  "Scheduler" should {

    "start Polling show new message" in {

      val movementRepository = new MovementRepository(
        mongoComponent,
        mongoAppConfig,
        timeService
      )

      setUpWireMockStubs()

      insert(createWorkItem("1")).futureValue
      insert(createWorkItem("3")).futureValue
      insert(createWorkItem("4")).futureValue

      movementRepository.saveMovement(Movement("boxId1", "token", "1", None, None, Instant.now, Seq.empty)).futureValue
      movementRepository.saveMovement(Movement("boxId2", "token", "3", None, None, Instant.now, Seq.empty)).futureValue
      movementRepository.saveMovement(Movement("boxId3", "token", "4", None, None, Instant.now, Seq.empty)).futureValue

      // todo: not a very good way to wait for the thread to do is job. Tried eventually but it does not
      // work. Try to find a better way.
      Thread.sleep(6000)

      eventually {wireMock.verify(putRequestedFor(urlEqualTo(s"${showNewMessageUrl}1")))}
      eventually {wireMock.verify(putRequestedFor(urlEqualTo(s"${showNewMessageUrl}3")))}
      eventually {wireMock.verify(putRequestedFor(urlEqualTo(s"${showNewMessageUrl}4")))}
      eventually {wireMock.verify(putRequestedFor(urlEqualTo(s"${messageReceiptUrl}1")))}
      eventually {wireMock.verify(putRequestedFor(urlEqualTo(s"${messageReceiptUrl}3")))}
      eventually {wireMock.verify(putRequestedFor(urlEqualTo(s"${messageReceiptUrl}4")))}

      val expectedMovementForErn_1 =  Movement("boxId1", "token", "1", None, Some("tokentokentokentokent"), Instant.now, expectedMessage)
      val expectedMovementForErn_3 = Movement("boxId2", "token", "3", None, Some("tokentokentokentokent"), Instant.now, expectedMessage.take(1))
      val expectedMovementForErn_4 = Movement("boxId3", "token", "4", None, Some("tokentokentokentokent"), Instant.now, Seq(createMessage(SchedulingTestData.ie704, MessageTypes.IE704.value)))
      val movements = movementRepository.collection.find().toFuture().futureValue

      movements.size mustBe 3
      assertResults(movements.find(_.consignorId.equals("1")).get, expectedMovementForErn_1)
      assertResults(movements.find(_.consignorId.equals("3")).get, expectedMovementForErn_3)
      assertResults(movements.find(_.consignorId.equals("4")).get, expectedMovementForErn_4)

      withClue("Should push a notification") {
        assertPushNotificationApiForErn1(expectedMovementForErn_1, movements(0)._id)
        assertPushNotificationApiForErn3(expectedMovementForErn_3, movements(1)._id)
        assertPushNotificationApiForErn4(expectedMovementForErn_4, movements(2)._id)
      }
    }

    "do not call EIS if nothing is in the work queue " in {

      val movementRepository = new MovementRepository(
        mongoComponent,
        mongoAppConfig,
        timeService
      )

      setUpWireMockStubs()

      movementRepository.saveMovement(Movement("boxId1", "token", "1", None, None, Instant.now, Seq.empty)).futureValue
      movementRepository.saveMovement(Movement("boxId2", "token", "3", None, None, Instant.now, Seq.empty)).futureValue
      movementRepository.saveMovement(Movement("boxId3", "token", "4", None, None, Instant.now, Seq.empty)).futureValue

      eventually {
        wireMock.verify(0, putRequestedFor(urlEqualTo(s"${showNewMessageUrl}1")))
      }
      eventually {
        wireMock.verify(0, putRequestedFor(urlEqualTo(s"${showNewMessageUrl}3")))
      }
      eventually {
        wireMock.verify(0, putRequestedFor(urlEqualTo(s"${showNewMessageUrl}4")))
      }
      eventually {
        wireMock.verify(0, putRequestedFor(urlEqualTo(s"${messageReceiptUrl}1")))
      }
      eventually {
        wireMock.verify(0, putRequestedFor(urlEqualTo(s"${messageReceiptUrl}3")))
      }
      eventually {
        wireMock.verify(0, putRequestedFor(urlEqualTo(s"${messageReceiptUrl}4")))
      }

      val movements = movementRepository.collection.find().toFuture().futureValue

      movements.size mustBe 3
      assertResults(movements.find(_.consignorId.equals("1")).get, Movement("boxId1", "token", "1", None, None, Instant.now, Seq.empty))
      assertResults(movements.find(_.consignorId.equals("3")).get, Movement("boxId2", "token", "3", None, None, Instant.now, Seq.empty))
      assertResults(movements.find(_.consignorId.equals("4")).get, Movement("boxId3", "token", "4", None, None, Instant.now, Seq.empty))
    }

    "if fails three times work item marked as ToDo with a slow interval" in {

      val movementRepository = new MovementRepository(
        mongoComponent,
        mongoAppConfig,
        timeService
      )

      stubForThrowingError()

      val createdWorkItem = createWorkItem("1")
      insert(createdWorkItem).futureValue

      movementRepository.saveMovement(Movement("boxId", "token", "1", None, None, Instant.now, Seq.empty)).futureValue

      // todo: not a very good way to wait for the thread to do is job. Tried eventually but it does not
      // work. Try to find a better way.
      Thread.sleep(6000)

      val movements = movementRepository.collection.find().toFuture().futureValue

      movements.size mustBe 1
      assertResults(movements.find(_.consignorId.equals("1")).get, Movement("boxId", "token", "1", None, None, Instant.now, Seq.empty))

      val workItems = findAll().futureValue

      val workItem = workItems.find(_.item.exciseNumber.equals("1")).get
      workItem.status mustBe ProcessingStatus.ToDo
      workItem.availableAt mustBe createdWorkItem.availableAt.plusSeconds(2 * 60)

    }

    "if fails mark work item as failed so can be retried" in {

      val movementRepository = new MovementRepository(
        mongoComponent,
        mongoAppConfig,
        timeService
      )

      stubForThrowingError()

      insert(createWorkItem("1")).futureValue

      movementRepository.saveMovement(Movement("boxId", "token", "1", None, None, Instant.now, Seq.empty)).futureValue

      // todo: not a very good way to wait for the thread to do is job. Tried eventually but it does not
      // work. Try to find a better way.
      Thread.sleep(100)

      val movements = movementRepository.collection.find().toFuture().futureValue

      movements.size mustBe 1
      assertResults(movements.find(_.consignorId.equals("1")).get, Movement("boxId", "token", "1", None, None, Instant.now, Seq.empty))

      val workItems = findAll().futureValue

      workItems.find(_.item.exciseNumber.equals("1")).get.status mustBe ProcessingStatus.ToDo
    }

  }

  private def assertPushNotificationApiForErn1(expectedMovement: Movement, movementId: String): Unit = {
    val loggerRequests = wireMock.findAll(postRequestedFor(urlEqualTo(s"/box/boxId1/notifications")))
    loggerRequests.size mustBe 3
    loggerRequests.get(0).getBodyAsString mustBe createJsonNotificationBody("1", movementId, expectedMovement, "messageId-1").toString()
    loggerRequests.get(1).getBodyAsString mustBe createJsonNotificationBody("1", movementId, expectedMovement, "messageId-2").toString()
    loggerRequests.get(2).getBodyAsString mustBe createJsonNotificationBody("1", movementId, expectedMovement, "messageId-3").toString()
  }

  private def assertPushNotificationApiForErn3(expectedMovement: Movement, movementId: String): Unit = {
    val loggerRequests = wireMock.findAll(postRequestedFor(urlEqualTo(s"/box/boxId2/notifications")))
    loggerRequests.size mustBe 1
    loggerRequests.get(0).getBodyAsString mustBe createJsonNotificationBody("3", movementId, expectedMovement, "messageId-1").toString()
  }

  private def assertPushNotificationApiForErn4(expectedMovement: Movement, movementId: String): Unit = {
    val loggerRequests = wireMock.findAll(postRequestedFor(urlEqualTo(s"/box/boxId3/notifications")))
    loggerRequests.size mustBe 1
    loggerRequests.get(0).getBodyAsString mustBe createJsonNotificationBody("4", movementId, expectedMovement, "messageId-4").toString()
  }

  private def assertResults(actual: Movement, expected: Movement) = {
    actual.localReferenceNumber mustBe expected.localReferenceNumber
    actual.consignorId mustBe expected.consignorId
    actual.administrativeReferenceCode mustBe expected.administrativeReferenceCode
    actual.consigneeId mustBe expected.consigneeId
    actual.boxId mustBe expected.boxId
    decodeAndCleanUpMessage(actual.messages) mustBe decodeAndCleanUpMessage(expected.messages)
  }

  private def decodeAndCleanUpMessage(messages: Seq[Message]): Seq[String] = {
    messages
      .map(o => Base64.getDecoder.decode(o.encodedMessage).map(_.toChar).mkString)
      .map(clean)
  }

  private def setUpWireMockStubs(): Unit = {
    stubShowNewMessageRequestForConsignorId1()
    stubShowNewMessageRequestForConsignorId3()
    stubShowNewMessageRequestForConsignorId4()
    stubMessageReceiptRequest("1")
    stubMessageReceiptRequest("3")
    stubMessageReceiptRequest("4")
    stubPushNotification("boxId1")
    stubPushNotification("boxId2")
    stubPushNotification("boxId3")
  }

  private def stubShowNewMessageRequestForConsignorId3(): Unit = {
    wireMock.stubFor(
      put(s"${showNewMessageUrl}3")
        .inScenario(s"requesting-new-message-for-ern-3")
        .whenScenarioStateIs(Scenario.STARTED)
        .willReturn(ok().withBody(Json.toJson(
          EISConsumptionResponse(
            Instant.parse("2023-01-02T03:04:05Z"),
            "3",
            Base64.getEncoder.encodeToString(newMessageWithIE801().toString().getBytes(StandardCharsets.UTF_8)),
          )).toString()
        ))
        .willSetStateTo(s"show-empty-message")
    )

    stubForEmptyMessageData("3")
  }

  private def stubShowNewMessageRequestForConsignorId4(): Unit = {
    wireMock.stubFor(
      put(s"${showNewMessageUrl}4")
        .inScenario(s"requesting-new-message-for-ern-4")
        .whenScenarioStateIs(Scenario.STARTED)
        .willReturn(ok().withBody(Json.toJson(
          EISConsumptionResponse(
            Instant.parse("2023-01-02T03:04:05Z"),
            "4",
            Base64.getEncoder.encodeToString(newMessageXmlWithIE704.toString().getBytes(StandardCharsets.UTF_8)),
          )).toString()
        ))
        .willSetStateTo(s"show-empty-message")
    )

    stubForEmptyMessageData("4")
  }

  private def stubShowNewMessageRequestForConsignorId1(): Unit = {
    wireMock.stubFor(
      put(s"${showNewMessageUrl}1")
        .inScenario("requesting-new-message-for-ern-1")
        .whenScenarioStateIs(Scenario.STARTED)
        .willReturn(ok().withBody(Json.toJson(
          EISConsumptionResponse(
            Instant.parse("2023-01-02T03:04:05Z"),
            "1",
            //Set the new message count so it will poll again and get the item below
            Base64.getEncoder.encodeToString(newMessageWithIE801(11).toString().getBytes(StandardCharsets.UTF_8)),
          )).toString()
        ))
        .willSetStateTo("show-second-response")
    )

    wireMock.stubFor(
      put(s"${showNewMessageUrl}1")
        .inScenario("requesting-new-message-for-ern-1")
        .whenScenarioStateIs("show-second-response")
        .willReturn(ok().withBody(Json.toJson(
          EISConsumptionResponse(
            Instant.parse("2024-01-02T03:04:05Z"),
            "1",
            Base64.getEncoder.encodeToString(newMessageWith818And802.toString().getBytes(StandardCharsets.UTF_8)),
          )).toString()
        ))
        .willSetStateTo("show-empty-message")
    )

    stubForEmptyMessageData("1")
  }

  private def stubForThrowingError(): StubMapping = {
    wireMock.stubFor(
      put(s"${showNewMessageUrl}1")
        .willReturn(serverError().withBody("Internal server error"))
    )
  }

  private def stubForEmptyMessageData(exciseNumber: String): Unit = {
    wireMock.stubFor(
      put(s"$showNewMessageUrl$exciseNumber")
        .inScenario(s"requesting-new-message-for-ern-$exciseNumber")
        .whenScenarioStateIs("show-empty-message")
        .willReturn(ok().withBody(Json.toJson(
          EISConsumptionResponse(
            Instant.parse("2024-01-02T03:04:05Z"),
            exciseNumber,
            Base64.getEncoder.encodeToString(emptyNewMessageDataXml.toString().getBytes(StandardCharsets.UTF_8)),
          )).toString()
        ))
    )
  }

  private def stubMessageReceiptRequest(exciseNumber: String): StubMapping = {
    wireMock.stubFor(
      put(s"$messageReceiptUrl$exciseNumber")
        .willReturn(ok().withBody(Json.toJson(
          MessageReceiptResponse(
            Instant.parse("2023-01-02T03:04:05Z"),
            exciseNumber,
            3
          )).toString()
        ))
    )
  }

  private def stubPushNotification(boxId: String) = {
    wireMock.stubFor(
      post(s"/box/$boxId/notifications")
        .willReturn(
          ok().withBody(Json.toJson(SuccessPushNotificationResponse("123")).toString())
        )
    )
  }

  private def createWorkItem(ern: String): WorkItem[ExciseNumberWorkItem] = {

    val sixtySecsAgo = availableBefore.minusSeconds(60)

    TestUtils.createWorkItem(
      ern = ern,
      receivedAt = sixtySecsAgo,
      updatedAt = sixtySecsAgo,
      availableAt = sixtySecsAgo,
      fastPollRetries = 3
    )

  }

  private def createMessage(xml: String, messageType: String): Message = {
    Message(
      Base64.getEncoder.encodeToString(xml.getBytes(StandardCharsets.UTF_8)),
      messageType,
      "messageId",
      timeService.timestamp()
    )
  }

  private def createJsonNotificationBody(
    ern: String,
    movementId: String,
    movement: Movement,
    messageId: String
  ): JsValue = {
    Json.toJson(Notification(
      movementId,
      s"/movements/$movementId/message/$messageId",
      messageId,
      movement.consignorId,
      movement.consigneeId,
      movement.administrativeReferenceCode.get,
      ern
    ))
  }
}

