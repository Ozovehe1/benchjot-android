package app.benchjot.core

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The same reading as the web's tests/logged.test.ts. */
class LoggedTest {
    private fun n(x: Number) = JsonPrimitive(x)
    private fun t(x: String) = JsonPrimitive(x)

    @Test fun readsPlainly() {
        assertEquals("0.9612", formatLogged(n(0.961234)))
        assertEquals("20", formatLogged(n(20)))
        assertEquals("12,345", formatLogged(n(12345)))
        assertEquals("1.234e-5", formatLogged(n(0.00001234)))
        assertEquals("true", formatLogged(JsonPrimitive(true)))
        assertEquals("mlp", formatLogged(t("mlp")))
        assertEquals("0.01", formatLogged(n(0.01)))
        assertEquals("", formatLogged(null))
    }

    @Test fun tableColumnsNumbersAndDifferences() {
        val runs = listOf(
            mapOf("accuracy" to n(0.961), "lr" to n(0.01), "model" to t("mlp")),
            mapOf("accuracy" to n(0.912), "lr" to n(0.1), "model" to t("mlp"), "epochs" to n(10)),
            mapOf("lr" to n(0.3), "model" to t("linear")),
        )
        assertEquals(listOf("accuracy", "lr", "model", "epochs"), loggedColumns(runs))
        assertEquals(true, numericColumn(runs, "accuracy"))
        assertEquals(false, numericColumn(runs, "model"))
        assertEquals(listOf("accuracy", "lr", "epochs"), differences(runs[0], runs[1]))
    }

    @Test fun chartOpensOnAResult() {
        assertEquals("accuracy", chartDefault(listOf("lr", "epochs", "accuracy", "loss")))
        assertEquals("val_loss", chartDefault(listOf("lr", "val_loss")))
        assertEquals("lr", chartDefault(listOf("lr", "epochs")))
        assertNull(chartDefault(emptyList()))
    }

    @Test fun keepsOnlyWhatTheWebKeeps() {
        assertNull(cleanLogged(emptyMap()))
        assertEquals(mapOf("a" to n(1)), cleanLogged(mapOf("a" to n(1), "" to n(2), "x".repeat(61) to n(3), "long" to t("y".repeat(201)))))
    }
}
