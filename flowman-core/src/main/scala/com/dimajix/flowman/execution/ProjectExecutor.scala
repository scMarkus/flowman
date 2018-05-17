/*
 * Copyright 2018 Kaya Kupferschmidt
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

package com.dimajix.flowman.execution

import scala.collection.mutable

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.broadcast
import org.slf4j.LoggerFactory

import com.dimajix.flowman.spec.Project
import com.dimajix.flowman.spec.TableIdentifier


private[execution] class ProjectExecutor(_parent:Executor, _project:Project, context:Context)
    extends AbstractExecutor(_parent.session, context) {
    override protected val logger = LoggerFactory.getLogger(classOf[ProjectExecutor])
    private val _tables = mutable.Map[String,DataFrame]()

    /**
      * Instantiates a table and recursively all its dependencies
      *
      * @param tableName
      * @return
      */
    private def createTable(tableName: String): DataFrame = {
        logger.info(s"Creating instance of table ${_project.name}/$tableName")
        implicit val icontext = context

        // Lookup table definition
        val transform = context.getMapping(TableIdentifier(tableName, None))
        if (transform == null) {
            logger.error(s"Table ${_project.name}/$tableName not found")
            throw new NoSuchElementException(s"Table ${_project.name}/$tableName not found")
        }

        // Ensure all dependencies are instantiated
        logger.info(s"Ensuring dependencies for table ${_project.name}/$tableName")
        val dependencies = transform.dependencies.map(d => (d, instantiate(d))).toMap

        // Process table and register result as temp table
        logger.info(s"Instantiating table ${_project.name}/$tableName")
        val instance = transform.execute(this, dependencies).persist(transform.cache)
        val df = if (transform.broadcast)
            broadcast(instance)
        else
            instance

        _tables.put(tableName, df)
        df
    }

    /**
      * Returns the project of this executor
      *
      * @return
      */
    def project : Project = _project

    /**
      * Returns a list of all tables of this Executor.
      *
      * @return
      */
    override def tables : Map[TableIdentifier,DataFrame] = _tables.toMap.map(kv => (TableIdentifier(kv._1, _project.name), kv._2))

    /**
      * Creates an instance of a table of a Dataflow, or retrieves it from cache
      *
      * @param tableName
      */
    override def instantiate(tableName: TableIdentifier) : DataFrame = {
        if (tableName.project.forall(_ == _project.name))
            _tables.getOrElseUpdate(tableName.name, createTable(tableName.name))
        else
            _parent.instantiate(tableName)
    }

    /**
      * Cleans up the session
      */
    override def cleanup() : Unit = {
        // Unregister all temporary tables
        if (sparkRunning) {
            logger.info("Cleaning up temporary tables and caches")
            val catalog = spark.catalog
            _tables.foreach(kv => catalog.dropTempView(kv._1))
            _tables.foreach(kv => kv._2.unpersist())
        }
    }

    /**
      * Returns a named table created by an executor. If a project is specified, Executors for other projects
      * will be searched as well
      *
      * @param identifier
      * @return
      */
    override def getTable(identifier: TableIdentifier): DataFrame = {
        if (identifier.project.forall(_ == _project.name))
            _tables.getOrElse(identifier.name, throw new NoSuchElementException(s"Table ${identifier.name} not found in project ${_project.name}"))
        else
            _parent.getTable(identifier)
    }
}
