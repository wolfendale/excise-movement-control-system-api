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
import org.mockito.ArgumentMatchersSugar.{eqTo, any => mockitoAny}
import org.mockito.MockitoSugar.when
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.test.Helpers.running
import uk.gov.hmrc.excisemovementcontrolsystemapi.config.AppConfig
import uk.gov.hmrc.excisemovementcontrolsystemapi.data.{NewMessagesXml, SchedulingTestData}
import uk.gov.hmrc.excisemovementcontrolsystemapi.fixture.StringSupport
import uk.gov.hmrc.excisemovementcontrolsystemapi.fixtures.WireMockServerSpec
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.eis.EISConsumptionResponse
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.notification.Notification
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.notification.NotificationResponse.SuccessPushNotificationResponse
import uk.gov.hmrc.excisemovementcontrolsystemapi.models.{MessageReceiptSuccessResponse, MessageTypes}
import uk.gov.hmrc.excisemovementcontrolsystemapi.repository.model.{ExciseNumberWorkItem, Message, Movement}
import uk.gov.hmrc.excisemovementcontrolsystemapi.repository.{ExciseNumberQueueWorkItemRepository, MovementRepository}
import uk.gov.hmrc.excisemovementcontrolsystemapi.utils.{DateTimeService, TestUtils}
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, DefaultPlayMongoRepositorySupport}
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import scala.concurrent.duration.{DAYS, Duration, MINUTES}
import scala.concurrent.{ExecutionContext, Future}

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
  private val timestamp = Instant.now
  when(timeService.timestamp()).thenReturn(timestamp)

  private val expectedMessage = Seq(
    createMessage(SchedulingTestData.ie801, MessageTypes.IE801.value),
    createMessage(SchedulingTestData.ie818, MessageTypes.IE818.value),
    createMessage(SchedulingTestData.ie802, MessageTypes.IE802.value)
  )

  private val cachedMovement1 = Movement(Some("boxId1"), "token", "1", None, None, Instant.now, Seq.empty)
  private val cachedMovement2 = Movement(Some("boxId2"), "token", "3", None, None, Instant.now, Seq.empty)
  private val cachedMovement3 = Movement(Some("boxId3"), "token", "4", None, None, Instant.now, Seq.empty)

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
        "microservice.services.push-pull-notifications.host" -> wireHost,
        "microservice.services.push-pull-notifications.port" -> wireMock.port(),
        "auditing.enabled" -> false
      )
  }

  protected def appBuilder(movementRepository: MovementRepository): GuiceApplicationBuilder = {
    {
      wireMock.start()
      WireMock.configureFor(wireHost, wireMock.port())

      GuiceApplicationBuilder()
        .configure(configureServices)
        .loadConfig(env => Configuration.load(env, Map("config.resource" -> "application.test.conf")))
        .overrides(
          bind[MovementRepository].to(movementRepository),
          bind[DateTimeService].to(timeService),
          bind[ExciseNumberQueueWorkItemRepository].to(repository)
        )
    }

  }


  override def beforeEach(): Unit = {
    wireMock.resetAll()
    prepareDatabase()
  }


  override def beforeAll(): Unit = {
    super.beforeAll()
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

      movementRepository.saveMovement(cachedMovement1).futureValue
      movementRepository.saveMovement(cachedMovement2).futureValue
      movementRepository.saveMovement(cachedMovement3).futureValue

      val app = appBuilder(movementRepository).build()
      running(app) {

        eventually {
          wireMock.verify(putRequestedFor(urlEqualTo(s"${showNewMessageUrl}1")))
        }
        eventually {
          wireMock.verify(putRequestedFor(urlEqualTo(s"${showNewMessageUrl}3")))
        }
        eventually {
          wireMock.verify(putRequestedFor(urlEqualTo(s"${showNewMessageUrl}4")))
        }
        eventually {
          wireMock.verify(2, putRequestedFor(urlEqualTo(s"${messageReceiptUrl}1")))
        }
        eventually {
          wireMock.verify(1, putRequestedFor(urlEqualTo(s"${messageReceiptUrl}3")))
        }
        eventually {
          wireMock.verify(1, putRequestedFor(urlEqualTo(s"${messageReceiptUrl}4")))
        }

        val expectedMovementForErn_1 = Movement(Some("boxId1"), "token", "1", None, Some("tokentokentokentokent"), Instant.now, expectedMessage)
        val expectedMovementForErn_3 = Movement(Some("boxId2"), "token", "3", None, Some("tokentokentokentokent"), Instant.now, expectedMessage.take(1))
        val expectedMovementForErn_4 = Movement(Some("boxId3"), "token", "4", None, Some("tokentokentokentokent"), Instant.now, Seq(createMessage(SchedulingTestData.ie704, MessageTypes.IE704.value)))
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
    }

    "not save duplicate to db" in {
      val movementRepository = new MovementRepository(
        mongoComponent,
        mongoAppConfig,
        timeService
      )

      setUpWireMockStubsWithDuplicate()

      insert(createWorkItem("1")).futureValue
      insert(createWorkItem("3")).futureValue
      insert(createWorkItem("4")).futureValue

      movementRepository.saveMovement(cachedMovement1).futureValue
      movementRepository.saveMovement(cachedMovement2).futureValue
      movementRepository.saveMovement(cachedMovement3).futureValue

      val app = appBuilder(movementRepository).build()
      running(app) {

        eventually {
          wireMock.verify(putRequestedFor(urlEqualTo(s"${showNewMessageUrl}1")))
        }
        eventually {
          wireMock.verify(putRequestedFor(urlEqualTo(s"${showNewMessageUrl}3")))
        }
        eventually {
          wireMock.verify(putRequestedFor(urlEqualTo(s"${showNewMessageUrl}4")))
        }
        eventually {
          wireMock.verify(2, putRequestedFor(urlEqualTo(s"${messageReceiptUrl}1")))
        }
        eventually {
          wireMock.verify(1, putRequestedFor(urlEqualTo(s"${messageReceiptUrl}3")))
        }
        eventually {
          wireMock.verify(1, putRequestedFor(urlEqualTo(s"${messageReceiptUrl}4")))
        }

        val expectedMovementForErn_1 = Movement(Some("boxId1"), "token", "1", None, Some("tokentokentokentokent"), Instant.now, expectedMessage)
        val expectedMovementForErn_3 = Movement(Some("boxId2"), "token", "3", None, Some("tokentokentokentokent"), Instant.now, expectedMessage.take(1))
        val expectedMovementForErn_4 = Movement(Some("boxId3"), "token", "4", None, Some("tokentokentokentokent"), Instant.now, Seq(createMessage(SchedulingTestData.ie704, MessageTypes.IE704.value)))
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
    }
    "not send acknowledge if cannot save a movement" in {

      val movementRepository = mock[MovementRepository]

      setUpWireMockStubs()

      insert(createWorkItem("1")).futureValue
      insert(createWorkItem("3")).futureValue
      insert(createWorkItem("4")).futureValue

      setUpMovementRepository(movementRepository)

      val app = appBuilder(movementRepository).build()
      running(app) {

        eventually {
          wireMock.verify(1, putRequestedFor(urlEqualTo(s"${messageReceiptUrl}1")))
        }
        eventually {
          wireMock.verify(1, putRequestedFor(urlEqualTo(s"${messageReceiptUrl}3")))
        }
        eventually {
          wireMock.verify(1, putRequestedFor(urlEqualTo(s"${messageReceiptUrl}4")))
        }
      }
    }

    "do not call EIS if nothing is in the work queue " in {

      val movementRepository = new MovementRepository(
        mongoComponent,
        mongoAppConfig,
        timeService
      )

      setUpWireMockStubs()

      movementRepository.saveMovement(cachedMovement1).futureValue
      movementRepository.saveMovement(cachedMovement2).futureValue
      movementRepository.saveMovement(cachedMovement3).futureValue

      val app = appBuilder(movementRepository).build()
      running(app) {

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
        assertResults(movements.find(_.consignorId.equals("1")).get, Movement(Some("boxId1"), "token", "1", None, None, Instant.now, Seq.empty))
        assertResults(movements.find(_.consignorId.equals("3")).get, Movement(Some("boxId2"), "token", "3", None, None, Instant.now, Seq.empty))
        assertResults(movements.find(_.consignorId.equals("4")).get, Movement(Some("boxId3"), "token", "4", None, None, Instant.now, Seq.empty))
      }
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

      movementRepository.saveMovement(cachedMovement1).futureValue

      val app = appBuilder(movementRepository).build()
      running(app) {

        val movements = movementRepository.collection.find().toFuture().futureValue

        movements.size mustBe 1
        assertResults(movements.find(_.consignorId.equals("1")).get, cachedMovement1)

        eventually {
          verifyItemAvailableAt(
            createdWorkItem.availableAt.plusSeconds(2 * 60).truncatedTo(ChronoUnit.MILLIS)
          ).futureValue mustBe true
        }

        eventually {
          verifyItemStatus(ProcessingStatus.ToDo).futureValue mustBe true
        }

      }
    }
  }

  def verifyItemStatus(expectedProcessingStatus: ProcessingStatus): Future[Boolean] = {
    findAll().map { records =>
      records.toList match {
        case Nil => false
        case head :: _ => head.status.equals(expectedProcessingStatus)
      }
    }
  }

  def verifyItemAvailableAt(
    expectedAvailableDate: Instant
  ): Future[Boolean] = {

    findAll().map { records =>
      records.toList match {
        case Nil => false
        case head :: _ => head.availableAt.compareTo(expectedAvailableDate) == 0
      }
    }
  }

  private def setUpMovementRepository(movementRepository: MovementRepository) = {
    val message1 = Message(123, "encodedMessage","IE801", "messageId", "ern", Set.empty, Instant.now())
    val message2 = Message(123, "encodedMessage","IE802", "messageId-2", "ern", Set.empty, Instant.now())

    when(movementRepository.getAllBy(eqTo("1")))
      .thenReturn(
        Future.successful(Seq(cachedMovement1)),
        Future.successful(Seq(cachedMovement1.copy(administrativeReferenceCode = Some("tokentokentokentokent"), messages = Seq(message1)))),
        Future.successful(Seq(cachedMovement1.copy(administrativeReferenceCode = Some("tokentokentokentokent"), messages = Seq(message1, message2))))
      )
    when(movementRepository.getAllBy(eqTo("3")))
      .thenReturn(Future.successful(Seq(cachedMovement2)))
    when(movementRepository.getAllBy(eqTo("4")))
      .thenReturn(Future.successful(Seq(cachedMovement3)))

    when(movementRepository.updateMovement(mockitoAny)) thenAnswer {
      (m: Movement) => {
        val hasMessageWithId = m.consignorId.equals("1") && m.messages.exists(o => o.messageId.equals("messageId-2"))
        if (hasMessageWithId) {
          throw new RuntimeException("error saving movement")
        } else
          Future.successful(Some(m))
      }
    }
  }

  private def assertPushNotificationApiForErn1(expectedMovement: Movement, movementId: String): Unit = {
    val loggerRequests = wireMock.findAll(postRequestedFor(urlEqualTo(s"/box/boxId1/notifications")))
    loggerRequests.size mustBe 3
    loggerRequests.get(0).getBodyAsString mustBe createJsonNotificationBody("1", movementId, expectedMovement, "messageId-1", "IE801").toString()
    loggerRequests.get(1).getBodyAsString mustBe createJsonNotificationBody("1", movementId, expectedMovement, "messageId-2", "IE818").toString()
    loggerRequests.get(2).getBodyAsString mustBe createJsonNotificationBody("1", movementId, expectedMovement, "messageId-3", "IE802").toString()
  }

  private def assertPushNotificationApiForErn3(expectedMovement: Movement, movementId: String): Unit = {
    val loggerRequests = wireMock.findAll(postRequestedFor(urlEqualTo(s"/box/boxId2/notifications")))
    loggerRequests.size mustBe 1
    loggerRequests.get(0).getBodyAsString mustBe createJsonNotificationBody("3", movementId, expectedMovement, "messageId-1", "IE801").toString()
  }

  private def assertPushNotificationApiForErn4(expectedMovement: Movement, movementId: String): Unit = {
    val loggerRequests = wireMock.findAll(postRequestedFor(urlEqualTo(s"/box/boxId3/notifications")))
    loggerRequests.size mustBe 1
    loggerRequests.get(0).getBodyAsString mustBe createJsonNotificationBody("4", movementId, expectedMovement, "messageId-4", "IE704").toString()
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

  private def setUpWireMockStubsWithDuplicate(): Unit = {
    stubShowNewMessageRequestForConsignorId1WithDuplicate()
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
            Instant.parse("2023-01-02T03:04:05.123Z"),
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
            Instant.parse("2023-01-02T03:04:05.123Z"),
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
            Instant.parse("2023-01-02T03:04:05.123Z"),
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
            Instant.parse("2024-01-02T03:04:05.123Z"),
            "1",
            Base64.getEncoder.encodeToString(newMessageWith818And802.toString().getBytes(StandardCharsets.UTF_8)),
          )).toString()
        ))
        .willSetStateTo("show-empty-message")
    )

    stubForEmptyMessageData("1")
  }

  private def stubShowNewMessageRequestForConsignorId1WithDuplicate(): Unit = {
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
        .willSetStateTo("show-duplicate-messages")
    )

    wireMock.stubFor(
      put(s"${showNewMessageUrl}1")
        .inScenario("requesting-new-message-for-ern-1")
        .whenScenarioStateIs("show-duplicate-messages")
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
            Instant.parse("2024-01-02T03:04:05.123Z"),
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
          MessageReceiptSuccessResponse(
            Instant.parse("2023-01-02T03:04:05.123Z"),
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

    val sixtySecsAgo = timestamp.minusSeconds(60)

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
      "ern",
      Set.empty,
      timestamp
    )
  }

  private def createJsonNotificationBody(
    ern: String,
    movementId: String,
    movement: Movement,
    messageId: String,
    messageType: String
  ): JsValue = {
    Json.toJson(Notification(
      movementId,
      s"/movements/$movementId/messages/$messageId",
      messageId,
      messageType,
      movement.consignorId,
      movement.consigneeId,
      movement.administrativeReferenceCode,
      ern
    ))
  }
}

