package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NavTest {
    @Test fun deletingAFiledNoteReturnsToItsProject() {
        assertEquals("project/p1", afterDeleteRoute("p1"))
    }

    @Test fun deletingAnUnfiledNoteReturnsHome() {
        assertEquals(HOME_ROUTE, afterDeleteRoute(null))
        assertEquals(HOME_ROUTE, afterDeleteRoute(""))
    }

    @Test fun backgroundingKeepsTheSignedInTree() {
        // Authenticated -> Initializing (app backgrounded) -> Authenticated (back in front)
        var gate = nextGate(Gate.Loading, true)
        gate = nextGate(gate, null)
        assertEquals(Gate.SignedIn, gate)
        assertEquals(Gate.SignedIn, nextGate(gate, true))
    }

    @Test fun signOutAndColdStart() {
        assertEquals(Gate.SignedOut, nextGate(Gate.SignedIn, false))
        assertEquals(Gate.Loading, nextGate(Gate.Loading, null))
    }
}
