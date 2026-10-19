package com.rex.twboardingscanner.ui

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Durable one-stock/one-consent capture manifest, independent of the Activity lifecycle. */
class ChipKSevenPageStore(context: Context) {
    data class PageState(
        val page: ChipKPage,
        val state: String,
        val file: String,
        val verification: String,
        val error: String
    )
    data class Snapshot(
        val id: String,
        val code: String,
        val active: Boolean,
        val state: String,
        val index: Int,
        val readyIndex: Int,
        val readyAt: Long,
        val seenAt: Long,
        val pages: List<PageState>,
        val error: String
    ) {
        val count: Int get() = pages.count { it.state == "captured" }
        val failed: Int get() = pages.count { it.state == "failed" }
    }
    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences("chipk-seven-page", Context.MODE_PRIVATE)
    private val root: File get() = File(app.filesDir, "chipk-batches").apply { mkdirs() }

    @Synchronized fun start(code: String): Snapshot {
        require(code.matches(Regex("[0-9]{4,6}")))
        val id = code + "_" + System.currentTimeMillis()
        File(root, id).mkdirs()
        prefs.edit().clear().putString("id", id).putString("code", code)
            .putBoolean("active", true).putString("state", "running")
            .putInt("index", 0).putInt("ready_index", -1).putLong("ready_at", 0L)
            .putLong("seen_at", 0L).commit()
        pruneOldRuns()
        return snapshot()!!.also { writeReport(it) }
    }

    @Synchronized fun snapshot(): Snapshot? {
        val id = prefs.getString("id", "").orEmpty()
        val code = prefs.getString("code", "").orEmpty()
        if (!id.matches(Regex("[0-9]{4,6}_[0-9]+")) || !code.matches(Regex("[0-9]{4,6}")))
            return null
        val pages = ChipKPage.ordered.map { page ->
            PageState(
                page,
                prefs.getString("status_${page.slug}", "pending").orEmpty(),
                prefs.getString("file_${page.slug}", "").orEmpty(),
                prefs.getString("verify_${page.slug}", "").orEmpty(),
                prefs.getString("error_${page.slug}", "").orEmpty()
            )
        }
        return Snapshot(id, code, prefs.getBoolean("active", false),
            prefs.getString("state", "stopped").orEmpty(),
            prefs.getInt("index", 0), prefs.getInt("ready_index", -1),
            prefs.getLong("ready_at", 0L), prefs.getLong("seen_at", 0L),
            pages, prefs.getString("error", "").orEmpty())
    }

    @Synchronized fun requestNavigation(index: Int) {
        val s = snapshot() ?: return
        if (!s.active || s.index != index || s.readyIndex == index) return
        val page = ChipKPage.at(index) ?: return
        prefs.edit().putString("status_${page.slug}", "navigating").commit()
    }

    /** Only the official ChipK accessibility helper may acknowledge a visible page. */
    @Synchronized fun ready(index: Int, evidence: String) {
        val s = snapshot() ?: return
        if (!s.active || s.index != index || evidence !in setOf("selected_tab", "changed_unverified")) return
        val now = System.currentTimeMillis()
        val page = ChipKPage.at(index) ?: return
        val firstReady = s.readyIndex != index
        val editor = prefs.edit()
            .putString("status_${page.slug}", "ready")
            .putString("verify_${page.slug}", evidence)
            .putInt("ready_index", index).putLong("seen_at", now)
        if (firstReady) editor.putLong("ready_at", now)
        editor.commit()
    }

    @Synchronized fun seen(index: Int) {
        val s = snapshot() ?: return
        if (s.active && s.readyIndex == index && s.index == index)
            prefs.edit().putLong("seen_at", System.currentTimeMillis()).commit()
    }

    @Synchronized fun complete(index: Int, file: File, verification: String) {
        val s = snapshot() ?: return
        val page = ChipKPage.at(index) ?: return
        if (!s.active || s.index != index || s.readyIndex != index ||
            file.parentFile?.canonicalPath != dirFor(s).canonicalPath || !file.isFile) return
        prefs.edit()
            .putString("status_${page.slug}", "captured")
            .putString("file_${page.slug}", file.name)
            .putString("verify_${page.slug}", verification)
            .putInt("index", index + 1)
            .putInt("ready_index", -1)
            .putLong("ready_at", 0L)
            .putLong("seen_at", 0L)
            .apply {
                if (index == ChipKPage.ordered.lastIndex) {
                    putBoolean("active", false)
                    putString("state", if (s.failed > 0) "complete_with_failures" else "complete")
                }
            }.commit()
        snapshot()?.let(::writeReport)
    }

    @Synchronized fun skip(index: Int, reason: String) {
        val s = snapshot() ?: return
        if (!s.active || s.index != index) return
        val page = ChipKPage.at(index) ?: return
        prefs.edit()
            .putString("status_${page.slug}", "failed")
            .putString("error_${page.slug}", reason.take(300))
            .putInt("index", index + 1)
            .putInt("ready_index", -1)
            .putLong("ready_at", 0L).putLong("seen_at", 0L)
            .apply {
                if (index == ChipKPage.ordered.lastIndex) {
                    putBoolean("active", false)
                    putString("state", "complete_with_failures")
                }
            }.commit()
        snapshot()?.let(::writeReport)
    }

    @Synchronized fun stop(reason: String) {
        val s = snapshot() ?: return
        if (s.active) {
            prefs.edit().putBoolean("active", false)
                .putString("state", "stopped").putString("error", reason.take(300))
                .putInt("ready_index", -1).commit()
        }
        snapshot()?.let(::writeReport)
    }

    fun dirFor(snapshot: Snapshot): File {
        require(snapshot.id.matches(Regex("[0-9]{4,6}_[0-9]+")))
        return File(root, snapshot.id).apply { mkdirs() }
    }

    fun fileFor(snapshot: Snapshot, page: ChipKPage): File =
        File(dirFor(snapshot), String.format(Locale.US, "%s_%02d_%s.jpg",
            snapshot.code, page.number, page.slug))

    fun capturedFiles(snapshot: Snapshot): List<File> =
        snapshot.pages.filter { it.state == "captured" && it.file.matches(Regex("[a-zA-Z0-9_.-]+")) }
            .map { File(dirFor(snapshot), it.file) }.filter { it.isFile }

    @Synchronized private fun writeReport(snapshot: Snapshot) {
        val pages = JSONArray()
        snapshot.pages.forEach { page ->
            pages.put(JSONObject().put("key", page.page.slug)
                .put("title", page.page.title)
                .put("status", page.state)
                .put("verification", page.verification)
                .put("filename", page.file)
                .put("error", page.error))
        }
        val metadata = JSONObject()
            .put("stock_code", snapshot.code)
            .put("capture_id", snapshot.id)
            .put("state", snapshot.state)
            .put("captured", snapshot.count)
            .put("total", ChipKPage.ordered.size)
            .put("pages", pages)
            .put("warning", "此為螢幕截圖；changed_unverified 僅證明畫面變動，不證明頁籤內容正確。")
        val folder = dirFor(snapshot)
        val tmp = File(folder, "manifest.json.tmp")
        tmp.writeText(metadata.toString(2))
        if (!tmp.renameTo(File(folder, "manifest.json"))) {
            File(folder, "manifest.json").writeText(metadata.toString(2))
            tmp.delete()
        }
    }

    @Synchronized private fun pruneOldRuns() {
        root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
            ?.drop(12)?.forEach { it.deleteRecursively() }
    }
}
