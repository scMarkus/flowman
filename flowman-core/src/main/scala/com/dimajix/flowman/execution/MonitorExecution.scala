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

package com.dimajix.flowman.execution

import java.time.Instant

import scala.util.control.NonFatal

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

import com.dimajix.flowman.catalog.Catalog
import com.dimajix.flowman.config.FlowmanConf
import com.dimajix.flowman.hadoop.FileSystem
import com.dimajix.flowman.metric.MetricBoard
import com.dimajix.flowman.metric.MetricSystem
import com.dimajix.flowman.metric.withMetrics
import com.dimajix.flowman.metric.withWallTime
import com.dimajix.flowman.model.Assertion
import com.dimajix.flowman.model.AssertionResult
import com.dimajix.flowman.model.Job
import com.dimajix.flowman.model.JobInstance
import com.dimajix.flowman.model.JobResult
import com.dimajix.flowman.model.LifecycleResult
import com.dimajix.flowman.model.Mapping
import com.dimajix.flowman.model.Target
import com.dimajix.flowman.model.TargetResult
import com.dimajix.flowman.types.StructType
import com.dimajix.flowman.util.withShutdownHook


final class MonitorExecution(parent:Execution, listeners:Seq[(ExecutionListener,Option[Token])], metricsBoard:Option[MetricBoard]) extends Execution {
    private val logger = LoggerFactory.getLogger(classOf[MonitorExecution])

    /**
     * Returns the MetricRegistry of this execution
     *
     * @return
     */
    override def metrics: MetricSystem = parent.metrics

    /**
     * Returns the FileSystem as configured in Hadoop
     *
     * @return
     */
    override def fs: FileSystem = parent.fs

    /**
     * Returns (or lazily creates) a SparkSession of this Executor. The SparkSession will be derived from the global
     * SparkSession, but a new derived session with a separate namespace will be created.
     *
     * @return
     */
    override def spark: SparkSession = parent.spark

    /**
     * Returns the FlowmanConf object, which contains all Flowman settings.
     *
     * @return
     */
    override def flowmanConf: FlowmanConf = parent.flowmanConf

    /**
     * Returns true if a SparkSession is already available
     *
     * @return
     */
    override def sparkRunning: Boolean = parent.sparkRunning

    /**
     * Returns the table catalog used for managing Hive table instances. The Catalog will take care of many
     * technical details, like refreshing additional external catalogs like Impala.
     *
     * @return
     */
    override def catalog: Catalog = parent.catalog

    /**
     * Returns the [[OperationManager]] of this execution, which should be the instance created by the [[Session]]
     *
     * @return
     */
    override def operations: OperationManager = parent.operations

    /**
     * Creates an instance of a mapping, or retrieves it from cache
     *
     * @param mapping
     */
    override def instantiate(mapping: Mapping): Map[String, DataFrame] = parent.instantiate(mapping)

    /**
     * Returns the schema for a specific output created by a specific mapping. Note that not all mappings support
     * schema analysis beforehand. In such cases, None will be returned.
     *
     * @param mapping
     * @param output
     * @return
     */
    override def describe(mapping: Mapping, output: String): StructType = parent.describe(mapping, output)

    /**
     * Releases any temporary tables
     */
    override def cleanup(): Unit = parent.cleanup()

    /**
     * Invokes a function with a new [[Executor]] that with additional [[ExecutionListener]].
     * @param listeners
     * @param fn
     * @tparam T
     * @return
     */
    override def withListeners[T](listeners:Seq[ExecutionListener])(fn:Execution => T) : T = {
        val execution = new MonitorExecution(parent, this.listeners ++ listeners.map(l => (l,None)), metricsBoard)
        fn(execution)
    }

    /**
     * Invokes a function with a new Executor with a specific [[MetricBoard]]
     * @param metrics
     * @param fn
     * @tparam T
     * @return
     */
    override def withMetrics[T](metrics: Option[MetricBoard])(fn: Execution => T): T = {
        val execution = new MonitorExecution(parent, this.listeners, metrics)
        fn(execution)
    }

    /**
     * Monitors the execution of a lifecycle by calling appropriate listeners at the start and end.
     *
     * @param job
     * @param arguments
     * @param phases
     * @param fn
     * @tparam T
     * @return
     */
    override def monitorLifecycle(job: Job, arguments: Map[String, Any], lifecycle: Seq[Phase])(fn: Execution => LifecycleResult): LifecycleResult = {
        def start(instance:JobInstance) : Seq[(ExecutionListener, LifecycleToken)] = {
            listeners.flatMap { case(hook,parent) =>
                try {
                    Some((hook, hook.startLifecycle(this, job, instance, lifecycle)))
                } catch {
                    case NonFatal(ex) =>
                        logger.warn(s"Execution listener threw exception on startLifecycle: ${ex.toString}.")
                        None
                }
            }
        }

        def finish(tokens:Seq[(ExecutionListener, LifecycleToken)], result:LifecycleResult) : Unit = {
            tokens.foreach { case (listener, token)  =>
                try {
                    listener.finishLifecycle(this, token, result)
                } catch {
                    case NonFatal(ex) =>
                        logger.warn(s"Execution listener threw exception on finishLifecycle: ${ex.toString}.")
                }
            }
        }

        val startTime = Instant.now()
        val instance = job.instance(arguments.map { case (k, v) => k -> v.toString })
        def failure() : LifecycleResult = {
            LifecycleResult(job, instance, lifecycle, Status.FAILED, startTime)
        }

        val tokens = start(instance)
        withShutdownHook(finish(tokens, failure())) {
            withTokens(tokens) { execution =>
                val result = try {
                    fn(execution)
                } catch {
                    case NonFatal(ex) =>
                        finish(tokens, failure())
                        throw ex
                }
                finish(tokens, result)
                result
            }
        }
    }

    /**
     * Monitors the execution of a job by calling appropriate listeners at the start and end.
     *
     * @param job
     * @param arguments
     * @param phase
     * @param fn
     * @tparam T
     * @return
     */
    override def monitorJob(job: Job, arguments: Map[String, Any], phase: Phase)(fn: Execution => JobResult): JobResult = {
        def start(instance:JobInstance) : Seq[(ExecutionListener, JobToken)] = {
            listeners.flatMap { case(hook,parent) =>
                try {
                    Some((hook, hook.startJob(this, job, instance, phase, parent)))
                } catch {
                    case NonFatal(ex) =>
                        logger.warn(s"Execution listener threw exception on startJob: ${ex.toString}.")
                        None
                }
            }
        }

        def finish(tokens:Seq[(ExecutionListener, JobToken)], result:JobResult) : Unit = {
            tokens.foreach { case (listener, token)  =>
                try {
                    listener.finishJob(this, token, result)
                } catch {
                    case NonFatal(ex) =>
                        logger.warn(s"Execution listener threw exception on finishJob: ${ex.toString}.")
                }
            }
        }

        val startTime = Instant.now()
        val instance = job.instance(arguments.map { case (k, v) => k -> v.toString })
        def failure() : JobResult = {
            JobResult(job, instance, phase, Status.FAILED, startTime)
        }

        val tokens = start(instance)
        withShutdownHook(finish(tokens, failure())) {
            withTokens(tokens) { execution =>
                val result = try {
                    com.dimajix.flowman.metric.withMetrics(metrics, metricsBoard) {
                        withWallTime(metrics, job.metadata, phase) {
                            fn(execution)
                        }
                    }
                } catch {
                    case NonFatal(ex) =>
                        finish(tokens, failure())
                        throw ex
                }
                finish(tokens, result)
                result
            }
        }
    }

    /**
     * Monitors the execution of a target by calling appropriate listeners at the start and end.
     *
     * @param target
     * @param phase
     * @param fn
     * @tparam T
     * @return
     */
    override def monitorTarget(target: Target, phase: Phase)(fn: Execution => TargetResult): TargetResult = {
        def start() : Seq[(ExecutionListener, TargetToken)] = {
            listeners.flatMap { case(hook,parent) =>
                try {
                    Some((hook, hook.startTarget(this, target, target.instance, phase, parent)))
                } catch {
                    case NonFatal(ex) =>
                        logger.warn(s"Execution listener threw exception on startTarget: ${ex.toString}.")
                        None
                }
            }
        }

        def finish(tokens:Seq[(ExecutionListener, TargetToken)], result:TargetResult) : Unit = {
            tokens.foreach { case (listener, token)  =>
                try {
                    listener.finishTarget(this, token, result)
                } catch {
                    case NonFatal(ex) =>
                        logger.warn(s"Execution listener threw exception on finishTarget: ${ex.toString}.")
                }
            }
        }

        val startTime = Instant.now()
        def failure() : TargetResult = {
            TargetResult(target, phase, Status.FAILED, startTime)
        }

        val tokens = start()
        withShutdownHook(finish(tokens, failure())) {
            withTokens(tokens) { execution =>
                val result = try {
                    withWallTime(execution.metrics, target.metadata, phase) {
                        fn(execution)
                    }
                } catch {
                    case NonFatal(ex) =>
                        finish(tokens, failure())
                        throw ex
                }
                finish(tokens, result)
                result
            }
        }
    }

    /**
     * Monitors the execution of an assertion by calling appropriate listeners at the start and end.
     *
     * @param assertion
     * @param fn
     * @tparam T
     * @return
     */
    override def monitorAssertion(assertion: Assertion)(fn: Execution => AssertionResult): AssertionResult = {
        def start() : Seq[(ExecutionListener, AssertionToken)] = {
            listeners.flatMap { case(hook,parent) =>
                try {
                    Some((hook, hook.startAssertion(this, assertion, parent)))
                } catch {
                    case NonFatal(ex) =>
                        logger.warn(s"Execution listener threw exception on startAssertion: ${ex.toString}.")
                        None
                }
            }
        }

        def finish(tokens:Seq[(ExecutionListener, AssertionToken)], result:AssertionResult) : Unit = {
            tokens.foreach { case (listener, token)  =>
                try {
                    listener.finishAssertion(this, token, result)
                } catch {
                    case NonFatal(ex) =>
                        logger.warn(s"Execution listener threw exception on finishAssertion: ${ex.toString}.")
                }
            }
        }

        val startTime = Instant.now()
        def failure() : AssertionResult = {
            // TODO: On shutdown, the assertion should be in FAILED state
            AssertionResult(assertion, Seq(), startTime)
        }

        val tokens = start()
        withShutdownHook(finish(tokens, failure())) {
            withTokens(tokens) { execution =>
                val result = try {
                    fn(execution)
                } catch {
                    case NonFatal(ex) =>
                        finish(tokens, failure())
                        throw ex
                }
                finish(tokens, result)
                result
            }
        }
    }

    private def withTokens[T <: Token,R](tokens:Seq[(ExecutionListener,T)])(fn:Execution => R) : R = {
        val execution = new MonitorExecution(this.parent, tokens.map(lt => (lt._1, Option(lt._2))), metricsBoard)
        fn(execution)
    }
}
