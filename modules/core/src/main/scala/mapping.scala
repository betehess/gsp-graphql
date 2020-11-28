// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle

import cats.Monad
import cats.data.{Chain, Ior}
import cats.implicits._
import io.circe.{Encoder, Json}

import Query.Select
import QueryCompiler.{ComponentElaborator, SelectElaborator}
import QueryInterpreter.mkErrorResult
import scala.annotation.unused
import org.tpolecat.typename._

trait QueryExecutor[F[_], T] { outer =>
  implicit val M: Monad[F]

  def run(query: Query, rootTpe: Type): F[T]

  def compileAndRun(text: String, name: Option[String] = None, untypedEnv: Option[Json] = None, useIntrospection: Boolean = true): F[T]
}

abstract class Mapping[F[_]](implicit val M: Monad[F]) extends QueryExecutor[F, Json] with SchemaValidation {

  val schema: Schema
  val typeMappings: List[TypeMapping]

  def run(query: Query, rootTpe: Type): F[Json] =
    interpreter.run(query, rootTpe)

  def compileAndRun(text: String, name: Option[String] = None, untypedEnv: Option[Json] = None, useIntrospection: Boolean = true): F[Json] =
    compiler.compile(text, name, untypedEnv, useIntrospection) match {
      case Ior.Right(compiledQuery) =>
        run(compiledQuery, schema.queryType)
      case invalid =>
        QueryInterpreter.mkInvalidResponse(invalid).pure[F]
    }

  def typeMapping(tpe: Type): Option[TypeMapping] =
    typeMappings.find(_.tpe.nominal_=:=(tpe))

  def validate: Chain[Json] = {
    val missingSchemaTypes: List[String] =
      typeMappings.flatMap(_.tpe.asNamed match {
        case None => Nil
        case Some(nt) =>
          if (schema.ref(nt).isEmpty) List(s"Found mapping for unknown type ${nt.name}")
          else Nil
      })

    val oms = typeMappings.collect { case om: ObjectMapping => om }

    val missingSchemaFields: List[String] =
      oms.flatMap { om =>
        val obj = om.tpe
        val on = obj.asNamed.map(_.name).getOrElse("Unknown")
        om.fieldMappings.collect {
          case fm if fm.isPublic && !obj.hasField(fm.fieldName) =>
            s"Found mapping for unknown field ${fm.fieldName} of type $on"
        }
      }

    val missingRequiredMappings: List[String] =
      oms.flatMap { om =>
        def hasRequired(name: String): Boolean =
          om.fieldMappings.exists(_.fieldName == name)

        val on = om.tpe.asNamed.map(_.name).getOrElse("Unknown")

        om.fieldMappings.flatMap {
          case CursorField(_, _, _, required) => required.filterNot(hasRequired)
          case CursorAttribute(_, _, required) => required.filterNot(hasRequired)
          case _ => Nil
        }.map(fan => s"No field/attribute mapping for $fan in object mapping for $on")
      }

    Chain.fromSeq((missingSchemaTypes ++ missingSchemaFields ++ missingRequiredMappings).map(Json.fromString))
  }

  def rootMapping(path: List[String], tpe: Type, fieldName: String): Option[RootMapping] =
    tpe match {
      case JoinType(componentName, _) =>
        rootMapping(Nil, schema.queryType, componentName)
      case _ =>
        fieldMapping(path, tpe, fieldName).collect {
          case rm: RootMapping => rm
        }
    }

  def rootCursor(path: List[String], rootTpe: Type, fieldName: String, child: Query): F[Result[Cursor]] =
    rootMapping(path, rootTpe, fieldName) match {
      case Some(root) =>
        root.cursor(child)
      case None =>
        mkErrorResult(s"No root field '$fieldName' in $rootTpe").pure[F]
    }

  def objectMapping(path: List[String], tpe: Type): Option[ObjectMapping] =
    typeMapping(tpe) match {
      case Some(om: ObjectMapping) => Some(om)
      case Some(pm: PrefixedMapping) =>
        val matching = pm.mappings.filter(m => path.startsWith(m._1.reverse))
        matching.sortBy(m => -m._1.length).headOption.map(_._2)
      case _ => None
    }

  def fieldMapping(path: List[String], tpe: Type, fieldName: String): Option[FieldMapping] =
    objectMapping(path, tpe).flatMap(_.fieldMappings.find(_.fieldName == fieldName).orElse {
      tpe.dealias match {
        case ot: ObjectType =>
          ot.interfaces.collectFirstSome(nt => fieldMapping(path, nt, fieldName))
        case _ => None
      }
    })

  def leafMapping[T](tpe: Type): Option[LeafMapping[T]] =
    typeMappings.collectFirst {
      case lm@LeafMapping(tpe0, _) if tpe0 =:= tpe => lm.asInstanceOf[LeafMapping[T]]
    }

  trait TypeMapping extends Product with Serializable {

    def tpe: Type

    def validationFailures: Chain[SchemaValidation.Failure] =
      Chain(warning("Validation is not yet available."))

    protected def failure(severity: SchemaValidation.Severity, fieldName: Option[String], message: String): SchemaValidation.Failure =
      new SchemaValidation.Failure(severity, tpe, fieldName, productPrefix) {
        override def text =
          s"""|${super.text}
              |$message
              |""".stripMargin
      }

    protected def warning(message: String, fieldName: Option[String] = None): SchemaValidation.Failure =
      failure(SchemaValidation.Severity.Warning, fieldName, message)

    protected def error(message: String, fieldName: Option[String] = None): SchemaValidation.Failure =
      failure(SchemaValidation.Severity.Error, fieldName, message)

    protected def info(message: String, fieldName: Option[String] = None): SchemaValidation.Failure =
      failure(SchemaValidation.Severity.Info, fieldName, message)

    }

  trait ObjectMapping extends TypeMapping {
    def fieldMappings: List[FieldMapping]
  }

  object ObjectMapping {

    case class DefaultObjectMapping(tpe: Type, fieldMappings: List[FieldMapping]) extends ObjectMapping {
      override def validationFailures: Chain[SchemaValidation.Failure] =
        tpe.dealias match {
          case ot @ ObjectType(_, _, fields, _) => fields.foldMap { f =>
            fieldMappings.find(_.fieldName == f.name) match {
              case Some(m) => m.validationFailures(ot, f)
              case None =>
                val message =
                  s"""|- Field $ot.${f.name} appears in a schema defined at ...
                      |
                      |However
                      |
                      |- It does not appear in the object mapping for $ot defined at ...
                      |""".stripMargin.trim
                Chain(error(message, Some(f.name)))
            }
          }
          case other => Chain(error(s"Type mismatch: expected ObjectType, found ${other.productPrefix}."))
        }
    }

    def apply(tpe: Type, fieldMappings: List[FieldMapping]): ObjectMapping =
      DefaultObjectMapping(tpe, fieldMappings.map(_.withParent(tpe)))
  }

  case class PrefixedMapping(tpe: Type, mappings: List[(List[String], ObjectMapping)]) extends TypeMapping

  trait FieldMapping extends Product with Serializable {
    def fieldName: String
    def isPublic: Boolean
    def withParent(tpe: Type): FieldMapping

    def validationFailures(owner: ObjectType, @unused field: Field): Chain[SchemaValidation.Failure] = {
      Chain(warning(owner, "Validation is not yet available."))
    }

    protected def failure(owner: ObjectType, severity: SchemaValidation.Severity, message: String): SchemaValidation.Failure =
      new SchemaValidation.Failure(severity, owner, Some(fieldName), productPrefix) {
        override def text =
          s"""|${super.text}
              |$message
              |""".stripMargin
      }

    protected def warning(owner: ObjectType, message: String): SchemaValidation.Failure =
      failure(owner, SchemaValidation.Severity.Warning, message)

    protected def error(owner: ObjectType, message: String): SchemaValidation.Failure =
      failure(owner, SchemaValidation.Severity.Error, message)

    protected def info(owner: ObjectType, message: String): SchemaValidation.Failure =
      failure(owner, SchemaValidation.Severity.Info, message)

  }

  trait RootMapping extends FieldMapping {
    def isPublic = true
    def cursor(query: Query): F[Result[Cursor]]
    def withParent(tpe: Type): RootMapping
  }

  trait LeafMapping[T] extends TypeMapping {
    def tpe: Type
    def encoder: Encoder[T]
    def scalaTypeName: String
  }
  object LeafMapping {

    case class DefaultLeafMapping[T](tpe: Type, encoder: Encoder[T], scalaTypeName: String) extends LeafMapping[T] {
      override def validationFailures: Chain[SchemaValidation.Failure] =
        tpe.dealias match {
          case ScalarType(_, _) => Chain.empty // these are valid on construction. Nothing to do.
          case other            => Chain(error(s"Cannot use $productPrefix with ${other.productPrefix}"))
        }
    }

    def apply[T: TypeName](tpe: Type)(implicit encoder: Encoder[T]): LeafMapping[T] =
      DefaultLeafMapping(tpe, encoder, typeName)

    def unapply[T](lm: LeafMapping[T]): Option[(Type, Encoder[T])] =
      Some((lm.tpe, lm.encoder))
  }

  case class CursorField[T](fieldName: String, f: Cursor => Result[T], encoder: Encoder[T], required: List[String]) extends FieldMapping {
    def isPublic = true
    def withParent(tpe: Type): CursorField[T] = this
  }
  object CursorField {
    def apply[T](fieldName: String, f: Cursor => Result[T], required: List[String] = Nil)(implicit encoder: Encoder[T], di: DummyImplicit): CursorField[T] =
      new CursorField(fieldName, f, encoder, required)
  }

  case class CursorAttribute[T](fieldName: String, f: Cursor => Result[T], required: List[String] = Nil) extends FieldMapping {
    def isPublic = false
    def withParent(tpe: Type): CursorAttribute[T] = this
  }

  case class Delegate(
    fieldName: String,
    interpreter: Mapping[F],
    join: (Cursor, Query) => Result[Query] = ComponentElaborator.TrivialJoin
  ) extends FieldMapping {
    def isPublic = true
    def withParent(tpe: Type): Delegate = this
  }

  val selectElaborator: SelectElaborator = new SelectElaborator(Map.empty[TypeRef, PartialFunction[Select, Result[Query]]])

  lazy val componentElaborator = {
    val componentMappings =
      typeMappings.flatMap {
        case om: ObjectMapping =>
          om.fieldMappings.collect {
            case Delegate(fieldName, mapping, join) =>
              ComponentElaborator.ComponentMapping(schema.ref(om.tpe.toString), fieldName, mapping, join)
          }
        case _ => Nil
      }

    ComponentElaborator(componentMappings)
  }

  def compilerPhases: List[QueryCompiler.Phase] = List(selectElaborator, componentElaborator)

  lazy val compiler = new QueryCompiler(schema, compilerPhases)

  val interpreter: QueryInterpreter[F] = new QueryInterpreter(this)
}
