// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package grackle.test

import cats.effect.IO
import io.circe.Json
import org.scalatest.funsuite.AnyFunSuite
import cats.effect.unsafe.implicits.global

import edu.gemini.grackle._
import Path._
import Predicate._
import sql.{Like, SqlStatsMonitor}
import syntax._

import GraphQLResponseTests.assertWeaklyEqual

/** Tests that confirm the compiler is writing the queries we want. */
trait SqlWorldCompilerSpec extends AnyFunSuite {

  type Fragment
  def mapping: IO[(QueryExecutor[IO, Json], SqlStatsMonitor[IO, Fragment])]

  /** Expected SQL string for the simple restricted query test. */
  def simpleRestrictedQuerySql: String

  /** Expected SQL string for the simple filtered query test. */
  def simpleFilteredQuerySql: String

  test("simple restricted query") {

    val query =
      """
        query {
          country(code: "GBR") {
            name
          }
        }
      """

    val expected =
      json"""
        {
          "data": {
            "country": {
              "name": "United Kingdom"
            }
          }
        }
      """

    val prog: IO[(Json, List[SqlStatsMonitor.SqlStats])] =
      for {
        mm  <- mapping
        (map, mon) = mm
        res <- map.compileAndRun(query)
        ss  <- mon.take
      } yield (res, ss.map(_.normalize))

    val (res, stats) = prog.unsafeRunSync()

    assertWeaklyEqual(res, expected)

    //println(stats.head.sql)

    assert(
      stats == List(
        SqlStatsMonitor.SqlStats(
          Query.Unique(Query.Filter(Eql(UniquePath(List("code")),Const("GBR")),Query.Select("name",List(),Query.Empty))),
          simpleRestrictedQuerySql,
          List("GBR"),
          1,
          2
        )
      )
    )

  }

  test("simple filtered query") {

    val query =
      """
        query {
          cities(namePattern: "Linh%") {
            name
          }
        }
      """

    val expected =
      json"""
        {
          "data" : {
            "cities" : [
              {
                "name" : "Linhe"
              },
              {
                "name" : "Linhai"
              },
              {
                "name" : "Linhares"
              }
            ]
          }
        }
      """

    val prog: IO[(Json, List[SqlStatsMonitor.SqlStats])] =
      for {
        mm  <- mapping
        (map, mon) = mm
        res <- map.compileAndRun(query)
        ss  <- mon.take
      } yield (res, ss.map(_.normalize))

    val (res, stats) = prog.unsafeRunSync()

    assertWeaklyEqual(res, expected)

    //println(stats.head.sql)

    assert(
      stats == List(
        SqlStatsMonitor.SqlStats(
          Query.Filter(Like(UniquePath(List("name")),"Linh%",true),Query.Select("name",List(),Query.Empty)),
          simpleFilteredQuerySql,
          List("Linh%"),
          3,
          2
        )
      )
    )

  }
}
