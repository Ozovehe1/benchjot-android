package app.benchjot.run

import org.junit.Assert.assertEquals
import org.junit.Test

class PyProtocolTest {
    @Test fun renderErrorsShowOnlyTheUsersMessage() {
        // Chaquopy's PyException message is "<type>: <message>", after the traceback when it has one.
        assertEquals("made with matplotlib 3.8", PyProtocol.renderErrorText("RenderError: made with matplotlib 3.8"))
        assertEquals("made with matplotlib 3.8", PyProtocol.renderErrorText("Traceback (most recent call last):\n  File \"x\"\nbj_capture.RenderError: made with matplotlib 3.8\n"))
        // Anything else is not for the user: the app says the file couldn't be made.
        assertEquals("", PyProtocol.renderErrorText("MemoryError: "))
        assertEquals("", PyProtocol.renderErrorText(""))
    }
}
