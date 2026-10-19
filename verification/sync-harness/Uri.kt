package android.net
import java.io.File
class Uri private constructor(private val raw: String, val path: String?) {
    companion object {
        fun fromFile(file: File): Uri = Uri("file://" + file.absolutePath, file.absolutePath)
        fun content(name: String): Uri = Uri("content://provider/$name", null)
    }
    override fun toString(): String = raw
    override fun equals(other: Any?): Boolean = other is Uri && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()
}
