package com.dimajix.dataflow.cli

import org.kohsuke.args4j.CmdLineParser
import org.kohsuke.args4j.Option


abstract class Command {
    @Option(name = "-h", aliases=Array("--help"), usage = "show help")
    var help: Boolean = false

    def execute(options:Arguments) : Boolean = {
        if (help) {
            new CmdLineParser(this).printUsage(System.err)
            System.err.println
            System.exit(1)
        }

        true
    }
}
