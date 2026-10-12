package com.c0mpile.readahead

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.c0mpile.readahead.narration.AacEncoder
import com.c0mpile.readahead.narration.EngineRenderer
import com.c0mpile.readahead.narration.ExportException
import com.c0mpile.readahead.narration.ExportProgress
import com.c0mpile.readahead.narration.ExportRunner
import com.c0mpile.readahead.narration.Mp4Joiner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Runs an export in the foreground so it goes on with the screen off and in the background. The work itself is
 * [ExportRunner]; this holds the notification, a wake lock and the cancel button.
 */
class ExportService : Service() {
    // Rendering, encoding and joining are blocking work: never on the main thread (that froze the screen and raised an ANR).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }
        val pending = ExportState.pending
        if (pending == null || job?.isActive == true) {
            if (job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Starting…", null), foregroundType())
        wake =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadAhead:export")
                .apply { acquire(WAKE_LIMIT_MS) }
        ExportState.set(ExportUi.Running(null))
        job = scope.launch { run(pending) }
        return START_NOT_STICKY
    }

    /** Media processing exists from Android 15; before that the work is declared as a data sync. */
    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    /** Android ends a media-processing service after its daily allowance; what is rendered stays and a restart resumes. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        job?.cancel()
    }

    private suspend fun run(pending: PendingExport) {
        try {
            val request = pending.request
            val runner =
                ExportRunner(
                    { EngineRenderer(applicationContext, pending.voice) },
                    AacEncoder(),
                    Mp4Joiner(),
                    File(cacheDir, "work"),
                    pending.parallel,
                )
            var location = ""
            runner.run(request, { progress ->
                ExportState.set(ExportUi.Running(progress))
                update(progress)
            }) { book, chapters ->
                ExportState.set(ExportUi.Running(ExportProgress.Saving))
                update(ExportProgress.Saving)
                val files = if (request.keepChapterFiles) listOf(book) + chapters else listOf(book)
                location = save(pending, files)
                // Copied: nothing is needed in the app's storage any more.
                files.forEach { it.delete() }
            }
            request.outDir.delete() // empty by now (the runner removed the chapter files); stays when anything is left
            ExportState.set(ExportUi.Done(location))
        } catch (e: CancellationException) {
            ExportState.set(ExportUi.Cancelled)
            throw e
        } catch (e: IOException) {
            ExportState.set(ExportUi.Failed(e.message ?: "The export failed."))
        } finally {
            ExportState.pending = null
            wake?.takeIf { it.isHeld }?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun save(
        pending: PendingExport,
        files: List<File>,
    ): String =
        try {
            LibraryFolder(applicationContext, pending.destination).save(files, pending.request.author, pending.request.title)
        } catch (e: IOException) {
            throw ExportException(
                "The audiobook is finished but could not be saved to the folder you chose: ${e.message}. " +
                    "It is kept in the app's storage; start again to try once more.",
                e,
            )
        }

    private fun update(progress: ExportProgress) {
        val text =
            when (progress) {
                is ExportProgress.Rendering ->
                    "${(progress.overall * PERCENT).toInt()} % · chapter ${progress.chapter + 1} of ${progress.chapters}: ${progress.title}"
                ExportProgress.Joining -> "Joining the chapters…"
                ExportProgress.Saving -> "Saving to your folder…"
                is ExportProgress.Done -> "Done"
            }
        val fraction = (progress as? ExportProgress.Rendering)?.overall
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, fraction))
    }

    private fun notification(
        text: String,
        fraction: Float?,
    ): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Export", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Rendering audiobook")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (fraction != null) setProgress(PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt(), false) }
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "export"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL = "com.c0mpile.readahead.CANCEL"
        private const val PROGRESS_MAX = 1000
        private const val PERCENT = 100
        private const val WAKE_LIMIT_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) = context.startForegroundService(Intent(context, ExportService::class.java))
    }
}
