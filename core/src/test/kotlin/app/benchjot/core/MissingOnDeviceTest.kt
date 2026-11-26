package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A library the phone lacked at run time goes on to the server (the web's missingOnDevice). */
class MissingOnDeviceTest {
    @Test fun namesALibraryTheServerHas() {
        assertEquals("openpyxl", missingOnDevice("Traceback…\nModuleNotFoundError: No module named 'openpyxl'"))
        assertEquals("torch", missingOnDevice("ModuleNotFoundError: No module named 'torch.nn'"))
    }

    @Test fun notThePhonesOwnLibrariesTheStandardLibraryOrOtherErrors() {
        assertNull(missingOnDevice("ModuleNotFoundError: No module named 'matplotlib.backends.x'"))
        assertNull(missingOnDevice("ModuleNotFoundError: No module named 'json'"))
        assertNull(missingOnDevice("ZeroDivisionError: division by zero"))
        assertNull(missingOnDevice(null))
    }
}
