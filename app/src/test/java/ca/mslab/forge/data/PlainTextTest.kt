package ca.mslab.forge.data

import ca.mslab.forge.notify.plainText
import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextTest {
    @Test fun notificationsShowReadableText() {
        val markdown = "I created the chain: **F → G** with `main`.\n\n| Task | Branch |\n|---|---|\n| Phase G | `phase-g` |\n\n- First\n## Next\nSee [docs](https://x.y)."
        assertEquals("I created the chain: F → G with main.\n\nTask · Branch\nPhase G · phase-g\n\n• First\nNext\nSee docs.",
            plainText(markdown))
    }
}
