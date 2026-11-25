package com.replayapp

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
import android.widget.Toast
import java.nio.ByteBuffer

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_SAVE = "save"
        const val ACTION_STOP = "stop"
        const val CHANNEL = "replay"
        const val KEEP_US = 30_000_000L
    }

    private class Frame(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private val lock = Any()
    private val frames = ArrayDeque<Frame>()
    @Volatile private var format: MediaFormat? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_SAVE -> saveClip()
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (running) return

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val big = maxOf(metrics.widthPixels, metrics.heightPixels)
        val small = minOf(metrics.widthPixels, metrics.heightPixels)
        val scale = 720f / small
        val width = ((big * scale) / 16).toInt() * 16
        val height = 720

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val t = HandlerThread("encoder")
        t.start()
        thread = t

        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val buf = codec.getOutputBuffer(index)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (buf != null && info.size > 0 && !isConfig) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(bytes)
                    addFrame(Frame(bytes, info.presentationTimeUs, info.flags))
                }
                codec.releaseOutputBuffer(index, false)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {}

            override fun onOutputFormatChanged(codec: MediaCodec, f: MediaFormat) {
                format = f
            }
        }, Handler(t.looper))

        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        c.start()
        codec = c

        display = proj.createVirtualDisplay(
            "replay", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
        running = true
    }

    private fun addFrame(f: Frame) {
        synchronized(lock) {
            frames.addLast(f)
            while (frames.size > 1 && f.ptsUs - frames.first().ptsUs > KEEP_US + 2_000_000L) {
                frames.removeFirst()
            }
        }
    }

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveClip() {
        val fmt = format
        val snapshot: List<Frame>
        synchronized(lock) { snapshot = ArrayList(frames) }
        if (fmt == null || snapshot.isEmpty()) {
            toast("Aún no hay video grabado")
            return
        }

        Thread {
            try {
                val lastPts = snapshot.last().ptsUs
                val start = snapshot.indexOfFirst {
                    lastPts - it.ptsUs <= KEEP_US &&
                        (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                }
                if (start < 0) {
                    toast("Espera unos segundos más e intenta de nuevo")
                    return@Thread
                }

                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "replay_" + System.currentTimeMillis() + ".mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Replay")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                val pfd = if (uri != null) contentResolver.openFileDescriptor(uri, "rw") else null
                if (pfd == null) {
                    toast("No se pudo crear el archivo")
                    return@Thread
                }

                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val track = muxer.addTrack(fmt)
                muxer.start()
                val base = snapshot[start].ptsUs
                val info = MediaCodec.BufferInfo()
                for (i in start until snapshot.size) {
                    val f = snapshot[i]
                    info.set(0, f.data.size, f.ptsUs - base, f.flags)
                    muxer.writeSampleData(track, ByteBuffer.wrap(f.data), info)
                }
                muxer.stop()
                muxer.release()
                pfd.close()
                toast("Guardado en Movies/Replay")
            } catch (e: Exception) {
                toast("Error al guardar: " + e.message)
            }
        }.start()
    }

    private fun stopRecording() {
        if (!running) return
        running = false
        try { display?.release() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        thread?.quitSafely()
        display = null
        codec = null
        projection = null
        synchronized(lock) { frames.clear() }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Replay", NotificationManager.IMPORTANCE_LOW)
        )

        fun pending(action: String, code: Int): PendingIntent =
            PendingIntent.getService(
                this, code,
                Intent(this, RecorderService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val saveAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_save),
            "Guardar 30 s", pending(ACTION_SAVE, 1)
        ).build()
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Detener", pending(ACTION_STOP, 2)
        ).build()

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Replay grabando")
            .setContentText("Baja este panel y toca Guardar 30 s")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(saveAction)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
