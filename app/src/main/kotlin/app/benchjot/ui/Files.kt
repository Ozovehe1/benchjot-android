package app.benchjot.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.benchjot.core.CodeFile
import app.benchjot.core.MAX_UPLOAD_BYTES
import app.benchjot.core.UploadResult
import app.benchjot.core.prepareUpload
import app.benchjot.core.tooLarge

// "Upload file" and "Download", the Android way (the system file picker), with the
// web's upload rules (core prepareUpload: 1 MB, text only, notebooks, language).

private fun Context.displayName(uri: Uri): String =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()

private fun Context.size(uri: Uri): Long =
    contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
    } ?: -1L

/** Returns a function that opens the picker; chosen files arrive as code blocks, problems as one message. */
@Composable
fun rememberFilePicker(onError: (String) -> Unit, onFiles: (List<CodeFile>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val files by rememberUpdatedState(onFiles)
    val errors by rememberUpdatedState(onError)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val ok = mutableListOf<CodeFile>()
        val problems = mutableListOf<String>()
        for (uri in uris) {
            val name = context.displayName(uri)
            val r = if (context.size(uri) > MAX_UPLOAD_BYTES) tooLarge(name) else {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                prepareUpload(name, bytes)
            }
            when (r) {
                is UploadResult.Ok -> ok += r.file
                is UploadResult.Failed -> problems += r.error
            }
        }
        if (problems.isNotEmpty()) errors(problems.joinToString(" "))
        if (ok.isNotEmpty()) files(ok)
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/** Returns a function that saves `content` under a suggested name, via the system "save as". */
@Composable
fun rememberDownloader(): (String, String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pending
        if (uri != null && text != null) context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        pending = null
    }
    return { name, content -> pending = content; launcher.launch(name) }
}
