package dev.hoshino.bridge.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class StartResultTest {
    @Test fun frameworkSuccessCodesAreStarted() {
        for (c in 0..3) assertEquals("code $c", StartResult.STARTED, StartResult.ofCode(c))
    }

    @Test fun positiveRefusalCodesAreBlockedNotSuccess() {
        // START_SWITCHES_CANCELED, START_RETURN_LOCK_TASK_MODE_VIOLATION, START_ABORTED (ROM interception)
        for (c in listOf(100, 101, 102)) assertEquals("code $c", StartResult.BLOCKED, StartResult.ofCode(c))
    }

    @Test fun negativeCodesAreErrors() {
        for (c in listOf(-1, -92, -96)) assertEquals("code $c", StartResult.ERROR, StartResult.ofCode(c))
    }

    @Test fun amStartOutput() {
        assertEquals(StartResult.STARTED, StartResult.ofAmOutput(0, "Starting: Intent { cmp=com.tencent.mm/.ui.LauncherUI }\n"))
        assertEquals(StartResult.STARTED, StartResult.ofAmOutput(0, "Starting: Intent { … }\nWarning: Activity not started, its current task has been brought to the front\n"))
        assertEquals(StartResult.BLOCKED, StartResult.ofAmOutput(0, "Starting: Intent { … }\nError: Activity not started, unknown error code 102\n"))
        assertEquals(StartResult.ERROR, StartResult.ofAmOutput(0, "Error: Activity class {x/y} does not exist.\n"))
        assertEquals(StartResult.ERROR, StartResult.ofAmOutput(255, ""))
    }
}
