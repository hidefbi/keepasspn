package android.content
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import android.net.Uri

class ContentResolver(private val originalDocument: File) {
    private fun fileFor(uri: Uri): File =
        if (uri.path != null) File(uri.path) else originalDocument
    fun openInputStream(uri: Uri): InputStream? = FileInputStream(fileFor(uri))
    fun openOutputStream(uri: Uri, mode: String): OutputStream? {
        check(mode == "wt") { "unexpected mode $mode" }
        return FileOutputStream(fileFor(uri))
    }
}

class Context(val filesDir: File, val contentResolver: ContentResolver) {
    val applicationContext: Context get() = this
}
