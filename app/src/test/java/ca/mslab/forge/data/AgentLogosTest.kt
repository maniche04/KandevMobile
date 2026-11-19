package ca.mslab.forge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentLogosTest {
    @Test fun singlePathLogoUsesRootFill() {
        val icon = parseSvgIcon("""<svg fill="#1A1A1A" fill-rule="evenodd" height="1em" viewBox="0 0 24 24" width="1em" xmlns="http://www.w3.org/2000/svg"><path d="M4 4h16v16H4z"/></svg>""")
        assertNotNull(icon)
        assertEquals(24f, icon!!.viewportWidth)
    }

    @Test fun masksAndClipPathsAreNotDrawnAndAspectIsKept() {
        // Kandev's opencode logo: a full-canvas mask and clip around two real shapes.
        val icon = parseSvgIcon("""<svg width='240' height='300' viewBox='0 0 240 300' fill='none' xmlns='http://www.w3.org/2000/svg'><g clip-path='url(#c)'><mask id='m' style='mask-type:luminance' maskUnits='userSpaceOnUse' x='0' y='0' width='240' height='300'><path d='M240 0H0V300H240V0Z' fill='white'/></mask><g mask='url(#m)'><path d='M180 240H60V120H180V240Z' fill='#CFCECD'/><path d='M180 60H60V240H180V60ZM240 300H0V0H240V300Z' fill='#211E1E'/></g></g><defs><clipPath id='c'><rect width='240' height='300' fill='white'/></clipPath></defs></svg>""")
        assertNotNull(icon)
        assertEquals(2, icon!!.root.size.let { (icon.root[0] as androidx.compose.ui.graphics.vector.VectorGroup).size })
        assertEquals(30f, icon.defaultHeight.value)
    }

    @Test fun multilinePathDataAndCommentsParse() {
        assertNotNull(parseSvgIcon("""<?xml version="1.0"?><svg viewBox="0 0 800 800"><!-- P --><path fill="#fff" d="
            M165 165
            H517 V400 Z"/></svg>"""))
    }

    @Test fun notAnSvgIsNull() { assertNull(parseSvgIcon("""{"error":"not found"}""")) }
}
