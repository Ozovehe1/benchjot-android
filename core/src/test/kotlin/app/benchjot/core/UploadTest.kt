package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadTest {
    @Test fun textFiles() {
        val r = prepareUpload("train.py", "\uFEFFprint(1)\r\n".toByteArray())
        assertEquals(UploadResult.Ok(CodeFile("train.py", "python", "print(1)\n")), r)
    }

    @Test fun binaryAndLarge() {
        assertEquals(UploadResult.Failed("a.bin isn’t a text file."), prepareUpload("a.bin", byteArrayOf(1, 0, 2)))
        assertEquals(UploadResult.Failed("big.txt is over 1 MB."), prepareUpload("big.txt", ByteArray(MAX_UPLOAD_BYTES + 1) { 'a'.code.toByte() }))
    }

    @Test fun notebooks() {
        val nb = """{"cells":[{"cell_type":"markdown","source":["# t"]},{"cell_type":"code","source":["a = 1\n","print(a)\n"]},{"cell_type":"code","source":"b = 2"}]}"""
        val r = prepareUpload("n.ipynb", nb.toByteArray()) as UploadResult.Ok
        assertEquals("a = 1\nprint(a)\n\n# %%\nb = 2", r.file.content)
        assertEquals("python", r.file.language)
        assertTrue(prepareUpload("bad.ipynb", "nope".toByteArray()) is UploadResult.Failed)
    }

    @Test fun languageFromContentWhenNoExtension() {
        assertEquals("json", (prepareUpload("data", """{"a": 1}""".toByteArray()) as UploadResult.Ok).file.language)
    }
}
