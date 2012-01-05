/*
 * Copyright 2011 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.scrooge
package scalagen

import java.io.File
import com.twitter.conversions.string._
import com.twitter.handlebar.{Dictionary, Handlebar}
import AST._

case class ConstList(constList: Seq[Const])

abstract sealed class ScalaServiceOption
case object WithFinagleClient extends ScalaServiceOption
case object WithFinagleService extends ScalaServiceOption
case object WithOstrichServer extends ScalaServiceOption

case class ScalaService(service: Service, options: Set[ScalaServiceOption])

class ScalaGenerator extends Generator with StructTemplate with ServiceTemplate {
  import Dictionary._

  implicit val templates = new HandlebarLoader("/scalagen/")

  val header = templates("header").generate { doc: Document =>
    val imports = doc.headers.collect {
      case AST.Include(_, doc) => doc.scalaNamespace
    } filter(_ != doc.scalaNamespace) map { ns =>
      Dictionary("namespace" -> ns)
    }
    Dictionary(
      "scalaNamespace" -> v(doc.scalaNamespace),
      "imports" -> v(imports)
    )
  }

  val enumTemplate = templates("enum").generate { enum: Enum =>
    val values = enum.values map { value =>
      Dictionary(
        "name" -> v(value.name),
        "nameLowerCase" -> v(value.name.toLowerCase),
        "value" -> v(value.value.toString)
      )
    }
    Dictionary(
      "enum_name" -> v(enum.name),
      "values" -> v(values)
    )
  }

  val enumsTemplate = templates("enums").generate { enums: Seq[Enum] =>
    val enumDictionaries = enums.map(enumTemplate.unpacker)
    Dictionary(
      "hasEnums" -> v(enumDictionaries.nonEmpty),
      "enums" -> v(enumDictionaries),
      "enum" -> v(enumTemplate.handlebar)
    )
  }

  val constsTemplate = templates("consts").generate { consts: ConstList =>
    val constants = consts.constList map { c =>
      Dictionary(
        "name" -> v(c.name),
        "type" -> v(scalaType(c.`type`)),
        "value" -> v(constantValue(c.value))
      )
    }
    Dictionary(
      "hasConstants" -> v(constants.nonEmpty),
      "constants" -> v(constants)
    )
  }

  def quote(str: String) = "\"" + str.quoteC() + "\""

  def listValue(list: ListConstant): String = {
    "List(" + list.elems.map(constantValue).mkString(", ") + ")"
  }

  def mapValue(map: MapConstant): String = {
    "Map(" + (map.elems.map {
      case (k, v) =>
        constantValue(k) + " -> " + constantValue(v)
    } mkString(", ")) + ")"
  }

  def constantValue(constant: Constant): String = {
    constant match {
      case NullConstant => "null"
      case StringConstant(value) => quote(value)
      case DoubleConstant(value) => value.toString
      case IntConstant(value) => value.toString
      case BoolConstant(value) => value.toString
      case c @ ListConstant(_) => listValue(c)
      case c @ MapConstant(_) => mapValue(c)
      case EnumValueConstant(enum, value) => enum.name + "." + value.name
      case Identifier(name) => name
    }
  }

  def writeFieldConst(name: String) = name.toUpperCase + "_FIELD_DESC"

  /**
   * The default value for a field when deserializing the thrift struct
   * and the field is not present.
   */
  def defaultReadValue(field: Field) = {
    if (field.requiredness.isOptional) {
      "None"
    } else {
      field.`type` match {
        case TBool => "false"
        case TByte | TI16 | TI32 | TI64 => "0"
        case TDouble => "0.0"
        case _ => "null"
      }
    }
  }

  def constType(t: FunctionType): String = {
    t match {
      case Void => "VOID"
      case TBool => "BOOL"
      case TByte => "BYTE"
      case TDouble => "DOUBLE"
      case TI16 => "I16"
      case TI32 => "I32"
      case TI64 => "I64"
      case TString => "STRING"
      case TBinary => "STRING" // thrift's idea of "string" is based on old broken c++ semantics.
      case StructType(_) => "STRUCT"
      case EnumType(_) => "I32" // enums are converted to ints
      case MapType(_, _, _) => "MAP"
      case SetType(_, _) => "SET"
      case ListType(_, _) => "LIST"
      case x => throw new InternalError("constType#" + t)
    }
  }

  def protocolReadMethod(t: FunctionType): String = {
    t match {
      case TBool => "readBool"
      case TByte => "readByte"
      case TI16 => "readI16"
      case TI32 => "readI32"
      case TI64 => "readI64"
      case TDouble => "readDouble"
      case TString => "readString"
      case TBinary => "readBinary"
      case x => throw new InternalError("protocolReadMethod#" + t)
    }
  }

  def protocolWriteMethod(t: FunctionType): String = {
    t match {
      case TBool => "writeBool"
      case TByte => "writeByte"
      case TI16 => "writeI16"
      case TI32 => "writeI32"
      case TI64 => "writeI64"
      case TDouble => "writeDouble"
      case TString => "writeString"
      case TBinary => "writeBinary"
      case x => throw new InternalError("protocolWriteMethod#" + t)
    }
  }

  def scalaType(t: FunctionType): String = {
    t match {
      case Void => "Unit"
      case TBool => "Boolean"
      case TByte => "Byte"
      case TI16 => "Short"
      case TI32 => "Int"
      case TI64 => "Long"
      case TDouble => "Double"
      case TString => "String"
      case TBinary => "ByteBuffer"
      case MapType(k, v, _) => "Map[" + scalaType(k) + ", " + scalaType(v) + "]"
      case SetType(x, _) => "Set[" + scalaType(x) + "]"
      case ListType(x, _) => "Seq[" + scalaType(x) + "]"
      case n: NamedType => n.name
    }
  }

  def scalaFieldType(f: Field): String = {
    if (f.requiredness.isOptional) {
      "Option[" + scalaType(f.`type`) + "]"
    } else {
      scalaType(f.`type`)
    }
  }

  def defaultFieldValue(f: Field): Option[String] = {
    f.default.map(constantValue).map { v =>
      if (f.requiredness.isOptional) "Some(" + v + ")" else v
    } orElse {
      if (f.requiredness.isOptional) Some("None") else None
    }
  }

  def fieldArgs(args: Seq[Field]): String = {
    args.map { f =>
      val prefix = "`" + f.name + "`: " + scalaFieldType(f)
      val suffix = defaultFieldValue(f) map { " = " + _ }
      prefix + suffix.getOrElse("")
    }.mkString(", ")
  }

  // deprecated (for tests)
  def apply(doc: Document, enum: Enum): String = header(doc) + enumTemplate(enum)
  def apply(doc: Document, consts: ConstList): String = header(doc) + constsTemplate(consts)
  def apply(doc: Document, struct: StructLike): String = header(doc) + structTemplate(struct)
  def apply(doc: Document, service: Service): String = header(doc) + serviceTemplate(ScalaService(service, Set()))

  def apply(_doc: Document, serviceOptions: Set[ScalaServiceOption]): String = {
    val doc = _doc.camelize

    val constSection = constsTemplate(ConstList(doc.consts))
    val enumSection = enumsTemplate(doc.enums)
    val structSection = doc.structs map { x => structTemplate(x) } mkString("", "\n\n", "\n\n")
    val serviceSection = doc.services.map { x =>
      serviceTemplate(ScalaService(x, serviceOptions))
    } mkString("", "\n\n", "\n\n")

    header(doc) + "\n" + constSection + enumSection + structSection + serviceSection
  }
}
