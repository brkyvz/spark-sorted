package com.tresata.spark.sorted

import org.apache.spark.HashPartitioner

import org.scalatest.FunSpec

case class TimeValue(time: Int, value: Double)

class GroupSortedSpec extends FunSpec {
  lazy val sc = SparkSuite.sc

  import PairRDDFunctions._

  describe("PairRDDFunctions") {
    it("should do groupSort on a RDD without value ordering") {
      val rdd = sc.parallelize(List((1, 2), (2, 3), (1, 3), (3, 1), (2, 1)))
      val groupSorted = rdd.groupSort(new HashPartitioner(2), None)
      assert(groupSorted.map(_._1).collect === List(2, 2, 1, 1, 3)) // ordering depends on hashcode!
      assert(groupSorted.glom.collect.map(_.map(_._1).toList).toSet === Set(List(2, 2), List(1, 1, 3)))
    }

    it("should do groupSort on a RDD with value ordering") {
      val rdd = sc.parallelize(List((1, 2), (2, 3), (1, 3), (3, 1), (2, 1)))
      val groupSorted = rdd.groupSort(new HashPartitioner(2), Some(implicitly[Ordering[Int]]))
      assert(groupSorted.map(_._1).collect === List(2, 2, 1, 1, 3)) // ordering depends on hashcode!
      assert(groupSorted.glom.collect.map(_.toList).toSet ===  Set(List((2, 1), (2, 3)), List((1, 2), (1, 3), (3, 1))))
    }

    it("should do groupSort on a GroupSorted with no effect") {
      val rdd = sc.parallelize(List((1, 2), (2, 3), (1, 3), (3, 1), (2, 1)))
      val groupSorted = rdd.groupSort(new HashPartitioner(2), Some(implicitly[Ordering[Int]]))
      assert(groupSorted.groupSort(new HashPartitioner(2), Some(implicitly[Ordering[Int]])) eq groupSorted)
    }
  }

  describe("GroupSorted") {
    it("should do mapStreamByKey without value ordering") {
      val rdd = sc.parallelize(List(("a", 1), ("b", 10), ("a", 3), ("b", 1), ("c", 5)))
      val sets = rdd.groupSort(new HashPartitioner(2), None).mapStreamByKey(iter => Iterator(iter.toSet)) // very contrived...
      assert(sets.collect.toMap ===  Map("a" -> Set(1, 3), "b" -> Set(1, 10), "c" -> Set(5)))
    }

    it("should do mapStreamByKey with value ordering") {
      val rdd = sc.parallelize(List(("a", 1), ("b", 10), ("a", 3), ("b", 1), ("c", 5)))
      val withMax = rdd.groupSort(new HashPartitioner(2), Some(implicitly[Ordering[Int]].reverse)).mapStreamByKey{ iter =>
        val buffered = iter.buffered
        val max = buffered.head
        buffered.map(_ => max)
      }
      assert(withMax.collect.toList.groupBy(identity).mapValues(_.size) ===  Map(("a", 3) -> 2, ("b", 10) -> 2, ("c", 5) -> 1))
    }

    it("should do foldLeftByKey with value ordering") {
      val ord = Ordering.by[TimeValue, Int](_.time)
      val tseries = sc.parallelize(List(
        (5, TimeValue(2, 0.5)), (1, TimeValue(1, 1.2)), (5, TimeValue(1, 1.0)),
        (1, TimeValue(2, 2.0)), (1, TimeValue(3, 3.0))
      ))
      val emas = tseries.groupSort(new HashPartitioner(2), Some(ord)).foldLeftByKey(0.0){ case (acc, TimeValue(time, value)) => 0.8 * acc + 0.2 * value }
      assert(emas.collect.toSet === Set((1, 1.0736), (5, 0.26)))
    }

    it("should do foldLeftByKey without value ordering") {
      val rdd = sc.parallelize(List(("c", "x"), ("a", "b"), ("a", "c"), ("b", "e"), ("b", "d")))
      val sets = rdd.groupSort(new HashPartitioner(2), None).foldLeftByKey(Set.empty[String]){ case (set, str) => set + str }
      assert(sets.collect.toMap === Map("a" -> Set("b", "c"), "b" -> Set("d", "e"), "c" -> Set("x")))
    }

    it("should do mapStreamByKey with value ordering while not exhausting iterators") {
      val rdd = sc.parallelize(List(("a", 1), ("b", 10), ("a", 3), ("b", 1), ("c", 5)))
      val withMax = rdd.groupSort(new HashPartitioner(2), Some(implicitly[Ordering[Int]].reverse)).mapStreamByKey{ iter =>
        Iterator(iter.next())
      }
      assert(withMax.collect.toSet ===  Set(("a", 3), ("b", 10), ("c", 5)))
    }
  }
}
