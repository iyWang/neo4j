/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.collector

import java.nio.file.Files

import org.neo4j.cypher._
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.collection.mutable.ArrayBuffer

class DataCollectorQueriesAcceptanceTest extends ExecutionEngineFunSuite {

  import DataCollectorMatchers._

  test("should collect and retrieve queries") {
    // given
    execute("RETURN 'not collected!'")
    execute("CALL db.stats.collect('QUERIES')").single

    execute("MATCH (n) RETURN count(n)")
    execute("MATCH (n)-->(m) RETURN n,m")
    execute("WITH 'hi' AS x RETURN x+'-ho'")

    execute("CALL db.stats.stop('QUERIES')").single
    execute("RETURN 'not collected!'")

    // when
    val res = execute("CALL db.stats.retrieve('QUERIES')").toList

    // then
    res should beListWithoutOrder(
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> beMapContaining(
          "query" -> "MATCH (n) RETURN count(n)"
        )
      ),
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> beMapContaining(
          "query" -> "MATCH (n)-->(m) RETURN n,m"
        )
      ),
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> beMapContaining(
          "query" -> "WITH 'hi' AS x RETURN x+'-ho'"
        )
      )
    )
  }

  test("should clear queries") {
    // given
    execute("CALL db.stats.collect('QUERIES')").single
    execute("MATCH (n) RETURN count(n)")
    execute("CALL db.stats.stop('QUERIES')").single
    execute("CALL db.stats.retrieve('QUERIES')").toList should have size 1

    // when
    execute("CALL db.stats.clear('QUERIES')").single

    // then
    execute("CALL db.stats.retrieve('QUERIES')").toList should be(empty)
  }

  test("should append queries if restarted collection") {
    // given
    execute("CALL db.stats.collect('QUERIES')").single
    execute("MATCH (n) RETURN count(n)")
    execute("CALL db.stats.stop('QUERIES')").single
    execute("CALL db.stats.retrieve('QUERIES')").toList should have size 1

    // when
    execute("CALL db.stats.collect('QUERIES')").single
    execute("RETURN 'another query'")
    execute("CALL db.stats.stop('QUERIES')").single

    execute("CALL db.stats.retrieve('QUERIES')").toList should have size 2
  }

  test("should stop collection after specified time") {
    // given
    execute("CALL db.stats.collect('QUERIES', {durationSeconds: 3})").single
    execute("MATCH (n) RETURN count(n)")
    Thread.sleep(4000)

    // then
    execute("CALL db.stats.status()").single should beMapContaining(
      "status" -> "idle",
      "section" -> "QUERIES"
    )

    // and when
    execute("RETURN 'late query'")

    // then
    execute("CALL db.stats.retrieve('QUERIES')").toList should have size 1
  }

  test("should not stop later collection event after initial timeout") {
    // given
    execute("CALL db.stats.collect('QUERIES', {durationSeconds: 3})").single
    execute("MATCH (n) RETURN count(n)")
    execute("CALL db.stats.stop('QUERIES')").single
    execute("CALL db.stats.collect('QUERIES')").single
    Thread.sleep(4000)

    // then
    execute("CALL db.stats.status()").single should beMapContaining(
      "status" -> "collecting",
      "section" -> "QUERIES"
    )
  }

  test("should retrieve query execution plan and estimated rows") {
    // given
    execute("CREATE (a), (b), (c)")

    execute("CALL db.stats.collect('QUERIES')").single
    execute("MATCH (n) RETURN sum(id(n))")
    execute("MATCH (n) RETURN count(n)")
    execute("CALL db.stats.stop('QUERIES')").single

    // when
    val res = execute("CALL db.stats.retrieve('QUERIES')").toList

    // then
    res should beListWithoutOrder(
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> beMapContaining(
          "query" -> "MATCH (n) RETURN sum(id(n))",
          "queryExecutionPlan" -> Map(
            "id" -> 0,
            "operator" -> "ProduceResults",
            "lhs" -> Map(
              "id" -> 1,
              "operator" -> "EagerAggregation",
              "lhs" -> Map(
                "id" -> 2,
                "operator" -> "AllNodesScan"
              )
            )
          ),
          "estimatedRows" -> List(1.0, 1.0, 3.0)
        )
      ),
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> beMapContaining(
          "query" -> "MATCH (n) RETURN count(n)",
          "queryExecutionPlan" -> Map(
            "id" -> 0,
            "operator" -> "ProduceResults",
            "lhs" -> Map(
              "id" -> 1,
              "operator" -> "NodeCountFromCountStore"
            )
          ),
          "estimatedRows" -> List(1.0, 1.0)
        )
      )
    )
  }

  test("should retrieve invocations of query") {
    // given
    execute("CALL db.stats.collect('QUERIES')").single
    execute("MATCH (n {p: $param}) RETURN count(n)", Map("param" -> "BrassLeg"))
    execute("MATCH (n {p: $param}) RETURN count(n)", Map("param" -> 2))
    execute("WITH 42 AS x RETURN x")
    execute("MATCH (n {p: $param}) RETURN count(n)", Map("param" -> List(3.1, 3.2)))
    execute("WITH 42 AS x RETURN x")
    execute("CALL db.stats.stop('QUERIES')").single

    // when
    val res = execute("CALL db.stats.retrieve('QUERIES')").toList

    // then
    res should beListWithoutOrder(
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> beMapContaining(
          "query" -> "MATCH (n {p: $param}) RETURN count(n)",
          "invocations" -> beInvocationsInOrder(
            Map("param" -> "BrassLeg"),
            Map("param" -> Long.box(2)),
            Map("param" -> List(3.1, 3.2))
          )
        )
      ),
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> beMapContaining(
          "query" -> "WITH 42 AS x RETURN x",
          "invocations" -> beInvocationCount(2)
        )
      )
    )
  }

  test("should retrieve invocation summary of query") {
    // given
    execute("CALL db.stats.collect('QUERIES')").single
    execute("MATCH (n {p: $param}) RETURN count(n)", Map("param" -> "BrassLeg"))
    execute("MATCH (n {p: $param}) RETURN count(n)", Map("param" -> 2))
    execute("WITH 42 AS x RETURN x")
    execute("MATCH (n {p: $param}) RETURN count(n)", Map("param" -> List(3.1, 3.2)))
    execute("WITH 42 AS x RETURN x")
    execute("CALL db.stats.stop('QUERIES')").single

    // when
    val res = execute("CALL db.stats.retrieve('QUERIES')").toList

    // then
    res should beListWithoutOrder(
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> QueryWithInvocationSummary("MATCH (n {p: $param}) RETURN count(n)")
      ),
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> QueryWithInvocationSummary("WITH 42 AS x RETURN x")
      )
    )
  }

  test("should limit number of retrieved invocations of query") {
    // given
    execute("CALL db.stats.collect('QUERIES')").single
    execute("MATCH (n {p: $param}) RETURN count(n)", Map("param" -> "BrassLeg"))
    execute("MATCH (n {p: $param}) RETURN count(n)", Map("param" -> 2))
    execute("WITH 42 AS x RETURN x")
    execute("MATCH (n {p: $param}) RETURN count(n)", Map("param" -> List(3.1, 3.2)))
    execute("WITH 42 AS x RETURN x")
    execute("WITH 42 AS x RETURN x")
    execute("WITH 42 AS x RETURN x")
    execute("CALL db.stats.stop('QUERIES')").single

    // when
    val res = execute("CALL db.stats.retrieve('QUERIES', {maxInvocations: 2})").toList

    // then
    res should beListWithoutOrder(
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> beMapContaining(
          "query" -> "MATCH (n {p: $param}) RETURN count(n)",
          "invocations" -> beInvocationsInOrder(
            Map("param" -> "BrassLeg"),
            Map("param" -> 2)
          )
        )
      ),
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> beMapContaining(
          "query" -> "WITH 42 AS x RETURN x",
          "invocations" -> beInvocationCount(2)
        )
      )
    )
  }

  test("should fail on incorrect maxInvocations") {
    execute("CALL db.stats.retrieve('QUERIES', {})").toList // missing maxInvocations argument is fine
    execute("CALL db.stats.retrieve('QUERIES', {maxIndications: -1})").toList // non-related arguments is fine
    assertInvalidArgument("CALL db.stats.retrieve('QUERIES', {maxInvocations: 'non-integer'})") // non-integer is not fine
    assertInvalidArgument("CALL db.stats.retrieve('QUERIES', {maxInvocations: -1})") // negative integer is not fine
  }

  test("[retrieveAllAnonymized] should anonymize tokens inside queries") {
    // given
    execute("CREATE (:User {age: 99})-[:KNOWS]->(:Buddy {p: 42})-[:WANTS]->(:Raccoon)") // create tokens
    execute("CALL db.stats.collect('QUERIES')").single
    execute("MATCH (:User)-[:KNOWS]->(:Buddy)-[:WANTS]->(:Raccoon) RETURN 1")
    execute("MATCH ({p: 42}), ({age: 43}) RETURN 1")
    execute("CALL db.stats.stop('QUERIES')").single

    // when
    val res = execute("CALL db.stats.retrieveAllAnonymized('myToken')")

    // then
    res.toList should beListWithoutOrder(
      querySection("MATCH (:L0)-[:R0]->(:L1)-[:R1]->(:L2) RETURN 1"),
      querySection("MATCH ({p1: 42}), ({p0: 43}) RETURN 1")
    )
  }

  test("[retrieveAllAnonymized] should handle pre-parser options") {
    // given
    execute("CREATE (:User {age: 99})-[:KNOWS]->(:Buddy {p: 42})-[:WANTS]->(:Raccoon)") // create tokens
    execute("CALL db.stats.collect('QUERIES')").single
    execute("EXPLAIN MATCH (:User)-[:KNOWS]->(:Buddy)-[:WANTS]->(:Raccoon) RETURN 1")
    execute("CYPHER 3.4 runtime=interpreted PROFILE CREATE ()")
    execute("CALL db.stats.stop('QUERIES')").single

    // when
    val res = execute("CALL db.stats.retrieveAllAnonymized('myToken')")

    // then
    res.toList should beListWithoutOrder(
      querySection("EXPLAIN MATCH (:L0)-[:R0]->(:L1)-[:R1]->(:L2) RETURN 1"),
      querySection("CYPHER 3.4 runtime=interpreted PROFILE CREATE ()")
    )
  }

  test("[retrieveAllAnonymized] should handle load csv") {
    // given
    val path = Files.createTempFile("data", ".csv")
    val url = s"file://$path"
    execute("CALL db.stats.collect('QUERIES')").single
    execute(s"LOAD CSV FROM '$url' AS row CREATE ({key: row[0]})")
    execute(s"USING PERIODIC COMMIT 30 LOAD CSV FROM '$url' AS row CREATE ({key: row[0]})")
    execute("CALL db.stats.stop('QUERIES')").single

    // when
    val res = execute("CALL db.stats.retrieveAllAnonymized('myToken')")

    // then
    val urlLength = url.length
    res.toList should beListWithoutOrder(
      querySection(s"LOAD CSV FROM 'string[$urlLength]' AS var0 CREATE ({UNKNOWN0: var0[0]})"),
      querySection(s"USING PERIODIC COMMIT 30 LOAD CSV FROM 'string[$urlLength]' AS var0 CREATE ({UNKNOWN0: var0[0]})")
    )
  }

  test("[retrieveAllAnonymized] should anonymize unknown tokens inside queries") {
    // given
    execute("CALL db.stats.collect('QUERIES')").single
    execute("MATCH (:User)-[:KNOWS]->(:Buddy)-[:WANTS]->(:Raccoon) RETURN 1")
    execute("MATCH ({p: 42}), ({age: 43}) RETURN 1")
    execute("CALL db.stats.stop('QUERIES')").single

    // when
    val res = execute("CALL db.stats.retrieveAllAnonymized('myToken')")

    // then
    res.toList should beListWithoutOrder(
      querySection("MATCH (:UNKNOWN0)-[:UNKNOWN1]->(:UNKNOWN2)-[:UNKNOWN3]->(:UNKNOWN4) RETURN 1"),
      querySection("MATCH ({UNKNOWN0: 42}), ({UNKNOWN1: 43}) RETURN 1")
    )
  }

  test("[retrieveAllAnonymized] should anonymize string literals inside queries") {
    // given
    execute("CALL db.stats.collect('QUERIES')").single
    execute("RETURN 'Scrooge' AS uncle, 'Donald' AS name")
    execute("CALL db.stats.stop('QUERIES')").single

    // when
    val res = execute("CALL db.stats.retrieveAllAnonymized('myToken')")

    // then
    res.toList should beListWithoutOrder(
      querySection("RETURN 'string[7]' AS var0, 'string[6]' AS var1")
    )
  }

  test("[retrieveAllAnonymized] should anonymize variables and return names") {
    // given
    execute("CALL db.stats.collect('QUERIES')").single
    execute("RETURN 42 AS x")
    execute("WITH 42 AS x RETURN x")
    execute("WITH 1 AS k RETURN k + k")
    execute("CALL db.stats.stop('QUERIES')").single

    // when
    val res = execute("CALL db.stats.retrieveAllAnonymized('myToken')")

    // then
    res.toList should beListWithoutOrder(
      querySection("RETURN 42 AS var0"),
      querySection("WITH 42 AS var0 RETURN var0"),
      querySection("WITH 1 AS var0 RETURN var0 + var0")
    )
  }

  test("[retrieveAllAnonymized] should anonymize parameters") {
    // given
    execute("CALL db.stats.collect('QUERIES')").single
    execute("RETURN 42 = $user, $name", Map("user" -> "BrassLeg", "name" -> "George"))
    execute("RETURN 42 = $user, $name", Map("user" -> 2, "name" -> "Glinda"))
    execute("RETURN 42 = $user, $name", Map("user" -> List(3.1, 3.2), "name" -> "Kim"))
    execute("RETURN $user, $name, $user + $name", Map("user" -> List(3.1, 3.2), "name" -> "Kim"))
    execute("CALL db.stats.stop('QUERIES')").single

    // when
    val res = execute("CALL db.stats.retrieveAllAnonymized('myToken')")

    // then
    res.toList should beListWithoutOrder(
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> beMapContaining(
          "query" -> "RETURN 42 = $param0, $param1",
          "invocations" -> beInvocationsInOrder(
            "4ac156c0", // Map("user" -> "BrassLeg", "name" -> "George")
            "b439d06c", // Map("user" -> 2, "name" -> "Glinda")
            "e5adc9ad"  // Map("user" -> List(3.1, 3.2), "name" -> "Kim")
          )
        )
      ),
      beMapContaining(
        "section" -> "QUERIES",
        "data" -> beMapContaining(
          "query" -> "RETURN $param0, $param1, $param0 + $param1",
          "invocations" -> beInvocationsInOrder(
            "e5adc9ad" // Map("user" -> List(3.1, 3.2), "name" -> "Kim")
          )
        )
      )
    )
  }

  private def querySection(queryText: String) =
    beMapContaining(
      "section" -> "QUERIES",
      "data" -> beMapContaining(
        "query" -> beCypher(queryText)
      )
    )

  private def assertInvalidArgument(query: String): Unit = {
    val e = intercept[CypherExecutionException](execute(query))
    e.status should be(org.neo4j.kernel.api.exceptions.Status.General.InvalidArguments)
  }

  case class QueryWithInvocationSummary(expectedQuery: String) extends Matcher[AnyRef] {
    override def apply(left: AnyRef): MatchResult = {
      left match {
        case m: Map[String, AnyRef] =>
          val query = m("query")
          if (query != expectedQuery)
            return MatchResult(matches = false,
              s"""Expected query
                 |  $expectedQuery
                 |got
                 |  $query""".stripMargin, "")

          val invocations = m("invocations").asInstanceOf[Seq[Map[String, AnyRef]]]
          val compileTimes = invocations.map(inv => inv("elapsedCompileTimeInUs").asInstanceOf[Long])
          val executionTimes = invocations.map(inv => inv("elapsedExecutionTimeInUs").asInstanceOf[Long])

          beMapContaining(
            "compileTimeInUs" -> beMapContaining(
              "min" -> compileTimes.min,
              "max" -> compileTimes.max,
              "avg" -> (compileTimes.sum / compileTimes.size)
            ),
            "executionTimeInUs" -> beMapContaining(
              "min" -> executionTimes.min,
              "max" -> executionTimes.max,
              "avg" -> (executionTimes.sum / executionTimes.size)
            ),
            "invocationCount" -> invocations.size
          ).apply(m("invocationSummary"))

        case _ =>
          MatchResult(matches = false, s"Expected map, got $left", "")
      }
    }
  }

  def beInvocationCount(count: Int) = InvocationsMatcher((0 until count).map(_ => null))

  def beInvocationsInOrder(expectedParams: AnyRef*) = InvocationsMatcher(expectedParams)

  case class InvocationsMatcher(expectedParams: Seq[AnyRef]) extends Matcher[AnyRef] {

    override def apply(left: AnyRef): MatchResult = {
      val errors = new ArrayBuffer[String]
      left match {
        case values: Seq[AnyRef] =>

          var previousInvocationTime = 0L

          for (i <- expectedParams.indices) {
            val expectedParam = expectedParams(i)

            if (i < values.size) {
              values(i) match {
                case map: Map[String, AnyRef] =>
                  if (expectedParam != null) {
                    val params = map.getOrElse("params", null)
                    if (params != expectedParam)
                      errors += s"Expected invocation with params '$expectedParam' at position $i, but has params '$params'"
                  }

                  map("elapsedExecutionTimeInUs").asInstanceOf[Long] should be > 0L
                  map("elapsedCompileTimeInUs").asInstanceOf[Long] should be > 0L
                  map("startTimestampMillis").asInstanceOf[Long] should be > 0L
                  val invocationTime = map("startTimestampMillis").asInstanceOf[Long]
                  if (invocationTime < previousInvocationTime)
                    errors += s"Expected invocations to be ordered by start timestamp, but got invocation with timestamp $invocationTime ordered after invocation with timestamp $previousInvocationTime"
                  previousInvocationTime = invocationTime

                case x => errors += s"Expected all invocations to be maps, but got $x"
              }

            } else
              errors += s"Expected invocation with param '$expectedParam' at position $i, but list was too small"
          }
          if (values.size > expectedParams.size)
            errors += s"Expected ${expectedParams.size} invocations, but got additional invocations ${values.slice(expectedParams.size, values.size)}"

        case x =>
          errors += s"Expected invocation list but got '$x'"
      }
      MatchResult(
        matches = errors.isEmpty,
        rawFailureMessage = "Encountered a bunch of errors: " + errors.map("  "+_).mkString("\n", "\n", "\n"),
        rawNegatedFailureMessage = "BAH"
      )
    }

    override def toString(): String = s"invocations with params (${expectedParams.mkString(", ")})"
  }
}
