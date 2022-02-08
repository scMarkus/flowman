/*
 * Copyright 2022 Kaya Kupferschmidt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dimajix.flowman.documentation

import com.dimajix.common.MapIgnoreCase
import com.dimajix.flowman.types.Field



final case class ColumnReference(
    override val parent:Option[Reference],
    name:String
) extends Reference

object ColumnDoc {
    def merge(thisCols:Seq[ColumnDoc], otherCols:Seq[ColumnDoc]) :Seq[ColumnDoc] = {
        val thisColsByName = MapIgnoreCase(thisCols.map(c => c.name -> c))
        val otherColsByName = MapIgnoreCase(otherCols.map(c => c.name -> c))
        val mergedColumns = thisCols.map { column =>
            otherColsByName.get(column.name) match {
                case Some(other) => column.merge(other)
                case None => column
            }
        }
        mergedColumns ++ otherCols.filter(c => !thisColsByName.contains(c.name))
    }

}
final case class ColumnDoc(
    parent:Option[Reference],
    name:String,
    field:Field,
    description:Option[String],
    children:Seq[ColumnDoc],
    tests:Seq[ColumnTest]
) extends EntityDoc {
    override def reference: ColumnReference = ColumnReference(parent, name)
    override def fragments: Seq[Fragment] = children
    override def reparent(parent: Reference): ColumnDoc = {
        val ref = ColumnReference(Some(parent), name)
        copy(
            parent = Some(parent),
            children = children.map(_.reparent(ref)),
            tests = tests.map(_.reparent(ref))
        )
    }

    def nullable : Boolean = field.nullable
    def typeName : String = field.typeName
    def sqlType : String = field.sqlType
    def sparkType : String = field.sparkType.sql
    def catalogType : String = field.catalogType.sql

    def merge(other:ColumnDoc) : ColumnDoc = {
        val childs =
            if (this.children.nonEmpty && other.children.nonEmpty)
                ColumnDoc.merge(children, other.children)
            else
                this.children ++ other.children
        val desc = description.orElse(description)
        val tsts = tests ++ other.tests
        copy(description=desc, children=childs, tests=tsts)
    }
}
