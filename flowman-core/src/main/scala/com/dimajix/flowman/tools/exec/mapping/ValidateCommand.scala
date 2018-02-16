package com.dimajix.flowman.tools.exec.mapping

import com.dimajix.flowman.execution.Executor
import com.dimajix.flowman.spec.Project
import com.dimajix.flowman.tools.exec.ActionCommand


class ValidateCommand extends ActionCommand {
    override def executeInternal(executor:Executor, project: Project) : Boolean = {
        false
    }
}
