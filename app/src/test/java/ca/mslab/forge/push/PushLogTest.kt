package ca.mslab.forge.push

import org.junit.Assert.*
import org.junit.Test

class PushLogTest {
    // RemoteMessage.PRIORITY_UNKNOWN = 0, PRIORITY_HIGH = 1, PRIORITY_NORMAL = 2.
    @Test fun priorityShowsWhenFcmLoweredIt() {
        assertEquals("high", PushLog.priority(1, 1))
        assertEquals("high→normal", PushLog.priority(1, 2))
        assertEquals("normal", PushLog.priority(2, 2))
        assertEquals("high", PushLog.priority(0, 1))
        assertNull(PushLog.priority(0, 0))
    }
}
