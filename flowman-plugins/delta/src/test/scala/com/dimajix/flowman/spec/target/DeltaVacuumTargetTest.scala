/*
 * Copyright 2021 Kaya Kupferschmidt
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

package com.dimajix.flowman.spec.target

import java.io.File
import java.time.Duration

import io.delta.sql.DeltaSparkSessionExtension
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.SparkSession
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.dimajix.common.No
import com.dimajix.common.Unknown
import com.dimajix.common.Yes
import com.dimajix.flowman.execution.Phase
import com.dimajix.flowman.execution.Session
import com.dimajix.flowman.model.Prototype
import com.dimajix.flowman.model.Relation
import com.dimajix.flowman.model.Schema
import com.dimajix.flowman.model.Target
import com.dimajix.flowman.model.ValueRelationReference
import com.dimajix.flowman.spec.ObjectMapper
import com.dimajix.flowman.spec.relation.DeltaFileRelation
import com.dimajix.flowman.spec.relation.DeltaTableRelation
import com.dimajix.flowman.spec.schema.EmbeddedSchema
import com.dimajix.flowman.types.Field
import com.dimajix.flowman.types.IntegerType
import com.dimajix.flowman.types.StringType
import com.dimajix.spark.testing.LocalSparkSession


class DeltaVacuumTargetTest extends AnyFlatSpec with Matchers with LocalSparkSession {
    override def configureSpark(builder: SparkSession.Builder): SparkSession.Builder = {
        builder.config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
            .withExtensions(new DeltaSparkSessionExtension)
    }

    "A DeltaVacuumTarget" should "be parseable" in {
        val spec =
            """
              |kind: deltaVacuum
              |relation: some_relation
              |retentionTime: P1D
              |""".stripMargin

        val targetSpec = ObjectMapper.parse[TargetSpec](spec)
        targetSpec shouldBe a[DeltaVacuumTargetSpec]

        val session = Session.builder().disableSpark().build()
        val context = session.context

        val instance = targetSpec.instantiate(context)
        instance shouldBe a[DeltaVacuumTarget]
    }

    it should "support an embedded DeltaRelation" in {
        val spec =
            """
              |kind: deltaVacuum
              |relation:
              |  kind: deltaTable
              |  database: default
              |  table: deltaTable
              |""".stripMargin

        val targetSpec = ObjectMapper.parse[TargetSpec](spec)
        targetSpec shouldBe a[DeltaVacuumTargetSpec]

        val session = Session.builder().disableSpark().build()
        val context = session.context

        val instance = targetSpec.instantiate(context)
        instance shouldBe a[DeltaVacuumTarget]
    }

    it should "work with a DeltaFileRelation" in {
        val session = Session.builder().withSparkSession(spark).build()
        val context = session.context
        val execution = session.execution

        val location = new File(tempDir, "delta/default/lala")
        val relation = DeltaFileRelation(
            Relation.Properties(context, "delta_relation"),
            schema = Some(EmbeddedSchema(
                Schema.Properties(context, "delta_schema"),
                fields = Seq(
                    Field("str_col", StringType),
                    Field("int_col", IntegerType)
                )
            )),
            location = new Path(location.toURI)
        )

        val target = DeltaVacuumTarget(
            Target.Properties(context, "vacuum"),
            ValueRelationReference(context, Prototype.of(relation))
        )
        target.phases should be (Set(Phase.BUILD))

        // == Create ==================================================================================================
        location.exists() should be (false)
        relation.exists(execution) should be (No)
        relation.create(execution)
        location.exists() should be (true)
        relation.exists(execution) should be (Yes)

        // == Vacuum ==================================================================================================
        target.dirty(execution, Phase.BUILD) should be (Unknown)
        target.execute(execution, Phase.BUILD)

        // == Destroy =================================================================================================
        relation.destroy(execution)
        location.exists() should be (false)
        relation.exists(execution) should be (No)
    }

    it should "work with a DeltaTableRelation" in {
        val session = Session.builder().withSparkSession(spark).build()
        val context = session.context
        val execution = session.execution

        val location = new File(tempDir, "delta/default/lala2")
        val relation = DeltaTableRelation(
            Relation.Properties(context, "delta_relation"),
            schema = Some(EmbeddedSchema(
                Schema.Properties(context, "delta_schema"),
                fields = Seq(
                    Field("str_col", StringType),
                    Field("int_col", IntegerType)
                )
            )),
            database = "default",
            table = "delta_table",
            location = Some(new Path(location.toURI))
        )

        val target = DeltaVacuumTarget(
            Target.Properties(context, "vacuum"),
            ValueRelationReference(context, Prototype.of(relation)),
            Some(Duration.parse("P10D"))
        )
        target.phases should be (Set(Phase.BUILD))

        // == Create ==================================================================================================
        location.exists() should be (false)
        relation.exists(execution) should be (No)
        relation.create(execution)
        location.exists() should be (true)
        relation.exists(execution) should be (Yes)

        // == Vacuum ==================================================================================================
        target.dirty(execution, Phase.BUILD) should be (Unknown)
        target.execute(execution, Phase.BUILD)

        // == Destroy =================================================================================================
        relation.destroy(execution)
        location.exists() should be (false)
        relation.exists(execution) should be (No)
    }
}
