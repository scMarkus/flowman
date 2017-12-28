package com.dimajix.dataflow.cli.flow

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.kohsuke.args4j.Argument
import org.kohsuke.args4j.Option
import org.slf4j.LoggerFactory

import com.dimajix.dataflow.cli.Command
import com.dimajix.dataflow.execution.Context
import com.dimajix.dataflow.execution.Executor
import com.dimajix.dataflow.spec.Dataflow


class ShowCommand extends AbstractCommand {
    private val logger = LoggerFactory.getLogger(classOf[ShowCommand])

    @Option(name="-n", aliases=Array("--limit"), usage="Specifies maximimum number of rows to print", metaVar="<limit>", required = false)
    var limit: Int = 10
    @Argument(usage = "specifies the table to show", metaVar = "<tablename>", required = true)
    var tablename: String = ""


    override def executeInternal(context:Context, dataflow:Dataflow) : Boolean = {
        logger.info("Showing first {} rows of table {}", limit:Any, tablename:Any)

        val executor = new Executor(context, dataflow)

        Try {
            val table = executor.instantiate(tablename)
            table.limit(limit).show(truncate = false)
        } match {
            case Success(_) =>
                logger.info("Successfully finished dumping table")
                true
            case Failure(e) =>
                logger.error("Caught exception while dumping table: {}", e.getMessage)
                logger.error(e.getStackTrace.mkString("\n    at "))
                false
        }
    }
}
