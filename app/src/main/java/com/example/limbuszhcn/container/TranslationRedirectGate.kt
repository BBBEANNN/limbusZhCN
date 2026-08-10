package com.example.limbuszhcn.container

import android.content.Context
import android.util.Log
import com.lody.virtual.client.ipc.VActivityManager
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One-shot handoff from the host app to the running Limbus virtual process. */
class TranslationRedirectGate(context: Context) {
    private val root = context.applicationContext.filesDir.toPath().resolve(TRANSLATION_CACHE_DIR)
    private val gate = root.resolve(GATE_FILE)

    fun close(): Boolean {
        val deleted = Files.deleteIfExists(gate)
        Log.i(TAG, "Closed translation redirect gate path=$gate deleted=$deleted")
        return deleted
    }

    fun openForRunningGame(): Int {
        val processes = VActivityManager.get()
            .getRunningAppProcesses(GAME_PACKAGE, 0)
            .filter { it.pid > 0 && it.packageName == GAME_PACKAGE }
        val process = processes.firstOrNull { it.processName == GAME_PACKAGE }
            ?: processes.singleOrNull()
            ?: error("Expected one running Limbus process, found ${processes.map { "${it.processName}:${it.pid}" }}")

        Files.createDirectories(root)
        val temporary = root.resolve("$GATE_FILE.tmp")
        Files.write(temporary, process.pid.toString().toByteArray(StandardCharsets.US_ASCII))
        try {
            Files.move(temporary, gate, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, gate, StandardCopyOption.REPLACE_EXISTING)
        }
        Log.i(TAG, "Opened translation redirect gate pid=${process.pid} process=${process.processName} path=$gate")
        return process.pid
    }

    companion object {
        const val GATE_FILE = "redirect-gate.pid"
        private const val TRANSLATION_CACHE_DIR = "translation-cache"
        private const val GAME_PACKAGE = "com.ProjectMoon.LimbusCompany"
        private const val TAG = "LimbusContainer"
    }
}
