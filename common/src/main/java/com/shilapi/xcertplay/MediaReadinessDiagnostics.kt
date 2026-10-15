package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Looper
import android.os.Process
import java.util.concurrent.TimeUnit

/** Read-only observations. Never binds an extra OEM service or exports logcat/error text. */
internal object MediaReadinessDiagnostics {
    fun environment(context: Context, emit: (String) -> Unit) {
        fun observe(name: String, work: () -> String) {
            val value = try { work() } catch (e: Exception) { "unavailable failureClass=${e.javaClass.simpleName}" }
            runCatching { emit("OEM media diagnostic $name $value") }
        }
        observe("caller") {
            val own = context.packageManager.getPackageInfo(context.packageName, 0)
            "buildCode=${own.longVersionCodeCompat()} applicationContext=${context === context.applicationContext} " +
                "mainThread=${Looper.myLooper() === Looper.getMainLooper()} " +
                "systemApp=${(own.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0} " +
                "mediaPermission=${context.checkSelfPermission("ecarx.oem.permission.OPENAPI_MEDIACENTER_PERMISSION") == PackageManager.PERMISSION_GRANTED}"
        }
        for ((label, pkg) in listOf("openapi" to "com.ecarx.sdk.openapi", "mediacenter" to "ecarx.xsf.mediacenter")) {
            observe("package=$label") {
                val info = context.packageManager.getPackageInfo(pkg, 0)
                val app = info.applicationInfo
                "visible=true buildCode=${info.longVersionCodeCompat()} enabled=${app?.enabled} " +
                    "stopped=${(app?.flags ?: 0) and ApplicationInfo.FLAG_STOPPED != 0} " +
                    "systemApp=${(app?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0}"
            }
        }
        for ((label, pkg, action) in listOf(
            Triple("openapi", "com.ecarx.sdk.openapi", "ecarx.intent.action.OpenAPIService"),
            Triple("media-v3", "ecarx.xsf.mediacenter", "ecarx.xsf.MEDIA_CENTER_SERVICE"),
            Triple("media-v4", "ecarx.xsf.mediacenter", "ecarx.xsf.mediacenter.intent.action.EAS_SUPPORT_SERVICE"))) {
            observe("service=$label") {
                val service = context.packageManager.resolveService(Intent(action).setPackage(pkg), 0)?.serviceInfo
                if (service == null) "resolved=false queryOnly=true absenceNotProven=true"
                else "resolved=true enabled=${service.enabled} exported=${service.exported} " +
                    "permissionRequired=${!service.permission.isNullOrBlank()} " +
                    "permissionGranted=${service.permission?.takeIf { it.isNotBlank() }?.let {
                        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ?: true} queryOnly=true"
            }
        }
    }

    fun failure(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val reason = chain.firstNotNullOfOrNull { category(it.message.orEmpty()) } ?: "unknown"
        return "failureClass=${chain.last().javaClass.simpleName} reason=$reason causes=${chain.size}"
    }

    /** Fixed categories only: arbitrary exception strings, names and authorization data stay local. */
    internal fun category(text: String): String? {
        val value = text.lowercase()
        return when {
            "no authorization to operate" in value -> "caller_not_authorized"
            "service[" in value && "not ready" in value -> "target_service_not_ready"
            "signature is inconsistent" in value || "app sign match failure" in value -> "signature_check_failed"
            "permission denial" in value || "permission denied" in value -> "permission_denied"
            "deadobjectexception" in value -> "binder_dead"
            "could not bind" in value || "unable to bind" in value -> "service_bind_failed"
            else -> null
        }
    }

    internal fun sdkLogSummary(lines: String): String {
        val categories = lines.lineSequence().take(160).filter {
            // Ignore our own summaries; a category is an observation, not a proven ready=false cause.
            !it.contains("OEM media") && (it.contains("mediacenter", true) || it.contains("ECarX", true))
        }.mapNotNull(::category).toSet()
        return "scope=own_process vendorProcess=unavailable categories=${categories.joinToString(",").ifEmpty { "none" }} " +
            "causeProven=false"
    }

    fun sdkLogs(emit: (String) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 26) {
            runCatching { emit("OEM media diagnostic sdkLog unavailable reason=bounded_process_wait_unsupported scope=own_process vendorProcess=unavailable") }
            return
        }
        var process: java.lang.Process? = null
        val summary = try {
            process = ProcessBuilder("/system/bin/logcat", "-d", "-t", "160", "--pid=${Process.myPid()}", "-v", "brief")
                .redirectErrorStream(true).start()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                "unavailable reason=read_timeout scope=own_process vendorProcess=unavailable"
            } else if (process.exitValue() != 0) {
                "unavailable reason=read_failed scope=own_process vendorProcess=unavailable"
            } else {
                val bytes = process.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024 + 1)
                    var used = 0
                    while (used < buffer.size) {
                        val size = input.read(buffer, used, buffer.size - used)
                        if (size < 0) break
                        used += size
                    }
                    buffer.copyOf(used)
                }
                if (bytes.size > 64 * 1024) "unavailable reason=read_limit scope=own_process vendorProcess=unavailable"
                else sdkLogSummary(bytes.toString(Charsets.UTF_8))
            }
        } catch (e: Exception) { "unavailable failureClass=${e.javaClass.simpleName} scope=own_process vendorProcess=unavailable" }
        finally { process?.destroy(); runCatching { process?.inputStream?.close() } }
        runCatching { emit("OEM media diagnostic sdkLog $summary") }
    }
}

@Suppress("DEPRECATION")
internal fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
    if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
