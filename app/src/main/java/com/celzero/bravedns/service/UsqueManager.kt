package com.celzero.bravedns.service

import Logger
import android.content.Context
import android.util.Log
import java.io.File
import java.io.StringWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UsqueManager {
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 40000
    private const val BINARY_NAME = "libusque.so"
    private var process: Process? = null

    // ── debug log file ────────────────────────────────────────────────────────
    private fun dlog(ctx: Context, msg: String) {
        Log.d("WARP_DEBUG", msg)
        try {
            File(ctx.filesDir, "warp_debug.txt").appendText("${System.currentTimeMillis()} $msg\n")
        } catch (_: Exception) {}
    }

    fun readDebugLog(ctx: Context): String {
        return try {
            val f = File(ctx.filesDir, "warp_debug.txt")
            if (f.exists()) f.readText() else "log file not found"
        } catch (e: Exception) {
            "error reading log: ${e.message}"
        }
    }

    fun clearDebugLog(ctx: Context) {
        try { File(ctx.filesDir, "warp_debug.txt").delete() } catch (_: Exception) {}
    }
    // ─────────────────────────────────────────────────────────────────────────

    private fun getBinary(ctx: Context): File {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val bin = File(nativeDir, BINARY_NAME)
        dlog(ctx, "getBinary: path=${bin.absolutePath} exists=${bin.exists()} canExec=${bin.canExecute()} size=${bin.length()}")
        return bin
    }

    fun isRegistered(ctx: Context): Boolean {
        val f = File(ctx.filesDir, "config.json")
        Log.d("WARP_DEBUG", "isRegistered: path=${f.absolutePath} exists=${f.exists()} size=${f.length()}")
        return f.exists() && f.length() > 0L
    }

    suspend fun registerWithWarp(context: Context): Boolean = withContext(Dispatchers.IO) {
        clearDebugLog(context)
        dlog(context, "registerWithWarp: >>>ENTRY<<<")
        try {
            val bin = getBinary(context)

            if (!bin.exists()) {
                dlog(context, "BINARY NOT FOUND — put libusque.so in jniLibs/arm64-v8a/")
                return@withContext false
            }
            if (!bin.canExecute()) {
                dlog(context, "BINARY NOT EXECUTABLE — W^X policy?")
                return@withContext false
            }

            val configFile = File(context.filesDir, "config.json")
            if (configFile.exists()) {
                configFile.delete()
                dlog(context, "deleted old config.json")
            }

            // --accept-tos skips the stdin TOS prompt entirely
            val cmd = listOf(bin.absolutePath, "register", "--accept-tos", "-c", configFile.absolutePath)
            dlog(context, "cmd=${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd).redirectErrorStream(false)
            // Go 1.24+ uses vDSO __kernel_getrandom which Android's seccomp filter blocks (SIGSYS/exit 159).
            // Disabling vgetrandom forces the Go runtime to use the getrandom syscall instead.
            pb.environment()["GODEBUG"] = "vgetrandom=off"
            val proc = pb.start()

            // read stdout and stderr concurrently to avoid deadlock
            val stdoutWriter = StringWriter()
            val stderrWriter = StringWriter()

            val stdoutThread = Thread {
                try { stdoutWriter.write(proc.inputStream.bufferedReader().readText()) } catch (_: Exception) {}
            }.also { it.start() }

            val stderrThread = Thread {
                try { stderrWriter.write(proc.errorStream.bufferedReader().readText()) } catch (_: Exception) {}
            }.also { it.start() }

            val exit = proc.waitFor()
            stdoutThread.join(3000)
            stderrThread.join(3000)

            dlog(context, "exit=$exit")
            dlog(context, "stdout=${stdoutWriter}")
            dlog(context, "stderr=${stderrWriter}")
            dlog(context, "configExists=${configFile.exists()} size=${configFile.length()}")

            val ok = exit == 0 && configFile.exists() && configFile.length() > 0L
            dlog(context, "result=$ok")
            ok

        } catch (e: Exception) {
            dlog(context, "EXCEPTION ${e.message}\n${e.stackTraceToString()}")
            Logger.e(Logger.LOG_TAG_PROXY, "registerWithWarp exception", e)
            false
        }
    }

    suspend fun startSocksProxy(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        clearDebugLog(ctx)
        dlog(ctx, "startSocksProxy: >>>ENTRY<<<")
        stopSocksProxy()
        try {
            val bin = getBinary(ctx)
            if (!bin.exists() || !bin.canExecute()) {
                dlog(ctx, "startSocksProxy: binary not ready exists=${bin.exists()} canExec=${bin.canExecute()}")
                return@withContext false
            }

            val configFile = File(ctx.filesDir, "config.json")
            dlog(ctx, "startSocksProxy: configExists=${configFile.exists()} size=${configFile.length()}")

            // Apply user-configured SNI override (defaults to "cloudflare.com").
              // Read at process-start time so the user's saved value is used for every restart.
              val sni = try {
                  org.koin.java.KoinJavaComponent
                      .get<PersistentState>(PersistentState::class.java)
                      .warpSpoofedSni.trim()
              } catch (t: Throwable) {
                  dlog(ctx, "startSocksProxy: SNI lookup failed: ${t.message}")
                  ""
              }
              val cmd = mutableListOf(
                  bin.absolutePath, "socks",
                  "-b", SOCKS_HOST,
                  "-p", SOCKS_PORT.toString(),
                  "-c", configFile.absolutePath
              )
              if (sni.isNotEmpty()) {
                  cmd += listOf("-s", sni)
              }
              dlog(ctx, "startSocksProxy: cmd=${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd).redirectErrorStream(false)
            pb.environment()["GODEBUG"] = "vgetrandom=off"
            val proc = pb.start()
            process = proc

            // Drain stdout and stderr in background threads so the process doesn't block on a
            // full pipe buffer. Capture output for diagnostics if the process exits early.
            val outputWriter = StringWriter()
            val errorWriter = StringWriter()
            val outThread = Thread {
                try { outputWriter.write(proc.inputStream.bufferedReader().readText()) } catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }
            val errThread = Thread {
                try { errorWriter.write(proc.errorStream.bufferedReader().readText()) } catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }

            // Give the process time to either bind the port or crash.
            Thread.sleep(1500)

            val alive = proc.isAlive
            dlog(ctx, "startSocksProxy: alive=$alive")

            if (!alive) {
                // Process already exited — collect its output before reporting failure.
                outThread.join(2000)
                errThread.join(2000)
                val exit = try { proc.exitValue() } catch (_: Exception) { -1 }
                dlog(ctx, "startSocksProxy: exit=$exit")
                dlog(ctx, "startSocksProxy: stdout=${outputWriter}")
                dlog(ctx, "startSocksProxy: stderr=${errorWriter}")
                process = null
            }

            alive

        } catch (e: Exception) {
            dlog(ctx, "startSocksProxy: EXCEPTION ${e.message}\n${e.stackTraceToString()}")
            Logger.e(Logger.LOG_TAG_PROXY, "startSocksProxy exception", e)
            false
        }
    }

    fun stopSocksProxy() {
        Log.d("WARP_DEBUG", "stopSocksProxy: isAlive=${process?.isAlive}")
        process?.destroy()
        process = null
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
