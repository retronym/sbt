/*
 * sbt
 * Copyright 2023, Scala center
 * Copyright 2011 - 2022, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt.std

import hedgehog.{ Gen, Property, Range, Result, Syntax }
import hedgehog.runner.*
import sbt.{ Completed, ConcurrentRestrictions, TaskId }
import sbt.ConcurrentRestrictions.{ All, Tag, TagMap, tagged }
import scala.collection.mutable

object PendingTasksTest extends Properties:
  override def tests: List[Test] = List(
    property(
      "pending tasks start in the order of retrying every one of them",
      sameStartsAsRetryingAll
    ),
    example("tags.add calls grow linearly with the number of pending tasks", linearAdds),
  )

  private final class Node(val id: Int, val tags: TagMap) extends TaskId[Unit]:
    override def toString = s"#$id$tags"

  /** Records `add` calls and the nodes whose addition was found valid, i.e. started. */
  private final class Spy(val underlying: ConcurrentRestrictions) extends ConcurrentRestrictions:
    type G = underlying.G
    var adds = 0
    val started = mutable.ArrayBuffer.empty[Node]
    private var added: Node | Null = null
    def empty: G = underlying.empty
    def add(g: G, a: TaskId[?]): G =
      adds += 1
      added = a.asInstanceOf[Node]
      underlying.add(g, a)
    def remove(g: G, a: TaskId[?]): G = underlying.remove(g, a)
    def valid(g: G): Boolean =
      val v = underlying.valid(g)
      if v && added != null then started += added.nn
      added = null
      v
    override def hold(g: G, a: TaskId[?]): G = underlying.hold(g, a)
    override def unhold(g: G, a: TaskId[?]): G = underlying.unhold(g, a)
    override private[sbt] def restrictionKey(a: TaskId[?]): Any = underlying.restrictionKey(a)

  /** The scheduling of sbt 1.12: every completion retries every pending task in order. */
  private final class RetryAll(r: ConcurrentRestrictions):
    private var state = r.empty
    private val pending = mutable.ListBuffer.empty[Node]
    val started = mutable.ArrayBuffer.empty[Node]
    private def tryStart(n: Node): Boolean =
      val next = r.add(state, n)
      if r.valid(next) then
        state = next
        started += n
      r.valid(next)
    def submit(n: Node): Unit = if !tryStart(n) then pending += n
    def complete(n: Node): Unit =
      state = r.remove(state, n)
      pending.filterInPlace(p => !tryStart(p))

  /** Runs the completion service on the calling thread: work starts only when `complete` runs it. */
  private final class Harness(restrictions: ConcurrentRestrictions):
    val spy = new Spy(restrictions)
    private val executed = mutable.ArrayBuffer.empty[Runnable]
    val service =
      ConcurrentRestrictions.completionService(r => executed += r, spy, sys.error)
    def submit(n: Node): Unit = service.submit(n, () => null.asInstanceOf[Completed])
    def running: Seq[Node] = spy.started.indices.filter(executed(_) ne null).map(spy.started)
    def complete(n: Node): Unit =
      val i = spy.started.indexOf(n)
      val r = executed(i)
      executed(i) = null
      r.run()

  private val tagPool = Vector(Tag("a"), Tag("b"), Tag("c"))

  private sealed trait Op
  private final case class Submit(tags: TagMap) extends Op
  private final case class Complete(pick: Int) extends Op

  private val genTags: Gen[TagMap] =
    for
      present <- Gen.list(Gen.boolean, Range.singleton(tagPool.size))
      weights <- Gen.list(Gen.int(Range.linear(1, 2)), Range.singleton(tagPool.size))
    yield tagPool.zip(present.zip(weights)).collect { case (t, (true, w)) => t -> w }.toMap

  private val genOp: Gen[Op] =
    Gen.frequency1(
      3 -> genTags.map(Submit(_)),
      2 -> Gen.int(Range.linear(0, 100)).map(Complete(_)),
    )

  private def sameStartsAsRetryingAll: Property =
    for
      limits <- Gen.list(Gen.int(Range.linear(2, 3)), Range.singleton(tagPool.size)).forAll
      allLimit <- Gen.int(Range.linear(1, 4)).forAll
      ops <- Gen.list(genOp, Range.linear(0, 60)).forAll
    yield
      val limitOf = tagPool.zip(limits).toMap + (All -> allLimit)
      val restrictions =
        tagged(m => limitOf.forall((t, max) => m.getOrElse(t, 0) <= max))
      val harness = new Harness(restrictions)
      val model = new RetryAll(restrictions)
      var nextId = 0
      ops.foreach:
        case Submit(tags) =>
          val n = new Node(nextId, tags)
          nextId += 1
          harness.submit(n)
          model.submit(n)
        case Complete(pick) =>
          val running = harness.running
          if running.nonEmpty then
            val n = running(pick % running.size)
            harness.complete(n)
            model.complete(n)
      harness.service.close()
      harness.spy.started.toList.map(_.id) ==== model.started.toList.map(_.id)

  private def linearAdds: Result =
    val tasks = 400
    val harness = new Harness(tagged(_.getOrElse(All, 0) <= 2))
    (0 until tasks).foreach: i =>
      harness.submit(new Node(i, if i % 2 == 0 then Map.empty else Map(tagPool(0) -> 1)))
    while harness.running.nonEmpty do harness.complete(harness.running.head)
    harness.service.close()
    Result.all(
      List(
        harness.spy.started.size ==== tasks,
        Result.assert(harness.spy.adds <= 4 * tasks).log(s"adds: ${harness.spy.adds}"),
      )
    )
end PendingTasksTest
