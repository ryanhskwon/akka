package akka.streams
package ops

import org.scalatest.{ Tag, ShouldMatchers, WordSpec }
import rx.async.api.Producer
import rx.async.spi.Publisher
import akka.streams.Operation._
import akka.streams.ops.Implementation.ImplementationSettings

class OperationSemanticsSpec extends WordSpec with ShouldMatchers {
  val oneToTen = FromIterableSource(1 to 10)
  import Operation._

  "Operations" should {
    "fold elements synchronously with small input" in {
      //implicit val implSettings = ImplementationSettings(traceTrampolining = true)
      val p = instance[Int](oneToTen.fold(0)(_ + _))
      p.handle(RequestMore(1)) should be(Emit(55) ~ Complete)
    }
    "fold elements synchronously with big input" in {
      val p = instance[Long](FromIterableSource(1L to 1000000L).fold(0L)(_ + _))
      p.handle(RequestMore(1)) should be(Emit(500000500000L) ~ Complete)
    }
    "create element spans" in {
      case class SubEmit(i: Int) extends CustomForwardResult[Source[Int]]
      case object SubComplete extends CustomForwardResult[Nothing]
      case class SubError(cause: Throwable) extends CustomForwardResult[Nothing]
      object MyPublisherResults extends PublisherResults[Int] {
        def emit(o: Int): Result[Source[Int]] = SubEmit(o)
        def complete: Result[Source[Int]] = SubComplete
        def error(cause: Throwable): Result[Source[Int]] = SubError(cause)
      }

      val p = instance[Source[Int]](FromIterableSource(1 to 6).span(_ % 3 == 0))
      val Emit(InternalPublisherFinished(f)) = p.handle(RequestMore(1))
      val handler = f(MyPublisherResults)
      p.handle(RequestMore(1)) should be(Continue)

      handler.handle(RequestMore(1)) should be(SubEmit(1))
      handler.handle(RequestMore(1)) should be(SubEmit(2))
      val Combine(Combine(SubEmit(3), SubComplete), Emit(InternalPublisherFinished((next)))) = handler.handle(RequestMore(1))

      val nextHandler = next(MyPublisherResults)
      nextHandler.handle(RequestMore(1)) should be(SubEmit(4))
      nextHandler.handle(RequestMore(1)) should be(SubEmit(5))
      nextHandler.handle(RequestMore(1)) should be(SubEmit(6) ~ SubComplete ~ Complete)
    }
    "flatten with generic producer" in {
      object MyProducer extends Producer[Int] {
        def getPublisher: Publisher[Int] = ???
      }
      val p = opInstance[Producer[Int], Int](Identity[Producer[Int]]().flatten)
      p.handle(RequestMore(4)) should be(RequestMore(1))
      val s @ Subscribe(_) = p.handle(Emit(MyProducer))

      case class SubRequestMore(subId: Symbol, n: Int) extends CustomBackchannelResult
      case class MySubscriptionResults(subId: Symbol) extends SubscriptionResults {
        def requestMore(n: Int): Result[Nothing] = SubRequestMore(subId, n)
      }
      val handler = s.handlerFactory(MySubscriptionResults('sub1))
      handler.initial should be(SubRequestMore('sub1, 4))
      handler.handle(Emit(1)) should be(Emit(1))
      handler.handle(Complete) should be(RequestMore(1))

      val s2 @ Subscribe(_) = p.handle(Emit(MyProducer))
      val handler2 = s2.handlerFactory(MySubscriptionResults('sub2))
      handler2.initial should be(SubRequestMore('sub2, 3))
      handler2.handle(Emit(12)) should be(Emit(12))

      // meanwhile complete main stream
      p.handle(Complete) should be(Continue)

      handler2.handle(Emit(38)) should be(Emit(38))
      handler2.handle(Complete) should be(Complete)
    }

    "flatten with internal producer" taggedAs (Only) in {
      // TODO: maybe use another example as `span().flatten` could also be statically optimized into `identity`
      //implicit val implSettings = ImplementationSettings(traceTrampolining = true)
      val p = instance[Int](FromIterableSource(1 to 6).span(_ % 3 == 0).flatten)
      p.handle(RequestMore(1)) should be(Emit(1))
      println("After 1")
      p.handle(RequestMore(1)) should be(Emit(2))
      p.handle(RequestMore(1)) should be(Emit(3))
      p.handle(RequestMore(1)) should be(Emit(4))
      p.handle(RequestMore(1)) should be(Emit(5))
      p.handle(RequestMore(1)) should be(Emit(6) ~ Complete)
    }
  }

  def instance[O](source: Source[O])(implicit settings: ImplementationSettings): OpInstance[Nothing, O] = Implementation(source)
  def opInstance[I, O](op: Operation[I, O])(implicit settings: ImplementationSettings): OpInstance[I, O] = Implementation(op)
}
object Only extends Tag("only")