package app.benchjot.engine

import app.benchjot.core.RuffDiagnostic
import app.benchjot.core.RuffSettings
import app.benchjot.core.parseRuffOutput
import app.benchjot.core.ruffFixAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ruff, the same version the web loads as WASM, as its official static Linux
 * binary (musl, so it needs nothing from the system). On Android it ships as
 * lib/<abi>/libruff.so so the package manager unpacks it, executable, into the
 * app's native library directory. One short process per check, stdin to stdout.
 */
class RuffProcess(private val binary: File, private val timeoutMs: Long = 10_000) {
    val available: Boolean get() = binary.canExecute()

    suspend fun check(code: String): List<RuffDiagnostic> = withContext(Dispatchers.IO) {
        check(available) { "Ruff is not available on this device" }
        val p = ProcessBuilder(listOf(binary.absolutePath) + RuffSettings.checkArgs())
            .redirectErrorStream(false)
            .apply { environment()["NO_COLOR"] = "1" }
            .start()
        val errors = StringBuilder()
        val stderr = Thread { errors.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
        p.outputStream.bufferedWriter().use { it.write(code) }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            error("Ruff took too long")
        }
        stderr.join(1_000)
        if (p.exitValue() != 0) error("Ruff failed (${p.exitValue()}): ${errors.toString().trim().take(300)}")
        parseRuffOutput(out)
    }

    suspend fun fixAll(code: String): String = ruffFixAll(code, ::check)
}
