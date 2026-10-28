package com.yonzmusic.engine

import android.content.Context
import androidx.annotation.Keep
import com.chaquo.python.Python
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Instantiate once per application acquisition repository, not per Composable.
 * No Python initialization or network work happens until begin() is called.
 */
class YonoEngineClient(
    context: Context,
    private val storage: SafDocumentStore,
    private val runtime: RuntimePaths = RuntimePaths(),
) : Closeable {
    data class RuntimePaths(val quickJs: File? = null, val ffmpeg: File? = null, val ffprobe: File? = null)

    class Task internal constructor(
        val id: String,
        internal val stopped: AtomicBoolean,
        internal val mutableEvents: MutableStateFlow<JSONObject>,
        internal val completion: CompletableDeferred<JSONObject>,
        private val stopAction: () -> Unit,
    ) {
        @Volatile internal var currentEntry=""
        internal val cancelledEntries = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun cancelEntry(id: String) { cancelledEntries.add(id) }
        val events: StateFlow<JSONObject> = mutableEvents.asStateFlow()
        val result: Deferred<JSONObject> = completion

        /** Keep the UI in 'cancelling' until result completes; do not allow a second operation yet. */
        fun cancel() {
            if (!completion.isCompleted && stopped.compareAndSet(false, true)) {
                mutableEvents.value = JSONObject().put("job_id", id).put("stage", "cancelling")
                stopAction()
            }
        }
    }

    private val app = context.applicationContext
    private val root = File(app.filesDir, "yono-engine").apply { mkdirs() }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "yono-engine") }
    private val canceller = Executors.newSingleThreadExecutor { r -> Thread(r, "yono-engine-cancel") }
    private val active = AtomicReference<Task?>(null)
    private val closed = AtomicBoolean(false)

    @Keep
    class Events internal constructor(private val task: Task) {
        @Keep fun isCancelled(): Boolean = task.stopped.get()
        @Keep fun isEntryCancelled(id: String): Boolean = id in task.cancelledEntries
        @Keep fun onEvent(json: String) {
            if (!task.stopped.get()) {
                val value=JSONObject(json)
                if(value.optString("stage")=="track_started")task.currentEntry=value.optString("entry_id")
                task.mutableEvents.value=value
            }
        }
    }

    fun begin(action: String, payload: JSONObject = JSONObject(), timeoutSeconds: Int = 900): Task {
        check(!closed.get()) { "Engine client is closed" }
        val id = UUID.randomUUID().toString()
        val task = Task(id, AtomicBoolean(false), MutableStateFlow(JSONObject().put("stage", "queued").put("job_id", id)),
            CompletableDeferred()) {
            canceller.execute {
                if (Python.isStarted()) runCatching { Python.getInstance().getModule("yono_engine_api").callAttr("cancel", id) }
            }
        }
        check(active.compareAndSet(null, task)) { "The previous engine operation has not stopped" }
        val input = payload.toString() // Freeze before crossing threads.
        worker.execute {
            try {
                storage.setCancellation { task.stopped.get() || task.currentEntry in task.cancelledEntries }
                PythonRuntime.module(app,"yono_engine_api")
                val config = JSONObject().put("job_id", id).put("state_dir", root.canonicalPath)
                    .put("timeout_seconds", timeoutSeconds.coerceIn(5, 7200))
                    .put("network", com.yonzmusic.app.network.AndroidNetworkContext.options(app, "https://music.youtube.com"))
                fun trustedNative(file: File?): String {
                    if (file == null) return ""
                    val native = File(app.applicationInfo.nativeLibraryDir).canonicalFile
                    require(file.canonicalFile.parentFile == native && file.isFile) { "Runtime must be an immutable bundled native executable" }
                    return file.canonicalPath
                }
                val qjs = trustedNative(runtime.quickJs)
                if (qjs.isNotEmpty()) config.put("runtime", JSONObject().put("quickjs", JSONObject().put("path", qjs)))
                config.put("ffmpeg", trustedNative(runtime.ffmpeg)).put("ffprobe", trustedNative(runtime.ffprobe))
                // Never persist this config or include it in user-visible logs.

                val response = Python.getInstance().getModule("yono_engine_api")
                    .callAttr("execute", action, input, config.toString(), Events(task), storage).toString()
                val result = JSONObject(response)
                val stage = if (result.optBoolean("ok")) "done" else result.optJSONObject("error")?.optString("code")?.lowercase() ?: "failed"
                task.mutableEvents.value = JSONObject().put("job_id", id).put("stage", stage).put("response", result)
                active.compareAndSet(task, null)
                task.completion.complete(result)
            } catch (_: Exception) {
                val result = JSONObject().put("ok", false).put("error", JSONObject()
                    .put("code", if (task.stopped.get()) "CANCELLED" else "ANDROID_BRIDGE")
                    .put("message", if (task.stopped.get()) "Dibatalkan." else "Mesin belum dapat dijalankan. Periksa konfigurasi build."))
                task.mutableEvents.value = JSONObject().put("stage", "failed").put("job_id", id).put("response", result)
                active.compareAndSet(task, null)
                task.completion.complete(result)
            } finally {
                storage.setCancellation { false }
                active.compareAndSet(task, null)
            }
        }
        return task
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            active.get()?.cancel()
            worker.shutdown() // Never pretend shutdownNow interrupts a Python/native call.
            canceller.shutdown()
        }
    }

}
