package com.orangetiger.otstudio

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Chooses which llama.cpp build runs on this phone. The APK carries two: the standard arm64 build from the
 * llama-android library (libjllama.so) and a faster build that uses the dot-product and int8 matrix
 * instructions of recent ARM cores (libjllama_i8mm.so, built by scripts/build-engine.ps1).
 *
 * The faster build would crash a phone without those instructions, so it is used only when the processor
 * reports both. If the app stops twice in a row while the fast build works on a reply, the next start
 * falls back to the standard build for good. (Once could be the user closing the app.)
 */
object NativeEngine {
    const val FAST = "fast"
    const val STANDARD = "standard"
    private const val LIB_PATH_PROPERTY = "net.ladenthin.llama.lib.path"
    @Volatile var chosen: String = STANDARD
        private set
    private var prepared = false

    /** Parses the Features lines of /proc/cpuinfo. Every core must report both features. */
    fun supportsFast(cpuinfo: String): Boolean {
        val lines = cpuinfo.lines().filter { it.trim().startsWith("Features") }
        return lines.isNotEmpty() && lines.all { line -> val f = line.substringAfter(':').trim().split(Regex("\\s+")).toSet(); "asimddp" in f && "i8mm" in f }
    }

    /** Must run before the first model load in this process. */
    @Synchronized fun prepare(context: Context) {
        if (prepared) return
        prepared = true
        val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
        if (prefs.getInt("unfinished", 0) >= 2) prefs.edit().putBoolean("fast_failed", true).remove("unfinished").commit()
        val fastLib = File(context.applicationInfo.nativeLibraryDir, "libjllama_i8mm.so")
        val cpuinfo = try { File("/proc/cpuinfo").readText() } catch (_: Exception) { "" }
        val useFast = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && fastLib.isFile && !prefs.getBoolean("fast_failed", false) && supportsFast(cpuinfo)
        if (!useFast) return
        try {
            // The library loader looks for a file named libjllama.so in a chosen folder. A link points it at the fast build.
            val dir = File(context.noBackupFilesDir, "engine").apply { mkdirs() }
            val link = File(dir, "libjllama.so")
            link.delete()
            android.system.Os.symlink(fastLib.path, link.path)
            System.setProperty(LIB_PATH_PROPERTY, dir.path)
            chosen = FAST
        } catch (_: Exception) { chosen = STANDARD }
    }

    /** Brackets each model load and reply. A native crash skips [end], and the count survives the restart. */
    fun begin(context: Context) {
        if (chosen != FAST) return
        val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
        prefs.edit().putInt("unfinished", prefs.getInt("unfinished", 0) + 1).commit()
    }
    fun end(context: Context) {
        if (chosen == FAST) context.getSharedPreferences("engine", Context.MODE_PRIVATE).edit().remove("unfinished").commit()
    }
}
