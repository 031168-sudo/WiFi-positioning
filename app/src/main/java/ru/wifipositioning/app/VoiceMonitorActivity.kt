package ru.wifipositioning.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min

/**
 * "Резонанс" — voice tension & text-consistency monitor.
 *
 * IMPORTANT: this is deliberately NOT presented as a lie detector. Voice
 * stress analysis has no validated scientific link to deception; it only
 * reflects physiological arousal (which is also caused by nervousness,
 * fatigue, or normal emotion). See the on-screen "Ограничения" section.
 */
class VoiceMonitorActivity : Activity() {

    // ---------- colors ----------
    private val colInk = Color.rgb(15, 17, 22)
    private val colPanel = Color.rgb(23, 26, 34)
    private val colPanel2 = Color.rgb(29, 33, 43)
    private val colLine = Color.rgb(42, 47, 59)
    private val colText = Color.rgb(232, 233, 238)
    private val colTextDim = Color.rgb(154, 160, 176)
    private val colAccent = Color.parseColor("#E8A33D")
    private val colCalm = Color.parseColor("#4FA89B")
    private val colStress = Color.parseColor("#D1495B")
    private val colFlag = Color.parseColor("#9B87D9")

    // ---------- audio / pitch state ----------
    private val sampleRate = 16000
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var recording = false
    @Volatile private var calibrating = false

    private var calibStart = 0L
    private val calibSamples = ArrayList<Float>()
    private var baselineMean: Float? = null
    private var baselineStd: Float? = null
    private val pitchHistory = ArrayList<PitchSample>() // bounded, last ~20s
    private val rollingPitch = ArrayList<Float>() // last N voiced freqs, for jitter
    private var recordingStart = 0L
    private var lastUtteranceEnd: Long? = null
    private var currentUtteranceStart: Long? = null

    private val handler = Handler(Looper.getMainLooper())
    private val utterances = ArrayList<Utterance>()
    private var nextUtteranceId = 1

    // ---------- speech recognition ----------
    private var recognizer: SpeechRecognizer? = null
    private var recognitionSupported = false
    private lateinit var recognizerIntent: Intent

    // ---------- views ----------
    private lateinit var recordBtn: TextView
    private lateinit var statusText: TextView
    private lateinit var calibrationBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var unsupportedNote: TextView
    private lateinit var waveView: WaveformView
    private lateinit var pitchView: PitchChartView
    private lateinit var mPitch: TextView
    private lateinit var mZ: TextView
    private lateinit var mRate: TextView
    private lateinit var mPause: TextView
    private lateinit var gaugeBar: ProgressBar
    private lateinit var gaugeLabel: TextView
    private lateinit var transcriptFeed: LinearLayout
    private lateinit var transcriptEmpty: TextView
    private lateinit var utteranceCount: TextView
    private lateinit var conflictLog: LinearLayout
    private lateinit var conflictEmpty: TextView
    private lateinit var conflictCount: TextView

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale("ru"))

    companion object {
        private const val REQ_AUDIO = 501
        private const val CALIB_MIN_SAMPLES = 18
        private const val CALIB_MIN_MS = 3000L
        private const val CALIB_MAX_MS = 11000L
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = colInk
        window.navigationBarColor = colInk

        recognitionSupported = SpeechRecognizer.isRecognitionAvailable(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        val root = buildLayout()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(root)

        if (!recognitionSupported) unsupportedNote.visibility = View.VISIBLE
    }

    // ================= layout =================

    private fun buildLayout(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(colInk) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))

        content.addView(header())
        content.addView(spacer(16))
        content.addView(controlPanel())
        content.addView(spacer(16))
        content.addView(panel("Форма волны") {
            waveView = WaveformView(this)
            it.addView(waveView, LinearLayout.LayoutParams(-1, dp(90)))
        })
        content.addView(spacer(14))
        content.addView(panel("Высота голоса относительно вашей нормы") {
            pitchView = PitchChartView(this)
            it.addView(pitchView, LinearLayout.LayoutParams(-1, dp(120)))
            it.addView(legend())
        })
        content.addView(spacer(14))
        content.addView(metricsPanel())
        content.addView(spacer(14))
        content.addView(panel("Текущее напряжение") {
            val track = FrameLayout(this)
            gaugeBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = 0
                progressTintList = ColorStateList.valueOf(colCalm)
            }
            track.addView(gaugeBar, ViewGroup.LayoutParams(-1, dp(10)))
            it.addView(track, LinearLayout.LayoutParams(-1, dp(10)))
            gaugeLabel = TextView(this).apply {
                text = "Ожидание записи…"; setTextColor(colText); textSize = 14f
                setPadding(0, dp(8), 0, 0)
            }
            it.addView(gaugeLabel)
        })
        content.addView(spacer(14))
        content.addView(transcriptPanel())
        content.addView(spacer(14))
        content.addView(conflictPanel())
        content.addView(spacer(14))
        content.addView(explainer())
        return scroll
    }

    private fun header(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(TextView(this).apply {
            text = "◈"; textSize = 26f; setTextColor(colAccent)
            setPadding(0, 0, dp(10), 0)
        })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "Резонанс"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(colText)
        })
        titles.addView(TextView(this).apply {
            text = "Монитор голосового напряжения и текстовых нестыковок"
            textSize = 12f; setTextColor(colTextDim); setPadding(0, dp(2), 0, 0)
        })
        brandRow.addView(titles)
        col.addView(brandRow)
        col.addView(spacer(12))

        val disclaimer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(Color.argb(40, 232, 163, 61), 10, strokeColor = Color.argb(140, 107, 84, 38), strokeWidth = 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        disclaimer.addView(TextView(this).apply { text = "⚠ "; setTextColor(colAccent); textSize = 13.5f })
        disclaimer.addView(TextView(this).apply {
            text = "Это НЕ детектор лжи. Наука не подтверждает, что по голосу можно определить, врёт человек. " +
                "Приложение показывает только физиологическое напряжение в голосе и формальные нестыковки в " +
                "тексте — см. «Ограничения» внизу."
            textSize = 13f; setTextColor(Color.rgb(240, 217, 171)); setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(disclaimer)
        return col
    }

    private fun controlPanel(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = bg(colPanel, 16); setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        recordBtn = TextView(this).apply {
            text = "●"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(colText)
            background = bg(colPanel2, 32)
            setOnClickListener { onRecordClicked() }
        }
        row.addView(recordBtn, LinearLayout.LayoutParams(dp(60), dp(60)))

        val statusCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusText = TextView(this).apply {
            text = "Нажмите на кнопку, чтобы начать запись"; textSize = 14.5f; setTextColor(colText)
        }
        statusCol.addView(statusText)
        calibrationBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; visibility = View.GONE
            progressTintList = ColorStateList.valueOf(colAccent)
        }
        statusCol.addView(calibrationBar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })
        errorText = TextView(this).apply {
            textSize = 12.5f; setTextColor(colStress); visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        statusCol.addView(errorText)
        unsupportedNote = TextView(this).apply {
            text = "Распознавание речи недоступно на этом устройстве — транскрипт и поиск нестыковок " +
                "работать не будут, но анализ голосового напряжения будет доступен."
            textSize = 12.5f; setTextColor(colTextDim); visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        statusCol.addView(unsupportedNote)
        row.addView(statusCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(spacer(10))
            addView(LinearLayout(this@VoiceMonitorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ghostButton("Скопировать транскрипт") { copyTranscript() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(spacer(10, horizontal = true))
                addView(ghostButton("Сбросить сессию") { resetSession() }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
    }

    private fun ghostButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 12.5f; isAllCaps = false
        setTextColor(colTextDim); setBackgroundColor(Color.TRANSPARENT)
        background = bg(Color.TRANSPARENT, 8, strokeColor = colLine, strokeWidth = 2)
        setOnClickListener { onClick() }
    }

    private fun metricsPanel(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun metricRow(a: Pair<String, TextView>, b: Pair<String, TextView>) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(metricCell(a.first, a.second), LinearLayout.LayoutParams(0, -2, 1f))
            addView(metricCell(b.first, b.second), LinearLayout.LayoutParams(0, -2, 1f))
        }
        mPitch = valueView("— Гц"); mZ = valueView("— σ")
        mRate = valueView("— сл/с"); mPause = valueView("— с")
        grid.addView(metricRow("Тон голоса" to mPitch, "Отклонение от нормы" to mZ))
        grid.addView(spacer(12))
        grid.addView(metricRow("Темп речи" to mRate, "Пауза перед фразой" to mPause))
        return panelWrap("") { it.addView(grid) }
    }

    private fun valueView(t: String) = TextView(this).apply {
        text = t; textSize = 19f; setTextColor(colText); typeface = Typeface.MONOSPACE
    }

    private fun metricCell(label: String, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@VoiceMonitorActivity).apply {
            text = label; textSize = 12f; setTextColor(colTextDim)
        })
        addView(value, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
    }

    private fun transcriptPanel(): View = panelWrap("") { container ->
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(sectionTitle("Транскрипт"), LinearLayout.LayoutParams(0, -2, 1f))
        utteranceCount = countBadge("0", colPanel2, colText)
        head.addView(utteranceCount)
        container.addView(head)
        container.addView(spacer(10))
        transcriptEmpty = TextView(this).apply {
            text = "Реплики появятся здесь по мере распознавания речи."
            textSize = 13.5f; setTextColor(colTextDim)
        }
        transcriptFeed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        transcriptFeed.addView(transcriptEmpty)
        container.addView(transcriptFeed)
    }

    private fun conflictPanel(): View = panelWrap("") { container ->
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(sectionTitle("Возможные текстовые нестыковки"), LinearLayout.LayoutParams(0, -2, 1f))
        conflictCount = countBadge("0", Color.argb(46, 155, 135, 217), colFlag)
        head.addView(conflictCount)
        container.addView(head)
        container.addView(spacer(10))
        conflictEmpty = TextView(this).apply {
            text = "Нестыковок пока не обнаружено."; textSize = 13.5f; setTextColor(colTextDim)
        }
        conflictLog = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        conflictLog.addView(conflictEmpty)
        container.addView(conflictLog)
    }

    private fun countBadge(t: String, back: Int, fg: Int) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(fg); typeface = Typeface.MONOSPACE
        background = bg(back, 20); setPadding(dp(10), dp(2), dp(10), dp(2))
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t.uppercase(Locale("ru")); textSize = 12.5f; setTextColor(colTextDim)
        typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f
    }

    private fun legend(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        fun dot(color: Int, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(View(this@VoiceMonitorActivity).apply { background = bg(color, 6) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
            addView(TextView(this@VoiceMonitorActivity).apply { text = label; textSize = 11.5f; setTextColor(colTextDim) })
        }
        row.addView(dot(colCalm, "спокойно"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
        row.addView(dot(colAccent, "повышено"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
        row.addView(dot(colStress, "высокое напряжение"))
        return row
    }

    private fun panel(title: String, fill: (LinearLayout) -> Unit): View = panelWrap(title, fill)

    private fun panelWrap(title: String, fill: (LinearLayout) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(colPanel, 16); setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        if (title.isNotEmpty()) {
            box.addView(sectionTitle(title))
            box.addView(spacer(10))
        }
        fill(box)
        return box
    }

    private fun explainer(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(colPanel, 16); setPadding(dp(16), dp(16), dp(16), dp(18))
        }
        col.addView(explainerBlock(
            "Как это работает", colText,
            listOf(
                "Микрофон и алгоритм автокорреляции анализируют высоту тона (F0) и громкость голоса в реальном времени.",
                "Первые секунды записи — калибровка: приложение запоминает вашу обычную высоту голоса как индивидуальную норму.",
                "Каждая фраза оценивается по отклонению от этой нормы (в стандартных отклонениях, σ) и по «дрожанию» тона между кадрами.",
                "Распознанный текст сравнивается с более ранними фразами: если новое утверждение по той же теме отрицает или меняет числа из предыдущего, это помечается как текстовая нестыковка."
            )
        ))
        col.addView(spacer(16))
        col.addView(explainerBlock(
            "Ограничения — прочитайте перед использованием", Color.rgb(240, 179, 189),
            listOf(
                "Голосовое напряжение — не ложь. Волнение, усталость, неловкий вопрос или обычная эмоциональность речи дают такие же сигналы.",
                "Метод не проходил научной валидации. Не используйте это приложение для обвинений, найма, допросов или разрешения споров.",
                "Поиск нестыковок — это сопоставление слов и чисел, а не понимание смысла: возможны и ложные срабатывания, и пропуски.",
                "Записывайте голос других людей только с их согласия — это может быть законодательным требованием в вашей стране."
            )
        ))
        return col
    }

    private fun explainerBlock(title: String, titleColor: Int, items: List<String>): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = title; textSize = 14.5f; setTextColor(titleColor); typeface = Typeface.DEFAULT_BOLD })
        col.addView(spacer(8))
        items.forEach { line ->
            col.addView(TextView(this).apply {
                text = "•  $line"; textSize = 13f; setTextColor(colTextDim)
                setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(5), 0, dp(5))
            })
        }
        return col
    }

    private fun spacer(hDp: Int, horizontal: Boolean = false) = View(this).apply {
        layoutParams = if (horizontal) LinearLayout.LayoutParams(dp(hDp), -2) else LinearLayout.LayoutParams(-1, dp(hDp))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(strokeWidth, strokeColor)
        }

    // ================= recording lifecycle =================

    private fun onRecordClicked() {
        if (recording) { stopRecording(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        } else {
            beginRecording()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) beginRecording()
            else showError("Доступ к микрофону запрещён. Разрешите его в настройках приложения и попробуйте снова.")
        }
    }

    private fun showError(msg: String) { errorText.text = msg; errorText.visibility = View.VISIBLE }
    private fun hideError() { errorText.visibility = View.GONE }

    private fun beginRecording() {
        hideError()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { showError("Микрофон недоступен на этом устройстве."); return }
        val bufSize = minBuf.coerceAtLeast(4096)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: SecurityException) { null }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            showError("Не удалось открыть микрофон для анализа голоса.")
            return
        }
        audioRecord = record

        calibrating = true
        calibStart = SystemClock.elapsedRealtime()
        calibSamples.clear()
        baselineMean = null; baselineStd = null
        pitchHistory.clear(); rollingPitch.clear()
        pitchView.clear()
        calibrationBar.visibility = View.VISIBLE
        calibrationBar.progress = 0
        statusText.text = "Идёт калибровка голоса — говорите обычным тоном несколько секунд"

        recording = true
        recordBtn.text = "■"
        recordBtn.background = bg(colStress, 32)

        if (recognitionSupported) startSpeechRecognition()

        record.startRecording()
        audioThread = thread(start = true) { audioLoop(record) }
    }

    private fun audioLoop(record: AudioRecord) {
        val frame = ShortArray(2048)
        val floatBuf = FloatArray(2048)
        while (recording) {
            val n = record.read(frame, 0, frame.size)
            if (n < 0) break
            if (n == 0) continue
            for (i in 0 until n) floatBuf[i] = frame[i] / 32768f
            val (freq, _) = autoCorrelate(floatBuf, n, sampleRate)
            val now = SystemClock.elapsedRealtime()
            val waveSnapshot = floatBuf.copyOf(n)
            handler.post {
                waveView.updateSamples(waveSnapshot)
                if (!recording) return@post
                if (freq > 0) onVoicedSample(freq, now)
            }
        }
    }

    private fun onVoicedSample(freq: Float, now: Long) {
        if (calibrating) {
            calibSamples.add(freq)
            val elapsed = now - calibStart
            val pct = min(100f, maxOf(elapsed.toFloat() / CALIB_MAX_MS, calibSamples.size.toFloat() / CALIB_MIN_SAMPLES) * 100f)
            calibrationBar.progress = pct.toInt()
            if ((calibSamples.size >= CALIB_MIN_SAMPLES && elapsed >= CALIB_MIN_MS) || elapsed >= CALIB_MAX_MS) {
                finishCalibration(now)
            }
            return
        }
        val mean = baselineMean ?: return
        val std = baselineStd ?: return
        val z = (freq - mean) / std
        pitchHistory.add(PitchSample(now, freq, z))
        val cutoff = now - 20000L
        while (pitchHistory.isNotEmpty() && pitchHistory[0].t < cutoff) pitchHistory.removeAt(0)
        pitchView.addPoint(now, z)

        rollingPitch.add(freq)
        if (rollingPitch.size > 24) rollingPitch.removeAt(0)
        val jitter = computeJitter(rollingPitch)
        val stressScore = min(1f, 0.65f * min(abs(z), 3f) / 3f + 0.35f * min(jitter / 14f, 1f))
        updateLiveReadout(freq, z, stressScore)
    }

    private fun computeJitter(arr: List<Float>): Float {
        if (arr.size < 3) return 0f
        var sum = 0f; var n = 0
        for (i in 1 until arr.size) {
            val prev = arr[i - 1]; val curr = arr[i]
            if (prev > 0f) { sum += abs(curr - prev) / prev * 100f; n++ }
        }
        return if (n > 0) sum / n else 0f
    }

    private fun finishCalibration(now: Long) {
        calibrating = false
        calibrationBar.visibility = View.GONE
        val n = calibSamples.size
        val mean = calibSamples.sum() / n
        val variance = calibSamples.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / n
        baselineMean = mean
        baselineStd = kotlin.math.sqrt(variance).coerceAtLeast(6f)
        recordingStart = now
        statusText.text = "Запись идёт — говорите"
    }

    private fun zoneFromScore(score: Float) = when {
        score < 0.33f -> "calm"
        score < 0.66f -> "elevated"
        else -> "high"
    }
    private fun zoneColor(z: String) = when (z) { "calm" -> colCalm; "elevated" -> colAccent; else -> colStress }
    private fun zoneLabel(z: String) = when (z) {
        "calm" -> "Спокойно"; "elevated" -> "Повышенное напряжение"; else -> "Высокое напряжение"
    }

    private fun updateLiveReadout(freq: Float, z: Float, score: Float) {
        mPitch.text = "${freq.toInt()} Гц"
        mZ.text = (if (z >= 0) "+" else "") + String.format(Locale("ru"), "%.2f σ", z)
        val zone = zoneFromScore(score)
        gaugeBar.progress = (score * 100).toInt()
        gaugeBar.progressTintList = ColorStateList.valueOf(zoneColor(zone))
        gaugeLabel.text = zoneLabel(zone) + " — не является признаком лжи"
    }

    private fun stopRecording() {
        recording = false
        calibrating = false
        calibrationBar.visibility = View.GONE
        try { recognizer?.setRecognitionListener(null); recognizer?.stopListening(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        audioThread = null
        recordBtn.text = "●"
        recordBtn.background = bg(colPanel2, 32)
        statusText.text = "Запись остановлена. Нажмите, чтобы начать снова"
        gaugeLabel.text = "Запись остановлена"
        gaugeBar.progress = 0
        waveView.updateSamples(FloatArray(0))
    }

    override fun onDestroy() {
        if (recording) stopRecording()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (recording) stopRecording()
    }

    // ================= speech recognition =================

    private fun startSpeechRecognition() {
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                if (currentUtteranceStart == null) currentUtteranceStart = SystemClock.elapsedRealtime()
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    showError("Доступ к микрофону для распознавания речи запрещён.")
                    return
                }
                if (recording) handler.postDelayed({ if (recording) safeStartListening() }, 250)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                val start = currentUtteranceStart ?: SystemClock.elapsedRealtime()
                val end = SystemClock.elapsedRealtime()
                if (!text.isNullOrEmpty()) finalizeUtterance(text, start, end)
                currentUtteranceStart = null
                if (recording) handler.postDelayed({ if (recording) safeStartListening() }, 150)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrEmpty()) {
                    if (currentUtteranceStart == null) currentUtteranceStart = SystemClock.elapsedRealtime()
                    statusText.text = "Слушаю: «$text…»"
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        safeStartListening()
    }

    private fun safeStartListening() {
        try { recognizer?.startListening(recognizerIntent) } catch (_: Exception) {}
    }

    // ================= contradiction detection =================

    private fun finalizeUtterance(text: String, startT: Long, endT: Long) {
        val duration = maxOf(0.3f, (endT - startT) / 1000f)
        val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val rate = wordCount / duration
        val pauseBefore = if (lastUtteranceEnd != null) (startT - lastUtteranceEnd!!) / 1000f
            else (startT - recordingStart) / 1000f
        lastUtteranceEnd = endT

        val slice = pitchHistory.filter { it.t in (startT - 200)..(endT + 200) }
        var avgZ: Float? = null
        var zone = "calm"
        if (slice.isNotEmpty()) {
            avgZ = slice.map { it.z }.average().toFloat()
            zone = zoneFromScore(min(1f, abs(avgZ) / 2.5f))
        }

        val content = tokenizeContent(text)
        val nums = extractNumbers(text)
        val neg = NEGATION_RE.containsMatchIn(text.lowercase(Locale("ru")))

        val utterance = Utterance(
            id = nextUtteranceId++, text = text, time = Date(),
            rate = rate, pauseBefore = pauseBefore, avgZ = avgZ, zone = zone,
            contentWords = content, nums = nums, neg = neg
        )
        utterance.conflict = detectContradiction(content, nums, neg, utterances)

        utterances.add(utterance)
        renderUtterance(utterance)
        if (utterance.conflict != null) renderConflict(utterance, utterance.conflict!!)
        utteranceCount.text = utterances.size.toString()
    }

    private fun detectContradiction(content: List<String>, nums: List<String>, neg: Boolean, prior: List<Utterance>): Conflict? {
        var best: Conflict? = null
        var bestScore = 0f
        val from = maxOf(0, prior.size - 30)
        for (i in prior.size - 1 downTo from) {
            val p = prior[i]
            val sim = jaccard(content, p.contentWords)
            if (sim < 0.35f) continue
            val numsDiffer = nums.isNotEmpty() && p.nums.isNotEmpty() &&
                !(nums.size == p.nums.size && nums.indices.all { nums[it] == p.nums[it] })
            val negDiffer = neg != p.neg
            if (numsDiffer || negDiffer) {
                if (sim > bestScore) {
                    bestScore = sim
                    best = Conflict(
                        priorId = p.id, priorText = p.text,
                        reason = if (numsDiffer) "разные числа при совпадающей теме" else "отрицание не совпадает с предыдущим утверждением",
                        sim = sim
                    )
                }
            }
        }
        return best
    }

    // ================= rendering =================

    private fun renderUtterance(u: Utterance) {
        if (transcriptFeed.indexOfChild(transcriptEmpty) >= 0) transcriptFeed.removeView(transcriptEmpty)

        val stripeColor = if (u.conflict != null) colFlag else zoneColor(u.zone)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(colPanel2, 10)
        }
        card.addView(View(this).apply { setBackgroundColor(stripeColor) }, LinearLayout.LayoutParams(dp(4), -1))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this@VoiceMonitorActivity).apply {
            text = timeFmt.format(u.time); textSize = 11f; setTextColor(colTextDim); typeface = Typeface.MONOSPACE
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this@VoiceMonitorActivity).apply {
            text = "#${u.id}"; textSize = 11f; setTextColor(colTextDim); typeface = Typeface.MONOSPACE
        })
        body.addView(head)
        body.addView(TextView(this).apply {
            text = u.text; textSize = 14.5f; setTextColor(colText)
            setPadding(0, dp(6), 0, dp(6))
        })
        val zDisplay = if (u.avgZ == null) "—" else (if (u.avgZ!! >= 0) "+" else "") + String.format(Locale("ru"), "%.2fσ", u.avgZ)
        body.addView(TextView(this).apply {
            text = "отклонение тона: $zDisplay   ·   темп: ${String.format(Locale("ru"), "%.1f", u.rate)} сл/с   ·   пауза: ${String.format(Locale("ru"), "%.1f", u.pauseBefore)} с"
            textSize = 11f; setTextColor(colTextDim); typeface = Typeface.MONOSPACE
        })
        u.conflict?.let {
            body.addView(TextView(this).apply {
                text = "⚑ Возможная нестыковка с фразой #${it.priorId} (${it.reason})"
                textSize = 12f; setTextColor(colFlag); setPadding(0, dp(6), 0, 0)
            })
        }
        card.addView(body, LinearLayout.LayoutParams(0, -2, 1f))

        transcriptFeed.addView(card, 0, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        while (transcriptFeed.childCount > 50) transcriptFeed.removeViewAt(transcriptFeed.childCount - 1)
    }

    private fun renderConflict(u: Utterance, c: Conflict) {
        if (conflictLog.indexOfChild(conflictEmpty) >= 0) conflictLog.removeView(conflictEmpty)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(colPanel2, 10)
        }
        card.addView(View(this).apply { setBackgroundColor(colFlag) }, LinearLayout.LayoutParams(dp(4), -1))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        body.addView(TextView(this@VoiceMonitorActivity).apply {
            text = "Фраза #${c.priorId}  ↔  фраза #${u.id}"; textSize = 12.5f
            setTextColor(colText); typeface = Typeface.DEFAULT_BOLD
        })
        body.addView(TextView(this@VoiceMonitorActivity).apply {
            text = c.priorText; textSize = 13f; setTextColor(colText)
            background = bg(colPanel, 6); setPadding(dp(8), dp(6), dp(8), dp(6))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        body.addView(TextView(this@VoiceMonitorActivity).apply {
            text = u.text; textSize = 13f; setTextColor(colText)
            background = bg(colPanel, 6); setPadding(dp(8), dp(6), dp(8), dp(6))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        body.addView(TextView(this@VoiceMonitorActivity).apply {
            text = "Причина: ${c.reason} · схожесть темы ${(c.sim * 100).toInt()}%. Формальное сопоставление слов, не анализ смысла."
            textSize = 11.5f; setTextColor(colTextDim); setPadding(0, dp(6), 0, 0)
        })
        card.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        conflictLog.addView(card, 0, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        while (conflictLog.childCount > 50) conflictLog.removeViewAt(conflictLog.childCount - 1)
        conflictCount.text = conflictLog.childCount.toString()
    }

    // ================= toolbar actions =================

    private fun resetSession() {
        if (recording) stopRecording()
        utterances.clear(); nextUtteranceId = 1
        pitchHistory.clear(); rollingPitch.clear(); pitchView.clear()
        baselineMean = null; baselineStd = null
        lastUtteranceEnd = null; currentUtteranceStart = null
        transcriptFeed.removeAllViews(); transcriptFeed.addView(transcriptEmpty)
        conflictLog.removeAllViews(); conflictLog.addView(conflictEmpty)
        utteranceCount.text = "0"; conflictCount.text = "0"
        mPitch.text = "— Гц"; mZ.text = "— σ"; mRate.text = "— сл/с"; mPause.text = "— с"
        statusText.text = "Сессия сброшена. Нажмите на кнопку, чтобы начать запись"
        Toast.makeText(this, "Сессия сброшена", Toast.LENGTH_SHORT).show()
    }

    private fun copyTranscript() {
        if (utterances.isEmpty()) { Toast.makeText(this, "Транскрипт пока пуст", Toast.LENGTH_SHORT).show(); return }
        val text = utterances.joinToString("\n") { u ->
            val flag = u.conflict?.let { " [нестыковка с #${it.priorId}]" } ?: ""
            "[${timeFmt.format(u.time)}] #${u.id} (${u.zone}): ${u.text}$flag"
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Транскрипт", text))
        Toast.makeText(this, "Транскрипт скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
    }
}

// ================= data =================

private data class PitchSample(val t: Long, val freq: Float, val z: Float)

private class Utterance(
    val id: Int,
    val text: String,
    val time: Date,
    val rate: Float,
    val pauseBefore: Float,
    val avgZ: Float?,
    val zone: String,
    val contentWords: List<String>,
    val nums: List<String>,
    val neg: Boolean
) { var conflict: Conflict? = null }

private data class Conflict(val priorId: Int, val priorText: String, val reason: String, val sim: Float)

// ================= text heuristics =================

private val STOPWORDS = setOf(
    "и", "в", "не", "на", "что", "я", "с", "по", "это", "как", "а", "то", "все", "она", "так", "его", "но", "да",
    "ты", "к", "у", "же", "вы", "за", "бы", "только", "ее", "мне", "было", "вот", "от", "меня", "еще", "нет",
    "о", "из", "ему", "теперь", "когда", "даже", "ну", "вдруг", "ли", "если", "уже", "или", "ни", "быть", "был",
    "него", "до", "вас", "нибудь", "опять", "уж", "вам", "ведь", "там", "потом", "себя", "ничего", "ей", "может",
    "они", "тут", "где", "есть", "надо", "ней", "для", "мы", "тебя", "их", "чем", "была", "сам", "чтоб", "без",
    "будто", "чего", "раз", "тоже", "себе", "под", "будет", "ж", "тогда", "кто", "этот", "того", "потому",
    "этого", "какой", "совсем", "ним", "здесь", "этом", "один", "почти", "мой", "тем", "чтобы", "нее", "сейчас",
    "были", "куда", "зачем", "всех", "можно", "при", "наконец", "два", "об", "другой", "хоть", "после", "над",
    "больше", "тот", "через", "эти", "нас", "про", "всего", "них", "какая", "много", "разве", "три", "эту",
    "моя", "впрочем", "хорошо", "свою", "этой", "перед", "иногда", "лучше", "чуть", "том", "нельзя", "такой",
    "им", "более", "всю", "между"
)
private val NEGATION_RE = Regex("\\b(не|нет|никогда|ничего|нисколько)\\b")
private val CLEAN_RE = Regex("[^a-zа-яё0-9\\s.,]", RegexOption.IGNORE_CASE)
private val NUM_RE = Regex("\\d+([.,]\\d+)?")

private fun tokenizeContent(text: String): List<String> {
    val clean = CLEAN_RE.replace(text.lowercase(Locale("ru")), " ")
    return clean.split(Regex("\\s+")).filter { it.length > 2 && it !in STOPWORDS }
}

private fun extractNumbers(text: String): List<String> =
    NUM_RE.findAll(text).map { it.value.replace(',', '.') }.toList()

private fun jaccard(a: List<String>, b: List<String>): Float {
    if (a.isEmpty() || b.isEmpty()) return 0f
    val setA = a.toSet(); val setB = b.toSet()
    val inter = setA.count { it in setB }
    val union = (setA + setB).size
    return if (union == 0) 0f else inter.toFloat() / union.toFloat()
}

// ================= pitch detection (autocorrelation) =================

private fun autoCorrelate(buf: FloatArray, len: Int, sampleRate: Int): Pair<Float, Float> {
    var rms = 0f
    for (i in 0 until len) { val v = buf[i]; rms += v * v }
    rms = kotlin.math.sqrt(rms / len)
    if (rms < 0.012f) return -1f to rms

    var r1 = 0; var r2 = len - 1
    val thres = 0.2f
    for (i in 0 until len / 2) { if (abs(buf[i]) < thres) { r1 = i; break } }
    for (i in 1 until len / 2) { if (abs(buf[len - i]) < thres) { r2 = len - i; break } }
    if (r2 <= r1) return -1f to rms
    val size = r2 - r1
    if (size < 8) return -1f to rms
    val trimmed = FloatArray(size) { buf[r1 + it] }

    val c = FloatArray(size)
    for (i in 0 until size) {
        var sum = 0f
        for (j in 0 until size - i) sum += trimmed[j] * trimmed[j + i]
        c[i] = sum
    }
    var d = 0
    while (d < size - 1 && c[d] > c[d + 1]) d++
    var maxVal = -1f; var maxPos = -1
    for (i in d until size) { if (c[i] > maxVal) { maxVal = c[i]; maxPos = i } }
    if (maxPos <= 0) return -1f to rms
    var t0 = maxPos.toFloat()
    if (maxPos in 1 until size - 1) {
        val x1 = c[maxPos - 1]; val x2 = c[maxPos]; val x3 = c[maxPos + 1]
        val a = (x1 + x3 - 2 * x2) / 2f; val b = (x3 - x1) / 2f
        if (a != 0f) t0 -= b / (2f * a)
    }
    val freq = sampleRate / t0
    if (freq < 60f || freq > 500f) return -1f to rms
    return freq to rms
}

// ================= custom drawing views =================

private class WaveformView(context: Context) : View(context) {
    private var samples: FloatArray = FloatArray(0)
    private val linePaint = Paint().apply {
        color = Color.parseColor("#E8A33D"); style = Paint.Style.STROKE
        strokeWidth = 4f; isAntiAlias = true
    }
    private val idlePaint = Paint().apply {
        color = Color.parseColor("#2A2F3B"); strokeWidth = 3f
    }

    fun updateSamples(s: FloatArray) { samples = s; postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat(); val mid = h / 2
        if (samples.isEmpty()) { canvas.drawLine(0f, mid, w, mid, idlePaint); return }
        val path = Path()
        val step = maxOf(1, samples.size / maxOf(1, w.toInt()))
        var x = 0f
        var i = 0
        var first = true
        while (i < samples.size && x <= w) {
            val y = mid + samples[i] * mid * 0.9f
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            x += 1f; i += step
        }
        canvas.drawPath(path, linePaint)
    }
}

private class PitchChartView(context: Context) : View(context) {
    private data class Pt(val t: Long, val z: Float)
    private val points = ArrayList<Pt>()
    private val windowMs = 20000L
    private val calmPaint = Paint().apply { color = Color.parseColor("#4FA89B"); strokeWidth = 4f; isAntiAlias = true }
    private val elevPaint = Paint().apply { color = Color.parseColor("#E8A33D"); strokeWidth = 4f; isAntiAlias = true }
    private val highPaint = Paint().apply { color = Color.parseColor("#D1495B"); strokeWidth = 4f; isAntiAlias = true }
    private val gridPaint = Paint().apply { color = Color.parseColor("#2A2F3B"); strokeWidth = 2f }

    fun addPoint(t: Long, z: Float) {
        points.add(Pt(t, z))
        val cutoff = t - windowMs
        while (points.isNotEmpty() && points[0].t < cutoff) points.removeAt(0)
        postInvalidateOnAnimation()
    }

    fun clear() { points.clear(); postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat(); val mid = h / 2
        canvas.drawLine(0f, mid, w, mid, gridPaint)
        if (points.size < 2) return
        val now = points.last().t
        val t0 = now - windowMs
        val scaleY = (h / 2 - 8f) / 2.5f
        for (i in 1 until points.size) {
            val p0 = points[i - 1]; val p1 = points[i]
            if (p1.t < t0) continue
            val x0 = ((p0.t - t0).toFloat() / windowMs) * w
            val x1 = ((p1.t - t0).toFloat() / windowMs) * w
            val y0 = mid - p0.z.coerceIn(-2.5f, 2.5f) * scaleY
            val y1 = mid - p1.z.coerceIn(-2.5f, 2.5f) * scaleY
            val score = min(1f, abs(p1.z) / 2.5f)
            val paint = if (score < 0.33f) calmPaint else if (score < 0.66f) elevPaint else highPaint
            canvas.drawLine(x0, y0, x1, y1, paint)
        }
    }
}
