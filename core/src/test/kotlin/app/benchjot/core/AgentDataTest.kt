package app.benchjot.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Data library tools on the phone, answered as the web answers them (tests/agent-fixes.test.ts). */
class AgentDataTest {
    private class Lib(private val fail: String? = null) : AgentData {
        val saved = mutableListOf<String>()
        override suspend fun list() = listOf("iris.csv" to 4551L)
        override suspend fun save(url: String, name: String?): Pair<String, Long> { fail?.let { throw Exception(it) }; saved += url; return (name ?: "x.csv") to 2_000_000L }
        override suspend fun find(query: String): DatasetFind =
            if (":" in query) DatasetFind.Files(query, listOf(FoundData("titanic.csv", "https://zenodo.org/api/records/1/files/titanic.csv/content", 44385), FoundData("big.parquet", "https://x/big.parquet", 80_000_000)))
            else DatasetFind.Datasets(listOf(FoundRef("zenodo:1", "Titanic", "Zenodo", "107 downloads")), listOf("figshare"))
    }

    private fun noteWith(lib: AgentData?) = object : AgentNote {
        override suspend fun read() = Triple("t", "body", emptyList<Cell>())
        override suspend fun add(language: String, code: String) = "x"
        override suspend fun setCode(id: String, code: String) {}
        override suspend fun run(id: String, where: String?) {}
        override suspend fun remove(id: String) {}
        override suspend fun setBody(text: String) {}
        override val data = lib
    }

    @Test fun listSaveAndFind() = runTest {
        val lib = Lib()
        val note = noteWith(lib)
        assertTrue(makeCall(AgentCall("list_data"), note, AgentUndo()).first.contains("- iris.csv (4 KB)"))
        val saved = makeCall(AgentCall("save_data", url = "https://x.org/wine.csv", name = "wine.csv"), note, AgentUndo())
        assertEquals(listOf("https://x.org/wine.csv"), lib.saved)
        assertTrue(saved.first.startsWith("Saved wine.csv (1.9 MB)"))
        val found = makeCall(AgentCall("find_datasets", query = "titanic"), note, AgentUndo()).first
        assertTrue(found.contains("- zenodo:1 — Titanic (Zenodo; 107 downloads)"))
        assertTrue(found.contains("figshare didn't answer this time"))
        val files = makeCall(AgentCall("find_datasets", query = "zenodo:1"), note, AgentUndo()).first
        assertTrue(files.contains("titanic.csv — 43 KB — https://zenodo.org/api/records/1/files/titanic.csv/content"))
        assertTrue(files.contains("(over 50 MB — load it in a server cell instead)"))
    }

    @Test fun refusedOrUnreachable() = runTest {
        val refused = makeCall(AgentCall("save_data", url = "http://x.org/a.csv"), noteWith(Lib("Only https:// addresses can be added.")), AgentUndo())
        assertTrue(refused.first.contains("Only https:// addresses can be added."))
        assertEquals("fail", refused.second.tone)
        assertEquals("The Data library can't be reached here.", makeCall(AgentCall("list_data"), noteWith(null), AgentUndo()).first)
    }
}

class NotDataTest {
    @org.junit.Test fun refusesPagesKeepsDataFiles() {
        org.junit.Assert.assertTrue(notData("https://github.com/x/y/blob/main/README.md"))
        org.junit.Assert.assertTrue(notData("https://github.com/x/y"))
        org.junit.Assert.assertTrue(notData("https://example.org/data.html"))
        org.junit.Assert.assertTrue(notData("https://example.org/f", "magnet_README.md"))
        org.junit.Assert.assertFalse(notData("https://raw.githubusercontent.com/x/y/main/data.csv"))
        org.junit.Assert.assertFalse(notData("https://github.com/x/y/releases/download/v1/data.zip"))
        org.junit.Assert.assertFalse(notData("https://zenodo.org/records/1/files/N87.h5?download=1"))
    }
}
