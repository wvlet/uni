package wvlet.uni.http.rpc

import wvlet.uni.http.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.time.LocalDate

case class Booking(id: Long, day: LocalDate) derives Weaver

trait BookingApi:
  def nextDay(day: LocalDate): LocalDate
  def book(id: Long, day: LocalDate): Booking

class BookingApiImpl extends BookingApi:
  def nextDay(day: LocalDate): LocalDate      = day.plusDays(1)
  def book(id: Long, day: LocalDate): Booking = Booking(id, day)

class RPCLocalDateTest extends UniTest:
  private val config = Http
    .client
    .withChannelFactory(
      InMemoryRPCChannelFactory(RPCDispatcher(RPCRouter.of[BookingApi](BookingApiImpl())))
    )

  private val day = LocalDate.of(2026, 11, 21)

  test("pass LocalDate arguments and results over RPC") {
    val rpc = RPCClient.of[BookingApi].newSyncClient(config.newSyncClient)
    rpc.call[LocalDate]("nextDay", day) shouldBe LocalDate.of(2026, 11, 22)
    rpc.call[Booking]("book", 1L, day) shouldBe Booking(1L, day)
  }

end RPCLocalDateTest
