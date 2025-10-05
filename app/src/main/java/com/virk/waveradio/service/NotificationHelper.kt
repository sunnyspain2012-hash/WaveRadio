import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.virk.waveradio.service.RadioPlaybackService

object NotificationHelper {
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RadioPlaybackService.CHANNEL_ID,
                "Radio Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}