package harness

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import com.keepasspn.utils.LocalDatabaseBackupManager as Manager
import java.io.File

var failures = 0
var passes = 0
fun check(label: String, ok: Boolean) {
    if (ok) passes++ else { failures++; println("  FAIL: $label") }
    println("  ${if (ok) "ok  " else "FAIL"} $label")
}

class Env(root: File) {
    val original = File(root, "original.kdbx")
    val context = Context(File(root, "files").apply { mkdirs() }, ContentResolver(original))
    val logical: Uri = Uri.content("db")
    fun write(text: String) = original.writeText(text)
    fun read(): String = original.readText()
    fun backupPath(): File = File(context.filesDir, "database_backups")
    fun backupUri(): Uri = Manager.backupUri(context, logical)
    fun backupText(): String? = File(backupUri().path!!).takeIf { it.isFile }?.readText()
    fun unavailable(): Boolean = original.renameTo(File(original.path + ".hidden")).also { }
    fun restore() { File(original.path + ".hidden").renameTo(original) }
    fun conflicts(): List<String> = backupPath().listFiles()
        ?.filter { it.name.contains(".conflict-") }?.map { it.readText() }.orEmpty()
}

fun scenarioOfflineEditsSurviveStaleOriginal() {
    println("Scenario A: offline edits must survive a stale original (reported data-loss case)")
    val env = Env(createTempDirectory().toFile())
    env.write("v1")
    // First successful load from the original creates the private copy
    check("first load uses original", Manager.resolveLoadUri(env.context, env.logical) == env.logical)
    Manager.syncFromUri(env.context, env.logical, env.logical)
    check("copy created with v1", env.backupText() == "v1")

    // Original vanishes; user loads the copy and saves an offline edit into it
    env.original.renameTo(File(env.original.path + ".hidden"))
    check("fallback to copy while original missing", Manager.resolveLoadUri(env.context, env.logical) == env.backupUri())
    File(env.backupUri().path!!).writeText("v2-offline")
    check("sync of offline save cannot push (original missing)", !Manager.syncFromUri(env.context, env.logical, env.backupUri()))

    // Original returns with the STALE v1 content
    env.restore()
    env.write("v1")
    val resolved = Manager.resolveLoadUri(env.context, env.logical)
    check("stale original does NOT win over offline copy", resolved == env.backupUri())
    check("offline copy content intact after resolve", env.backupText() == "v2-offline")

    // Loading the copy pushes it back to the original (original clean)
    check("push back to original succeeds", Manager.syncFromUri(env.context, env.logical, env.backupUri()))
    check("original now holds offline edits", env.read() == "v2-offline")
    check("next launch uses original again", Manager.resolveLoadUri(env.context, env.logical) == env.logical)
}

fun scenarioBothChangedArchivesInsteadOfOverwriting() {
    println("Scenario B: both sides changed -> copy archived as conflict, not overwritten")
    val env = Env(createTempDirectory().toFile())
    env.write("v1")
    Manager.resolveLoadUri(env.context, env.logical)
    Manager.syncFromUri(env.context, env.logical, env.logical)
    env.original.renameTo(File(env.original.path + ".hidden"))
    Manager.resolveLoadUri(env.context, env.logical)
    File(env.backupUri().path!!).writeText("v2-offline")
    env.restore()
    env.write("v3-other-device")
    check("resolve loads original on conflict", Manager.resolveLoadUri(env.context, env.logical) == env.logical)
    check("offline edit preserved as conflict copy", env.conflicts() == listOf("v2-offline"))
    Manager.syncFromUri(env.context, env.logical, env.logical)
    check("copy refreshed from original after conflict", env.backupText() == "v3-other-device")
}

fun scenarioNormalExternalUpdate() {
    println("Scenario C: original updated externally, copy clean -> original used, copy refreshed")
    val env = Env(createTempDirectory().toFile())
    env.write("v1")
    Manager.resolveLoadUri(env.context, env.logical)
    Manager.syncFromUri(env.context, env.logical, env.logical)
    env.write("v4-external")
    check("original used", Manager.resolveLoadUri(env.context, env.logical) == env.logical)
    Manager.syncFromUri(env.context, env.logical, env.logical)
    check("copy refreshed", env.backupText() == "v4-external")
    check("no conflict copy created", env.conflicts().isEmpty())
}

fun scenarioLegacyCopyWithoutSyncStateIsNotSilentlyOverwritten() {
    println("Scenario D: legacy copy (no sync state) that differs -> archived, not overwritten")
    val env = Env(createTempDirectory().toFile())
    env.write("v1")
    env.backupPath().mkdirs()
    File(env.backupUri().path!!).writeText("legacy-offline")
    check("original loaded", Manager.resolveLoadUri(env.context, env.logical) == env.logical)
    check("legacy copy archived", env.conflicts() == listOf("legacy-offline"))
}

fun scenarioConflictCopiesArePruned() {
    println("Scenario E: conflict copies are pruned to the most recent three")
    val env = Env(createTempDirectory().toFile())
    env.write("v1")
    Manager.resolveLoadUri(env.context, env.logical)
    Manager.syncFromUri(env.context, env.logical, env.logical)
    for (i in 1..5) {
        env.original.renameTo(File(env.original.path + ".hidden"))
        Manager.resolveLoadUri(env.context, env.logical)
        File(env.backupUri().path!!).writeText("offline-$i")
        env.restore()
        env.write("external-$i")
        Manager.resolveLoadUri(env.context, env.logical)
        Manager.syncFromUri(env.context, env.logical, env.logical)
        Thread.sleep(3)
    }
    check("only 3 conflict copies kept", env.conflicts().size == 3)
    check("kept copies are the newest", env.conflicts().toSet() == setOf("offline-3", "offline-4", "offline-5"))
}

fun scenarioDeleteRemovesEverything() {
    println("Scenario F: deleting a database removes copy, sync state and conflicts")
    val env = Env(createTempDirectory().toFile())
    env.write("v1")
    Manager.resolveLoadUri(env.context, env.logical)
    Manager.syncFromUri(env.context, env.logical, env.logical)
    check("delete succeeds", Manager.deleteBackup(env.context, env.logical))
    check("no backup left", !Manager.hasBackup(env.context, env.logical))
    check("no files left in directory", env.backupPath().listFiles()?.isEmpty() ?: true)
}

fun main(args: Array<String>) {
    // Pure-logic unit tests: run the real test classes with the junit shim
    for (name in listOf(
        "com.keepasspn.tests.pin.PinEnvelopeTest",
        "com.keepasspn.tests.backup.BackupSyncPolicyTest"
    )) {
        val clazz = Class.forName(name)
        println("Unit: ${clazz.simpleName}")
        for (method in clazz.declaredMethods.sortedBy { it.name }) {
            if (method.getAnnotation(org.junit.Test::class.java) == null) continue
            try {
                method.invoke(clazz.getDeclaredConstructor().newInstance())
                check(method.name, true)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                check("${method.name}: ${e.cause}", false)
            }
        }
    }
    scenarioOfflineEditsSurviveStaleOriginal()
    scenarioBothChangedArchivesInsteadOfOverwriting()
    scenarioNormalExternalUpdate()
    scenarioLegacyCopyWithoutSyncStateIsNotSilentlyOverwritten()
    scenarioConflictCopiesArePruned()
    scenarioDeleteRemovesEverything()
    println("\nRESULT: $passes passed, $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}

fun createTempDirectory(): java.nio.file.Path = java.nio.file.Files.createTempDirectory("kdbx-harness")
