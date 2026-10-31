package com.lion.ai

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** شاشة منبه كاملة: صوت منبه متكرر + قراءة صوتية للتذكير + أزرار إيقاف وتأجيل. */
class AlarmActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var message = ""
    private var stopped = false

    private val speakLoop = object : Runnable {
        override fun run() {
            if (stopped) return
            tts?.speak("تذكير. $message", TextToSpeech.QUEUE_FLUSH, null, "lion_alarm")
            handler.postDelayed(this, 12_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getIntExtra("id", 0)
        message = intent.getStringExtra("text").orEmpty().ifBlank { "حان موعد التذكير" }
        val at = intent.getLongExtra("at", System.currentTimeMillis())
        // نلغي الإشعار حتى لا يتكرر صوته مع صوت هذه الشاشة
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)

        startSound()
        startVibration()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { engine?.language = Locale.forLanguageTag("ar") }
                handler.postDelayed(speakLoop, 1500L)
            }
        }
        tts = engine
        handler.postDelayed({ stopAll(); finish() }, 2 * 60_000L)

        setContent {
            AlarmScreen(
                text = message,
                time = TimeFmt.clock(at),
                onStop = {
                    stopAll()
                    finish()
                },
                onSnooze = { minutes ->
                    runCatching {
                        Scheduler.add(
                            this,
                            SecurePrefs.get(this),
                            ScheduledItem(
                                Scheduler.newId(),
                                ActionKind.REMINDER,
                                System.currentTimeMillis() + minutes * 60_000L,
                                "", "", message
                            )
                        )
                    }
                    stopAll()
                    finish()
                }
            )
        }
    }

    private fun startSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator = v
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
        } catch (e: Exception) {
        }
    }

    private fun stopAll() {
        if (stopped) return
        stopped = true
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }
}

@Composable
private fun AlarmScreen(
    text: String,
    time: String,
    onStop: () -> Unit,
    onSnooze: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("⏰", fontSize = 72.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "حان موعد التذكير",
            color = Color(0xFFFFB800),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text("🕐 الموعد: $time", color = Color(0xFFB0B0B0), fontSize = 16.sp)
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
        ) {
            Text("✔ إيقاف", color = Color(0xFF0D0D0D), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onSnooze(10) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Text("⏰ تأجيل 10 دقائق", color = Color.White, fontSize = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onSnooze(60) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Text("⏰ تأجيل ساعة", color = Color.White, fontSize = 15.sp)
        }
    }
}
