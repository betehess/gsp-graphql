// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle.sql.test

import cats.effect.IO
import io.circe.Json
import org.scalatest.funsuite.AnyFunSuite
import cats.effect.unsafe.implicits.global

import edu.gemini.grackle._
import syntax._

import grackle.test.GraphQLResponseTests.assertWeaklyEqual

trait SqlUnionSpec extends AnyFunSuite {
  def mapping: QueryExecutor[IO, Json]

  test("simple union query") {
    val query = """
      query {
        collection {
          ... on ItemA {
            itema
          }
          ... on ItemB {
            itemb
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "collection" : [
            {
              "itema" : "A"
            },
            {
              "itemb" : "B"
            }
          ]
        }
      }
    """

    val res = mapping.compileAndRun(query).unsafeRunSync()
    //println(res)

    assertWeaklyEqual(res, expected)
  }

  test("union query with introspection") {
    val query = """
      query {
        collection {
          ... on ItemA {
            __typename
            itema
          }
          ... on ItemB {
            __typename
            itemb
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "collection" : [
            {
              "__typename" : "ItemA",
              "itema" : "A"
            },
            {
              "__typename" : "ItemB",
              "itemb" : "B"
            }
          ]
        }
      }
    """

    val res = mapping.compileAndRun(query).unsafeRunSync()
    //println(res)

    assertWeaklyEqual(res, expected)
  }

  test("unrequested members of union returns empty response") {
    val query = """
      query {
        collection {
          ... on ItemA {
            itema
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "collection" : [
            {
              "itema" : "A"
            },
            {
            }
          ]
        }
      }
    """

    val res = mapping.compileAndRun(query).unsafeRunSync()
    //println(res)

    assertWeaklyEqual(res, expected)
  }

  test("union query with only introspection") {
    val query = """
      query {
        collection {
          ... on ItemA {
            __typename
          }
          ... on ItemB {
            __typename
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "collection" : [
            {
              "__typename" : "ItemA"
            },
            {
              "__typename" : "ItemB"
            }
          ]
        }
      }
    """

    val res = mapping.compileAndRun(query).unsafeRunSync()
    //println(res)

    assertWeaklyEqual(res, expected)
  }
}
