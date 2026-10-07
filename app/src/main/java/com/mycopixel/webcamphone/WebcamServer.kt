package com.mycopixel.webcamphone

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WebcamServer(private val port: Int = 8080) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val clientCount = AtomicInteger(0)
    private var serverJob: Job? = null

    @Volatile
    private var latestJpegBytes: ByteArray? = null

    val connectedClients: Int
        get() = clientCount.get()

    val running: Boolean
        get() = isRunning.get()

    fun updateFrame(jpegBytes: ByteArray) {
        latestJpegBytes = jpegBytes
    }

    fun start(scope: CoroutineScope) {
        if (isRunning.get()) return
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                isRunning.set(true)
                Log.d("WebcamServer", "Server started on port $port")

                while (isRunning.get() && serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        clientCount.incrementAndGet()
                        launch(Dispatchers.IO) {
                            handleClient(socket)
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e("WebcamServer", "Error accepting client", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WebcamServer", "Server error", e)
            } finally {
                stopServer()
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = input.bufferedReader()
            val requestLine = reader.readLine() ?: return

            Log.d("WebcamServer", "Request: $requestLine")

            if (requestLine.contains("/video") || requestLine.contains("GET / HTTP")) {
                // If root or video, let's serve MJPEG stream for /video, or HTML for /
                if (requestLine.contains("GET / HTTP")) {
                    serveHtmlPage(output)
                } else {
                    serveMjpegStream(output)
                }
            } else {
                // Serve HTML by default
                serveHtmlPage(output)
            }
        } catch (e: Exception) {
            // Client disconnected or timeout
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
            clientCount.decrementAndGet()
        }
    }

    private fun serveHtmlPage(output: OutputStream) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>WebcamPhone Stream</title>
                <style>
                    body { font-family: sans-serif; background: #121212; color: #fff; text-align: center; padding-top: 40px; }
                    img { max-width: 100%; height: auto; border: 3px solid #333; border-radius: 8px; }
                    .info { margin-top: 15px; color: #aaa; }
                </style>
            </head>
            <body>
                <h1>WebcamPhone Live Stream</h1>
                <img src="/video" alt="Live Stream" />
                <div class="info">Connected via Android WebcamPhone App</div>
            </body>
            </html>
        """.trimIndent()
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: ${html.length}\r\n" +
                "Connection: close\r\n\r\n" + html
        output.write(response.toByteArray())
        output.flush()
    }

    private suspend fun serveMjpegStream(output: OutputStream) {
        withContext(Dispatchers.IO) {
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                    "Connection: close\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "Pragma: no-cache\r\n\r\n"
            output.write(header.toByteArray())
            output.flush()

            while (isRunning.get()) {
                val frame = latestJpegBytes
                if (frame != null) {
                    try {
                        val partHeader = "--frame\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${frame.size}\r\n\r\n"
                        output.write(partHeader.toByteArray())
                        output.write(frame)
                        output.write("\r\n".toByteArray())
                        output.flush()
                    } catch (e: Exception) {
                        // Client closed connection
                        break
                    }
                }
                kotlinx.coroutines.delay(50) // ~20 fps max streaming rate
            }
        }
    }

    fun stop() {
        stopServer()
    }

    private fun stopServer() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        clientCount.set(0)
        Log.d("WebcamServer", "Server stopped")
    }
}
