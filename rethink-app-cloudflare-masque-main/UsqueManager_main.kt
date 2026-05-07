/*
 * File: app/src/main/java/com/celzero/bravedns/service/UsqueManager.kt
 *
 * WARP tunnel lifecycle manager via usque-rs binary (fd-passing mode).
 * Drop-in replacement for the broken file currently in the repo.
 */

package com.celzero.bravedns.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

object UsqueManager {

    private const val TAG = "UsqueManager"
    private const val CONFIG_DIR  = "usque"
    private const val CONFIG_FILE = "config.json"

    @Volatile private var usqueProcess: Process?             = null
    @Volatile private var tunFdReference: ParcelFileDescriptor? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Returns true when usque-rs config.json exists and is non-empty,
     * meaning the device has already been registered with WARP.
     */
    fun isRegistered(context: Context): Boolean = try {
        val f = File(context.filesDir, "$CONFIG_DIR/$CONFIG_FILE")
        f.exists() && f.length() > 0L
    } catch (_: Exception) {
        false
    }

    /**
     * Run `usque-rs register --accept-tos -c <path>` and wait for it.
     * Must be called from a coroutine / background thread.
     * Returns true on success.
     */
    suspend fun registerWithWarp(context: Context): Boolean = try {
        val bin = extractBinary(context) ?: return false
        val configDir  = File(context.filesDir, CONFIG_DIR).also { it.mkdirs() }
        val configFile = File(configDir, CONFIG_FILE)

        val cmd = listOf(bin.absolutePath, "register", "--accept-tos", "-c", configFile.absolutePath)
        Log.i(TAG, "register: ${cmd.joinToString(" ")}")

        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val exit = proc.waitFor()
        Log.i(TAG, "register exit=$exit, config exists=${configFile.exists()}")
        exit == 0 && configFile.exists() && configFile.length() > 0L
    } catch (e: Exception) {
        Log.e(TAG, "registerWithWarp error: ${e.message}", e)
        false
    }

    /**
     * Launch usque-rs in nativetun fd-passing mode.
     * The [tunFd] must remain open for the entire process lifetime;
     * this object holds a reference until [stopWarpTunnel] is called.
     */
    suspend fun startWarpTunnel(
        context: Context,
        tunFd: ParcelFileDescriptor,
        scope: CoroutineScope
    ): Boolean = try {
        val bin = extractBinary(context) ?: return false

        val cmd = listOf(
            bin.absolutePath,
            "nativetun",
            "--fd",          tunFd.fd.toString(),
            "--no-iproute2",
            "--sni-address", "consumer-masque.cloudflareclient.com"
        )
        Log.i(TAG, "startWarpTunnel: ${cmd.joinToString(" ")}")

        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        usqueProcess  = proc
        tunFdReference = tunFd

        // Monitor the child process and log if it dies unexpectedly.
        scope.launch(Dispatchers.IO) {
            val exit = proc.waitFor()
            Log.w(TAG, "usque-rs exited with code $exit")
        }

        Log.i(TAG, "WARP tunnel started (pid=${proc.pid()})")
        true
    } catch (e: Exception) {
        Log.e(TAG, "startWarpTunnel error: ${e.message}", e)
        false
    }

    /**
     * Kill the running usque-rs process and release resources.
     */
    fun stopWarpTunnel() {
        try {
            usqueProcess?.let { p ->
                p.destroy()
                try { p.waitFor() } catch (_: Exception) { p.destroyForcibly() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopWarpTunnel destroy error: ${e.message}")
        } finally {
            usqueProcess = null
        }

        try { tunFdReference?.close() } catch (_: Exception) {}
        tunFdReference = null

        Log.i(TAG, "WARP tunnel stopped")
    }

    /** True while the usque-rs child process is alive. */
    fun isRunning(): Boolean = usqueProcess?.isAlive == true

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Copy the correct ABI binary out of assets the first time,
     * then return the executable [File].  Returns null on failure.
     */
    private fun extractBinary(context: Context): File? = try {
        val arch       = detectArch()
        val assetName  = "usque-rs-$arch"
        val outputFile = File(context.filesDir, assetName)

        if (!outputFile.exists() || outputFile.length() == 0L) {
            context.assets.open(assetName).use { inp ->
                outputFile.outputStream().use { out -> inp.copyTo(out) }
            }
            Log.i(TAG, "Extracted $assetName to ${outputFile.absolutePath}")
        }

        outputFile.setExecutable(true, false)
        if (outputFile.exists()) outputFile else null
    } catch (e: Exception) {
        Log.e(TAG, "extractBinary error: ${e.message}", e)
        null
    }

    private fun detectArch(): String {
        val abi = System.getProperty("os.arch", "").lowercase()
        return when {
            abi.contains("aarch64") || abi.contains("arm64") -> "arm64"
            abi.contains("arm")                              -> "arm32"
            abi.contains("x86_64")                          -> "x86_64"
            else                                             -> "arm64"
        }
    }
}
