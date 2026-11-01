package com.pro.intervaltimer

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

// ==================== 1. DATA MODELS ====================

data class TimerStage(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var hours: Int,
    var minutes: Int,
    var seconds: Int,
    var reps: Int = 1
) {
    val durationMillis: Long
        get() = ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L
}

data class TimerRoutine(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var sets: Int,
    var rounds: Int,
    val stages: MutableList<TimerStage>
)

data class FlattenedStep(
    val stageName: String,
    val durationMillis: Long,
    val currentSet: Int,
    val totalSets: Int,
    val currentRound: Int,
    val totalRounds: Int,
    val currentRep: Int,
    val totalReps: Int,
    val nextStageName: String,
    val nextStageMillis: Long
)

// ==================== 2. LOCAL STORAGE & PREFERENCES ====================

object LocalStore {
    private const val PREF_NAME = "pro_interval_timer_store"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var isHindi: Boolean
        get() = true
        set(_) {}

    fun getLang(ctx: Context): String = prefs(ctx).getString("lang", "HI") ?: "HI"
    fun setLang(ctx: Context, lang: String) = prefs(ctx).edit().putString("lang", lang).apply()

    fun getThemeMode(ctx: Context): String = prefs(ctx).getString("theme", "DARK") ?: "DARK"
    fun setThemeMode(ctx: Context, mode: String) = prefs(ctx).edit().putString("theme", mode).apply()

    fun getAccentIndex(ctx: Context): Int = prefs(ctx).getInt("accent", 0)
    fun setAccentIndex(ctx: Context, idx: Int) = prefs(ctx).edit().putInt("accent", idx).apply()

    fun getBellType(ctx: Context): Int = prefs(ctx).getInt("bell_type", 0)
    fun setBellType(ctx: Context, type: Int) = prefs(ctx).edit().putInt("bell_type", type).apply()

    fun getCustomUri(ctx: Context): String = prefs(ctx).getString("custom_uri", "") ?: ""
    fun setCustomUri(ctx: Context, uri: String) = prefs(ctx).edit().putString("custom_uri", uri).apply()

    fun isVibrate(ctx: Context): Boolean = prefs(ctx).getBoolean("vibrate", true)
    fun setVibrate(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("vibrate", v).apply()

    fun getVolume(ctx: Context): Float = prefs(ctx).getFloat("volume", 1.0f)
    fun setVolume(ctx: Context, v: Float) = prefs(ctx).edit().putFloat("volume", v).apply()

    fun getAlarmDuration(ctx: Context): Int = prefs(ctx).getInt("alarm_dur", 5)
    fun setAlarmDuration(ctx: Context, sec: Int) = prefs(ctx).edit().putInt("alarm_dur", sec).apply()

    fun isHeadsUp(ctx: Context): Boolean = prefs(ctx).getBoolean("heads_up", true)
    fun setHeadsUp(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("heads_up", v).apply()

    fun isStageSound(ctx: Context): Boolean = prefs(ctx).getBoolean("stage_sound", true)
    fun setStageSound(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("stage_sound", v).apply()

    fun loadRoutines(ctx: Context): MutableList<TimerRoutine> {
        val raw = prefs(ctx).getString("routines_json", null)
        if (raw.isNullOrEmpty()) {
            val defaults = mutableListOf(
                TimerRoutine(
                    title = "HIIT वर्कआउट (5 Sets x 3 Rounds)",
                    sets = 5,
                    rounds = 3,
                    stages = mutableListOf(
                        TimerStage(name = "तैयारी (Prep)", hours = 0, minutes = 0, seconds = 10, reps = 1),
                        TimerStage(name = "कार्य (Work)", hours = 0, minutes = 5, seconds = 0, reps = 1),
                        TimerStage(name = "आराम (Rest)", hours = 0, minutes = 1, seconds = 30, reps = 1)
                    )
                ),
                TimerRoutine(
                    title = "अध्ययन फोकस (Study Routine)",
                    sets = 4,
                    rounds = 1,
                    stages = mutableListOf(
                        TimerStage(name = "अध्ययन (Study)", hours = 0, minutes = 25, seconds = 0, reps = 1),
                        TimerStage(name = "ब्रेक (Break)", hours = 0, minutes = 5, seconds = 0, reps = 1)
                    )
                )
            )
            saveRoutines(ctx, defaults)
            return defaults
        }

        val list = mutableListOf<TimerRoutine>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val stArr = obj.getJSONArray("stages")
                val stages = mutableListOf<TimerStage>()
                for (j in 0 until stArr.length()) {
                    val s = stArr.getJSONObject(j)
                    stages.add(
                        TimerStage(
                            id = s.optString("id", UUID.randomUUID().toString()),
                            name = s.getString("name"),
                            hours = s.getInt("hours"),
                            minutes = s.getInt("minutes"),
                            seconds = s.getInt("seconds"),
                            reps = s.optInt("reps", 1)
                        )
                    )
                }
                list.add(
                    TimerRoutine(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        sets = obj.getInt("sets"),
                        rounds = obj.getInt("rounds"),
                        stages = stages
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveRoutines(ctx: Context, list: List<TimerRoutine>) {
        val arr = JSONArray()
        for (r in list) {
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("title", r.title)
            obj.put("sets", r.sets)
            obj.put("rounds", r.rounds)
            val stArr = JSONArray()
            for (s in r.stages) {
                val sObj = JSONObject()
                sObj.put("id", s.id)
                sObj.put("name", s.name)
                sObj.put("hours", s.hours)
                sObj.put("minutes", s.minutes)
                sObj.put("seconds", s.seconds)
                sObj.put("reps", s.reps)
                stArr.put(sObj)
            }
            obj.put("stages", stArr)
            arr.put(obj)
        }
        prefs(ctx).edit().putString("routines_json", arr.toString()).apply()
    }

    fun tr(ctx: Context, hi: String, en: String): String =
        if (getLang(ctx) == "HI") hi else en
}

// ==================== 3. LOUD BELL ALARM ENGINE ====================

object BellAlarmEngine {
    @Volatile var isRinging = false
    private var alarmThread: Thread? = null
    private var activeTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    var onAlarmStateChange: (() -> Unit)? = null

    fun triggerAlarm(ctx: Context, isFinalFinish: Boolean) {
        stopAlarm()
        if (!LocalStore.isStageSound(ctx) && !isFinalFinish) return

        if (LocalStore.isVibrate(ctx)) {
            vibratePhone(ctx, if (isFinalFinish) 2500L else 700L)
        }

        val bellType = LocalStore.getBellType(ctx)
        val volume = LocalStore.getVolume(ctx)
        val durationSec = if (isFinalFinish) LocalStore.getAlarmDuration(ctx).coerceAtLeast(2) else 2

        isRinging = true
        onAlarmStateChange?.invoke()

        alarmThread = Thread {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                if (bellType == 3 || (bellType == 4 && LocalStore.getCustomUri(ctx).isNotEmpty())) {
                    val uri = if (bellType == 4) Uri.parse(LocalStore.getCustomUri(ctx))
                    else (RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

                    val mp = MediaPlayer()
                    mediaPlayer = mp
                    mp.setDataSource(ctx, uri)
                    mp.setAudioAttributes(attrs)
                    mp.isLooping = true
                    mp.setVolume(volume, volume)
                    mp.prepare()
                    mp.start()
                    Thread.sleep(durationSec * 1000L)
                } else {
                    val sampleRate = 44100
                    val strikeSamples = (sampleRate * 1.15).toInt()
                    val pcm = ShortArray(strikeSamples)
                    val baseFreq = when (bellType) {
                        0 -> 880.0   // Traditional Temple Bell
                        1 -> 1174.6  // Double High Chime
                        else -> 528.0 // Deep Bronze Gong
                    }

                    for (i in 0 until strikeSamples) {
                        val t = i.toDouble() / sampleRate
                        val env = exp(-3.0 * t)
                        val h1 = sin(2.0 * PI * baseFreq * t)
                        val h2 = 0.65 * sin(2.0 * PI * (baseFreq * 2.0) * t) * exp(-4.2 * t)
                        val h3 = 0.40 * sin(2.0 * PI * (baseFreq * 2.76) * t) * exp(-5.8 * t)
                        val h4 = if (bellType == 1 && t > 0.16) {
                            0.75 * sin(2.0 * PI * (baseFreq * 1.5) * (t - 0.16)) * exp(-4.0 * (t - 0.16))
                        } else 0.0
                        val sample = ((h1 + h2 + h3 + h4) / 2.15) * env * volume.coerceIn(0.1f, 1.0f)
                        pcm[i] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                    }

                    val track = AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(pcm.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()

                    activeTrack = track
                    track.write(pcm, 0, pcm.size)

                    val endTime = System.currentTimeMillis() + (durationSec * 1000L)
                    while (!Thread.currentThread().isInterrupted && System.currentTimeMillis() < endTime) {
                        track.stop()
                        track.reloadStaticData()
                        track.play()
                        Thread.sleep(1050L)
                    }
                }
            } catch (_: Exception) {
            } finally {
                Handler(Looper.getMainLooper()).post { stopAlarm() }
            }
        }.also { it.start() }
    }

    fun stopAlarm() {
        alarmThread?.interrupt()
        alarmThread = null
        try {
            activeTrack?.stop()
            activeTrack?.release()
        } catch (_: Exception) {}
        activeTrack = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        isRinging = false
        onAlarmStateChange?.invoke()
    }

    private fun vibratePhone(ctx: Context, ms: Long) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350), -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        } catch (_: Exception) {}
    }
}

// ==================== 4. FOREGROUND TIMER SERVICE ====================

class TimerForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var stepEndRealtime = 0L
    private var lastNotifSecond = -1L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isActive || !isRunning) return
            val now = SystemClock.elapsedRealtime()
            val remStep = (stepEndRealtime - now).coerceAtLeast(0L)
            remainingStepMillis = remStep

            val futureMillis = steps.drop(currentStepIndex + 1).sumOf { it.durationMillis }
            remainingTotalMillis = remStep + futureMillis

            val sec = remStep / 1000L
            if (sec != lastNotifSecond) {
                lastNotifSecond = sec
                updateOngoingNotification()
            }
            onTickListener?.invoke()

            if (remStep <= 0L) {
package com.pro.intervaltimer

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

// ==================== 1. DATA MODELS ====================

data class TimerStage(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var hours: Int,
    var minutes: Int,
    var seconds: Int,
    var reps: Int = 1
) {
    val durationMillis: Long
        get() = ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L
}

data class TimerRoutine(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var sets: Int,
    var rounds: Int,
    val stages: MutableList<TimerStage>
)

data class FlattenedStep(
    val stageName: String,
    val durationMillis: Long,
    val currentSet: Int,
    val totalSets: Int,
    val currentRound: Int,
    val totalRounds: Int,
    val currentRep: Int,
    val totalReps: Int,
    val nextStageName: String,
    val nextStageMillis: Long
)

// ==================== 2. LOCAL STORAGE & PREFERENCES ====================

object LocalStore {
    private const val PREF_NAME = "pro_interval_timer_store"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var isHindi: Boolean
        get() = true
        set(_) {}

    fun getLang(ctx: Context): String = prefs(ctx).getString("lang", "HI") ?: "HI"
    fun setLang(ctx: Context, lang: String) = prefs(ctx).edit().putString("lang", lang).apply()

    fun getThemeMode(ctx: Context): String = prefs(ctx).getString("theme", "DARK") ?: "DARK"
    fun setThemeMode(ctx: Context, mode: String) = prefs(ctx).edit().putString("theme", mode).apply()

    fun getAccentIndex(ctx: Context): Int = prefs(ctx).getInt("accent", 0)
    fun setAccentIndex(ctx: Context, idx: Int) = prefs(ctx).edit().putInt("accent", idx).apply()

    fun getBellType(ctx: Context): Int = prefs(ctx).getInt("bell_type", 0)
    fun setBellType(ctx: Context, type: Int) = prefs(ctx).edit().putInt("bell_type", type).apply()

    fun getCustomUri(ctx: Context): String = prefs(ctx).getString("custom_uri", "") ?: ""
    fun setCustomUri(ctx: Context, uri: String) = prefs(ctx).edit().putString("custom_uri", uri).apply()

    fun isVibrate(ctx: Context): Boolean = prefs(ctx).getBoolean("vibrate", true)
    fun setVibrate(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("vibrate", v).apply()

    fun getVolume(ctx: Context): Float = prefs(ctx).getFloat("volume", 1.0f)
    fun setVolume(ctx: Context, v: Float) = prefs(ctx).edit().putFloat("volume", v).apply()

    fun getAlarmDuration(ctx: Context): Int = prefs(ctx).getInt("alarm_dur", 5)
    fun setAlarmDuration(ctx: Context, sec: Int) = prefs(ctx).edit().putInt("alarm_dur", sec).apply()

    fun isHeadsUp(ctx: Context): Boolean = prefs(ctx).getBoolean("heads_up", true)
    fun setHeadsUp(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("heads_up", v).apply()

    fun isStageSound(ctx: Context): Boolean = prefs(ctx).getBoolean("stage_sound", true)
    fun setStageSound(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("stage_sound", v).apply()

    fun loadRoutines(ctx: Context): MutableList<TimerRoutine> {
        val raw = prefs(ctx).getString("routines_json", null)
        if (raw.isNullOrEmpty()) {
            val defaults = mutableListOf(
                TimerRoutine(
                    title = "HIIT वर्कआउट (5 Sets x 3 Rounds)",
                    sets = 5,
                    rounds = 3,
                    stages = mutableListOf(
                        TimerStage(name = "तैयारी (Prep)", hours = 0, minutes = 0, seconds = 10, reps = 1),
                        TimerStage(name = "कार्य (Work)", hours = 0, minutes = 5, seconds = 0, reps = 1),
                        TimerStage(name = "आराम (Rest)", hours = 0, minutes = 1, seconds = 30, reps = 1)
                    )
                ),
                TimerRoutine(
                    title = "अध्ययन फोकस (Study Routine)",
                    sets = 4,
                    rounds = 1,
                    stages = mutableListOf(
                        TimerStage(name = "अध्ययन (Study)", hours = 0, minutes = 25, seconds = 0, reps = 1),
                        TimerStage(name = "ब्रेक (Break)", hours = 0, minutes = 5, seconds = 0, reps = 1)
                    )
                )
            )
            saveRoutines(ctx, defaults)
            return defaults
        }

        val list = mutableListOf<TimerRoutine>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val stArr = obj.getJSONArray("stages")
                val stages = mutableListOf<TimerStage>()
                for (j in 0 until stArr.length()) {
                    val s = stArr.getJSONObject(j)
                    stages.add(
                        TimerStage(
                            id = s.optString("id", UUID.randomUUID().toString()),
                            name = s.getString("name"),
                            hours = s.getInt("hours"),
                            minutes = s.getInt("minutes"),
                            seconds = s.getInt("seconds"),
                            reps = s.optInt("reps", 1)
                        )
                    )
                }
                list.add(
                    TimerRoutine(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        sets = obj.getInt("sets"),
                        rounds = obj.getInt("rounds"),
                        stages = stages
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveRoutines(ctx: Context, list: List<TimerRoutine>) {
        val arr = JSONArray()
        for (r in list) {
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("title", r.title)
            obj.put("sets", r.sets)
            obj.put("rounds", r.rounds)
            val stArr = JSONArray()
            for (s in r.stages) {
                val sObj = JSONObject()
                sObj.put("id", s.id)
                sObj.put("name", s.name)
                sObj.put("hours", s.hours)
                sObj.put("minutes", s.minutes)
                sObj.put("seconds", s.seconds)
                sObj.put("reps", s.reps)
                stArr.put(sObj)
            }
            obj.put("stages", stArr)
            arr.put(obj)
        }
        prefs(ctx).edit().putString("routines_json", arr.toString()).apply()
    }

    fun tr(ctx: Context, hi: String, en: String): String =
        if (getLang(ctx) == "HI") hi else en
}

// ==================== 3. LOUD BELL ALARM ENGINE ====================

object BellAlarmEngine {
    @Volatile var isRinging = false
    private var alarmThread: Thread? = null
    private var activeTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    var onAlarmStateChange: (() -> Unit)? = null

    fun triggerAlarm(ctx: Context, isFinalFinish: Boolean) {
        stopAlarm()
        if (!LocalStore.isStageSound(ctx) && !isFinalFinish) return

        if (LocalStore.isVibrate(ctx)) {
            vibratePhone(ctx, if (isFinalFinish) 2500L else 700L)
        }

        val bellType = LocalStore.getBellType(ctx)
        val volume = LocalStore.getVolume(ctx)
        val durationSec = if (isFinalFinish) LocalStore.getAlarmDuration(ctx).coerceAtLeast(2) else 2

        isRinging = true
        onAlarmStateChange?.invoke()

        alarmThread = Thread {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                if (bellType == 3 || (bellType == 4 && LocalStore.getCustomUri(ctx).isNotEmpty())) {
                    val uri = if (bellType == 4) Uri.parse(LocalStore.getCustomUri(ctx))
                    else (RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

                    val mp = MediaPlayer()
                    mediaPlayer = mp
                    mp.setDataSource(ctx, uri)
                    mp.setAudioAttributes(attrs)
                    mp.isLooping = true
                    mp.setVolume(volume, volume)
                    mp.prepare()
                    mp.start()
                    Thread.sleep(durationSec * 1000L)
                } else {
                    val sampleRate = 44100
                    val strikeSamples = (sampleRate * 1.15).toInt()
                    val pcm = ShortArray(strikeSamples)
                    val baseFreq = when (bellType) {
                        0 -> 880.0   // Traditional Temple Bell
                        1 -> 1174.6  // Double High Chime
                        else -> 528.0 // Deep Bronze Gong
                    }

                    for (i in 0 until strikeSamples) {
                        val t = i.toDouble() / sampleRate
                        val env = exp(-3.0 * t)
                        val h1 = sin(2.0 * PI * baseFreq * t)
                        val h2 = 0.65 * sin(2.0 * PI * (baseFreq * 2.0) * t) * exp(-4.2 * t)
                        val h3 = 0.40 * sin(2.0 * PI * (baseFreq * 2.76) * t) * exp(-5.8 * t)
                        val h4 = if (bellType == 1 && t > 0.16) {
                            0.75 * sin(2.0 * PI * (baseFreq * 1.5) * (t - 0.16)) * exp(-4.0 * (t - 0.16))
                        } else 0.0
                        val sample = ((h1 + h2 + h3 + h4) / 2.15) * env * volume.coerceIn(0.1f, 1.0f)
                        pcm[i] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                    }

                    val track = AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(pcm.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()

                    activeTrack = track
                    track.write(pcm, 0, pcm.size)

                    val endTime = System.currentTimeMillis() + (durationSec * 1000L)
                    while (!Thread.currentThread().isInterrupted && System.currentTimeMillis() < endTime) {
                        track.stop()
                        track.reloadStaticData()
                        track.play()
                        Thread.sleep(1050L)
                    }
                }
            } catch (_: Exception) {
            } finally {
                Handler(Looper.getMainLooper()).post { stopAlarm() }
            }
        }.also { it.start() }
    }

    fun stopAlarm() {
        alarmThread?.interrupt()
        alarmThread = null
        try {
            activeTrack?.stop()
            activeTrack?.release()
        } catch (_: Exception) {}
        activeTrack = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        isRinging = false
        onAlarmStateChange?.invoke()
    }

    private fun vibratePhone(ctx: Context, ms: Long) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350), -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        } catch (_: Exception) {}
    }
}

// ==================== 4. FOREGROUND TIMER SERVICE ====================

class TimerForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var stepEndRealtime = 0L
    private var lastNotifSecond = -1L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isActive || !isRunning) return
            val now = SystemClock.elapsedRealtime()
            val remStep = (stepEndRealtime - now).coerceAtLeast(0L)
            remainingStepMillis = remStep

            val futureMillis = steps.drop(currentStepIndex + 1).sumOf { it.durationMillis }
            remainingTotalMillis = remStep + futureMillis

            val sec = remStep / 1000L
            if (sec != lastNotifSecond) {
                lastNotifSecond = sec
                updateOngoingNotification()
            }
            onTickListener?.invoke()

            if (remStep <= 0L) {
                advanceToNextStep()
            } else {
                handler.postDelayed(this, 50L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntervalTimer::WakeLock").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (steps.isNotEmpty()) {
                    isActive = true
                    isRunning = true
                    currentStepIndex = 0
                    remainingStepMillis = steps[0].durationMillis
                    remainingTotalMillis = totalRoutineMillis
                    stepEndRealtime = SystemClock.elapsedRealtime() + remainingStepMillis
                    val notif = buildOngoingNotification()
                    if (Build.VERSION.SDK_INT >= 34) {
                        startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(1001, notif)
                    }
                    handler.removeCallbacks(tickRunnable)
                    handler.post(tickRunnable)
                    onTickListener?.invoke()
                }
            }
            ACTION_PAUSE -> {
                if (isRunning) {
                    isRunning = false
                    handler.removeCallbacks(tickRunnable)
                    remainingStepMillis = (stepEndRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    updateOngoingNotification()
                    onTickListener?.invoke()
                }
            }
            ACTION_RESUME -> {
                if (isActive && !isRunning && steps.isNotEmpty()) {
                    isRunning = true
                    if (remainingStepMillis <= 0L) {
                        remainingStepMillis = steps[currentStepIndex].durationMillis
                    }
                    stepEndRealtime = SystemClock.elapsedRealtime() + remainingStepMillis
                    handler.removeCallbacks(tickRunnable)
                    handler.post(tickRunnable)
                    updateOngoingNotification()
                    onTickListener?.invoke()
                }
            }
            ACTION_RESTART -> {
                if (steps.isNotEmpty()) {
                    isActive = true
                    isRunning = true
                    currentStepIndex = 0
                    remainingStepMillis = steps[0].durationMillis
                    remainingTotalMillis = totalRoutineMillis
                    stepEndRealtime = SystemClock.elapsedRealtime() + remainingStepMillis
                    handler.removeCallbacks(tickRunnable)
                    handler.post(tickRunnable)
                    updateOngoingNotification()
                    onTickListener?.invoke()
                }
            }
            ACTION_NEXT -> jumpToStep(currentStepIndex + 1)
            ACTION_PREV -> jumpToStep((currentStepIndex - 1).coerceAtLeast(0))
            ACTION_STOP_ALARM -> {
                BellAlarmEngine.stopAlarm()
                updateOngoingNotification()
                onTickListener?.invoke()
            }
            ACTION_STOP -> {
                handler.removeCallbacks(tickRunnable)
                BellAlarmEngine.stopAlarm()
                isActive = false
                isRunning = false
                onTickListener?.invoke()
                stopForeground( true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun jumpToStep(idx: Int) {
        if (idx !in steps.indices) return
        currentStepIndex = idx
        remainingStepMillis = steps[idx].durationMillis
        stepEndRealtime = SystemClock.elapsedRealtime() + remainingStepMillis
        val futureMillis = steps.drop(idx + 1).sumOf { it.durationMillis }
        remainingTotalMillis = remainingStepMillis + futureMillis
        updateOngoingNotification()
        onTickListener?.invoke()
    }

    private fun advanceToNextStep() {
        val nextIdx = currentStepIndex + 1
        if (nextIdx < steps.size) {
            currentStepIndex = nextIdx
            val nextStep = steps[nextIdx]
            remainingStepMillis = nextStep.durationMillis
            stepEndRealtime = SystemClock.elapsedRealtime() + nextStep.durationMillis
            BellAlarmEngine.triggerAlarm(this, isFinalFinish = false)
            if (LocalStore.isHeadsUp(this)) {
                sendTransitionAlert(nextStep.stageName, nextStep.durationMillis, false)
            }
            updateOngoingNotification()
            onTickListener?.invoke()
            handler.postDelayed(tickRunnable, 50L)
        } else {
            isRunning = false
            remainingStepMillis = 0L
            remainingTotalMillis = 0L
            BellAlarmEngine.triggerAlarm(this, isFinalFinish = true)
            sendTransitionAlert(LocalStore.tr(this, "रूटीन पूरा हुआ!", "Routine Completed!"), 0L, true)
            updateOngoingNotification()
            onTickListener?.invoke()
        }
    }

    private fun buildOngoingNotification(): Notification {
        val step = steps.getOrNull(currentStepIndex)
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (step != null) "${step.stageName} • ${formatMs(remainingStepMillis)}" else routineTitle
        val body = if (step != null) {
            "${LocalStore.tr(this, "सेट", "Set")} ${step.currentSet}/${step.totalSets} | ${LocalStore.tr(this, "राउंड", "Round")} ${step.currentRound}/${step.totalRounds}"
        } else L                advanceToNextStep()
            } else {
                handler.postDelayed(this, 50L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntervalTimer::WakeLock").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (steps.isNotEmpty()) {
                    isActive = true
                    isRunning = true
                    currentStepIndex = 0
                    remainingStepMillis = steps[0].durationMillis
                    remainingTotalMillis = totalRoutineMillis
                    stepEndRealtime = SystemClock.elapsedRealtime() + remainingStepMillis
                    val notif = buildOngoingNotification()
                    if (Build.VERSION.SDK_INT >= 34) {
                        startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(1001, notif)
                    }
                    handler.removeCallbacks(tickRunnable)
                    handler.post(tickRunnable)
                    onTickListener?.invoke()
                }
            }
            ACTION_PAUSE -> {
                if (isRunning) {
                    isRunning = false
                    handler.removeCallbacks(tickRunnable)
                    remainingStepMillis = (stepEndRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    updateOngoingNotification()
                    onTickListener?.invoke()
                }
            }
            ACTION_RESUME -> {
                if (isActive && !isRunning && steps.isNotEmpty()) {
                    isRunning = true
                    if (remainingStepMillis <= 0L) {
                        remainingStepMillis = steps[currentStepIndex].durationMillis
                    }
                    stepEndRealtime = SystemClock.elapsedRealtime() + remainingStepMillis
                    handler.removeCallbacks(tickRunnable)
                    handler.post(tickRunnable)
                    updateOngoingNotification()
                    onTickListener?.invoke()
                }
            }
            ACTION_RESTART -> {
                if (steps.isNotEmpty()) {
                    isActive = true
                    isRunning = true
                    currentStepIndex = 0
                    remainingStepMillis = steps[0].durationMillis
                    remainingTotalMillis = totalRoutineMillis
                    stepEndRealtime = SystemClock.elapsedRealtime() + remainingStepMillis
                    handler.removeCallbacks(tickRunnable)
                    handler.post(tickRunnable)
                    updateOngoingNotification()
                    onTickListener?.invoke()
                }
            }
            ACTION_NEXT -> jumpToStep(currentStepIndex + 1)
            ACTION_PREV -> jumpToStep((currentStepIndex - 1).coerceAtLeast(0))
            ACTION_STOP_ALARM -> {
                BellAlarmEngine.stopAlarm()
                updateOngoingNotification()
                onTickListener?.invoke()
            }
            ACTION_STOP -> {
                handler.removeCallbacks(tickRunnable)
                BellAlarmEngine.stopAlarm()
                isActive = false
                isRunning = false
                onTickListener?.invoke()
