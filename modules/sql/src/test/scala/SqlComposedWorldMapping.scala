// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package composed

import cats.Monad
import cats.data.Ior
import cats.implicits._
import edu.gemini.grackle._
import edu.gemini.grackle.sql.Like
import edu.gemini.grackle.syntax._

import Query._
import Path._
import Predicate._
import Value._
import QueryCompiler._
import QueryInterpreter.mkErrorResult

import utils.SqlTestMapping

/* Currency component */

object CurrencyData {
  case class Currency(
    code: String,
    exchangeRate: Double,
    countryCode: String
  )

  val BRL = Currency("BRL", 0.25, "BRA")
  val EUR = Currency("EUR", 1.12, "NLD")
  val GBP = Currency("GBP", 1.25, "GBR")

  val currencies = List(BRL, EUR, GBP)

}

class CurrencyMapping[F[_] : Monad] extends ValueMapping[F] {
  import CurrencyData._

  val schema =
    schema"""
      type Query {
        allCurrencies: [Currency!]!
      }
      type Currency {
        code: String!
        exchangeRate: Float!
        countryCode: String!
      }
    """

  val QueryType = schema.ref("Query")
  val CurrencyType = schema.ref("Currency")

  val typeMappings =
    List(
      ObjectMapping(
        tpe = QueryType,
        fieldMappings =
          List(
            ValueRoot("allCurrencies", currencies)
          )
      ),
      ValueObjectMapping[Currency](
        tpe = CurrencyType,
        fieldMappings =
          List(
            ValueField("code", _.code),
            ValueField("exchangeRate", _.exchangeRate),
            ValueField("countryCode", _.countryCode)
          )
      )
  )
}

object CurrencyMapping {
  def apply[F[_] : Monad]: CurrencyMapping[F] = new CurrencyMapping[F]
}

/* World component */

trait SqlWorldMapping[F[_]] extends SqlTestMapping[F] {
  object country extends TableDef("country") {
    val code           = col("code", bpchar(3))
    val name           = col("name", text)
    val continent      = col("continent", text)
    val region         = col("region", text)
    val surfacearea    = col("surfacearea", float4)
    val indepyear      = col("indepyear", nullable(int2))
    val population     = col("population", int4)
    val lifeexpectancy = col("lifeexpectancy", nullable(float4))
    val gnp            = col("gnp", nullable(numeric(10, 2)))
    val gnpold         = col("gnpold", nullable(numeric(10, 2)))
    val localname      = col("localname", text)
    val governmentform = col("governmentform", text)
    val headofstate    = col("headofstate", nullable(text))
    val capitalId      = col("capital", nullable(int4))
    val numCities      = col("num_cities", int8)
    val code2          = col("code2", bpchar(2))
  }

  object city extends TableDef("city") {
    val id          = col("id", int4)
    val countrycode = col("countrycode", bpchar(3))
    val name        = col("name", text)
    val district    = col("district", text)
    val population  = col("population", int4)
  }

  object countrylanguage extends TableDef("countrylanguage") {
    val countrycode = col("countrycode", bpchar(3))
    val language = col("language", text)
    val isOfficial = col("isOfficial", bool)
    val percentage = col("percentage", float4)
  }

  val schema =
    schema"""
      type Query {
        cities(namePattern: String = "%"): [City!]
        country(code: String): Country
        countries: [Country!]
      }
      type City {
        name: String!
        country: Country!
        district: String!
        population: Int!
      }
      type Language {
        language: String!
        isOfficial: Boolean!
        percentage: Float!
        countries: [Country!]!
      }
      type Country {
        name: String!
        continent: String!
        region: String!
        surfacearea: Float!
        indepyear: Int
        population: Int!
        lifeexpectancy: Float
        gnp: String
        gnpold: String
        localname: String!
        governmentform: String!
        headofstate: String
        capitalId: Int
        code2: String!
        cities: [City!]!
        languages: [Language!]!
      }
    """

  val QueryType = schema.ref("Query")
  val CountryType = schema.ref("Country")
  val CityType = schema.ref("City")
  val LanguageType = schema.ref("Language")

  val typeMappings =
    List(
      ObjectMapping(
        tpe = QueryType,
        fieldMappings =
          List(
            SqlRoot("cities"),
            SqlRoot("country"),
            SqlRoot("countries")
          )
      ),
      ObjectMapping(
        tpe = CountryType,
        fieldMappings = List(
          SqlField("code",           country.code, key = true, hidden = true),
          SqlField("name",           country.name),
          SqlField("continent",      country.continent),
          SqlField("region",         country.region),
          SqlField("surfacearea",    country.surfacearea),
          SqlField("indepyear",      country.indepyear),
          SqlField("population",     country.population),
          SqlField("lifeexpectancy", country.lifeexpectancy),
          SqlField("gnp",            country.gnp),
          SqlField("gnpold",         country.gnpold),
          SqlField("localname",      country.localname),
          SqlField("governmentform", country.governmentform),
          SqlField("headofstate",    country.headofstate),
          SqlField("capitalId",      country.capitalId),
          SqlField("code2",          country.code2),
          SqlObject("cities",        Join(country.code, city.countrycode)),
          SqlObject("languages",     Join(country.code, countrylanguage.countrycode))
        ),
      ),
      ObjectMapping(
        tpe = CityType,
        fieldMappings = List(
          SqlField("id", city.id, key = true, hidden = true),
          SqlField("countrycode", city.countrycode, hidden = true),
          SqlField("name", city.name),
          SqlField("district", city.district),
          SqlField("population", city.population),
          SqlObject("country", Join(city.countrycode, country.code)),
        )
      ),
      ObjectMapping(
        tpe = LanguageType,
        fieldMappings = List(
          SqlField("language", countrylanguage.language, key = true),
          SqlField("isOfficial", countrylanguage.isOfficial),
          SqlField("percentage", countrylanguage.percentage),
          SqlField("countrycode", countrylanguage.countrycode, hidden = true),
          SqlObject("countries", Join(countrylanguage.countrycode, country.code))
        )
      )
    )
}

/* Composition */

class SqlComposedMapping[F[_] : Monad]
  (world: Mapping[F], currency: Mapping[F]) extends Mapping[F] {
  val schema =
    schema"""
      type Query {
        cities(namePattern: String = "%"): [City!]
        country(code: String): Country
        countries: [Country!]
        currencies: [Currency!]!
      }
      type City {
        name: String!
        country: Country!
        district: String!
        population: Int!
      }
      type Language {
        language: String!
        isOfficial: Boolean!
        percentage: Float!
        countries: [Country!]!
      }
      type Currency {
        code: String!
        exchangeRate: Float!
        countryCode: String!
      }
      type Country {
        name: String!
        continent: String!
        region: String!
        surfacearea: Float!
        indepyear: Int
        population: Int!
        lifeexpectancy: Float
        gnp: String
        gnpold: String
        localname: String!
        governmentform: String!
        headofstate: String
        capitalId: Int
        code2: String!
        cities: [City!]!
        languages: [Language!]!
        currencies: [Currency!]!
      }
    """

  val QueryType = schema.ref("Query")
  val CountryType = schema.ref("Country")

  val typeMappings =
    List(
      ObjectMapping(
        tpe = QueryType,
        fieldMappings =
          List(
            Delegate("country", world),
            Delegate("countries", world),
            Delegate("cities", world)
          )
      ),
      ObjectMapping(
        tpe = CountryType,
        fieldMappings =
          List(
            Delegate("currencies", currency, countryCurrencyJoin)
          )
      )
    )

  def countryCurrencyJoin(c: Cursor, q: Query): Result[Query] =
    (c.fieldAs[String]("code"), q) match {
      case (Ior.Right(countryCode: String), Select("currencies", _, child)) =>
        Select("allCurrencies", Nil, Filter(Eql(UniquePath(List("countryCode")), Const(countryCode)), child)).rightIor
      case _ => mkErrorResult(s"Expected 'code' attribute at ${c.tpe}")
    }

  override val selectElaborator =  new SelectElaborator(Map(
    QueryType -> {
      case Select("country", List(Binding("code", StringValue(code))), child) =>
        Select("country", Nil, Unique(Filter(Eql(UniquePath(List("code")), Const(code)), child))).rightIor
      case Select("countries", _, child) =>
        Select("countries", Nil, child).rightIor
      case Select("cities", List(Binding("namePattern", StringValue(namePattern))), child) =>
        Select("cities", Nil, Filter(Like(UniquePath(List("name")), namePattern, true), child)).rightIor
    }
  ))
}