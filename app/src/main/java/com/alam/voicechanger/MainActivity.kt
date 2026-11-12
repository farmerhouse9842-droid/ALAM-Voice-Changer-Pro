package com.alam.voicechanger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var recording = false

    private lateinit var source: File
    private lateinit var output: File
    private lateinit var status: TextView
    private lateinit var effectPicker: Spinner
    private lateinit var startTrim: EditText
    private lateinit var endTrim: EditText
    private lateinit var endpoint: EditText
    private lateinit var pitchBar: SeekBar
    private lateinit var speedBar: SeekBar
    private lateinit var toneBar: SeekBar

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val effects = listOf(
        "Original", "Female Voice", "Funny Girl",
        "Funny Cartoon", "Deep", "Baby",
        "Chipmunk", "Monster", "Old Man",
        "Helium", "Robot", "Alien", "Echo",
        "Fast", "Slow", "Noise Gate",
        "Bass Boost", "Normalize"
    )

    private val themes = listOf(
        "Black Glass",
        "Neon Blue & Purple",
        "AMOLED Black",
        "Dark Gradient"
    )

    private var themeIndex = 0
    private var canvasColor = Color.rgb(8, 13, 25)
    private var cardColor = Color.rgb(19, 29, 50)
    private var accentColor = Color.rgb(26, 109, 172)
    private var secondaryColor = Color.rgb(34, 83, 138)

    private val white = Color.WHITE
    private val muted = Color.rgb(171, 192, 220)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun choosePalette(index: Int) {
        themeIndex = index.coerceIn(0, themes.lastIndex)

        when (themeIndex) {
            0 -> {
                canvasColor = Color.rgb(8, 13, 25)
                cardColor = Color.rgb(23, 34, 55)
                accentColor = Color.rgb(26, 109, 172)
                secondaryColor = Color.rgb(39, 69, 111)
            }

            1 -> {
                canvasColor = Color.rgb(14, 9, 36)
                cardColor = Color.rgb(32, 22, 67)
                accentColor = Color.rgb(105, 49, 207)
                secondaryColor = Color.rgb(16, 126, 169)
            }

            2 -> {
                canvasColor = Color.BLACK
                cardColor = Color.rgb(17, 17, 17)
                accentColor = Color.rgb(30, 104, 93)
                secondaryColor = Color.rgb(34, 34, 34)
            }

            else -> {
                canvasColor = Color.rgb(11, 23, 42)
                cardColor = Color.rgb(28, 42, 66)
                accentColor = Color.rgb(28, 130, 150)
                secondaryColor = Color.rgb(72, 52, 122)
            }
        }
    }

    private fun panelBg() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(cardColor, secondaryColor)
    ).apply {
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), Color.argb(105, 160, 193, 232))
    }

    private fun actionBg() = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(accentColor, secondaryColor)
    ).apply {
        cornerRadius = dp(16).toFloat()
    }

    private fun text(
        value: String,
        size: Float = 15f,
        color: Int = Color.WHITE
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, dp(5), 0, dp(7))
    }

    private fun notice(message: String) {
        main.post {
            if (::status.isInitialized) status.text = message
        }
    }

    private fun button(
        value: String,
        action: () -> Unit
    ) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(white)
        textSize = 15f
        backgroundTintList = null
        background = actionBg()
        minHeight = dp(50)
        setOnClickListener { action() }
    }

    private fun field(
        hintText: String,
        default: String = ""
    ) = EditText(this).apply {
        setSingleLine(true)
        setTextColor(white)
        setHintTextColor(muted)
        hint = hintText
        setText(default)
        textSize = 14f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        choosePalette(
            getPreferences(0).getInt("theme", 0)
        )

        source = File(cacheDir, "source.m4a")
        output = File(cacheDir, "processed.wav")

        val scroll = ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    canvasColor,
                    if (themeIndex == 3)
                        secondaryColor
                    else canvasColor
                )
            )
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(16), dp(20),
                dp(16), dp(30)
            )
        }

        scroll.addView(root)

        fun card(): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(15), dp(13),
                    dp(15), dp(14)
                )
                background = panelBg()
                elevation = dp(3).toFloat()

                root.addView(
                    this,
                    LinearLayout.LayoutParams(
                        -1, -2
                    ).apply {
                        bottomMargin = dp(14)
                    }
                )
            }

        val heading = text(
            "ALAM VOICE CHANGER PRO", 22f
        ).apply {
            setTypeface(null, Typeface.BOLD)
        }

        root.addView(heading)
        root.addView(
            text(
                "OFFLINE VOICE STUDIO | v0.6",
                12f, muted
            )
        )

        val themeCard = card()
        themeCard.addView(
            text("Choose Theme", 18f)
        )

        val themePicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                themes
            )
            setBackgroundColor(cardColor)
            setSelection(themeIndex)
        }

        themeCard.addView(themePicker)

        themeCard.addView(
            button("Apply Theme") {
                val selected =
                    themePicker.selectedItemPosition

                if (selected != themeIndex) {
                    getPreferences(0).edit()
                        .putInt("theme", selected)
                        .apply()
                    recreate()
                } else {
                    notice("Theme already selected")
                }
            }
        )

        val recordCard = card()
        recordCard.addView(
            text("Record or Import Audio", 18f)
        )

        status = text(
            "Ready to record", 13f, muted
        )
        recordCard.addView(status)

        recordCard.addView(
            button("Record / Stop") {
                if (recording) stopRecord()
                else startRecord()
            }
        )

        recordCard.addView(
            button("Import Audio") {
                startActivityForResult(
                    Intent(
                        Intent.ACTION_OPEN_DOCUMENT
                    ).apply {
                        type = "audio/*"
                        addCategory(
                            Intent.CATEGORY_OPENABLE
                        )
                    },
                    201
                )
            }
        )

        val effectsCard = card()
        effectsCard.addView(
            text("Voice Presets", 18f)
        )

        effectPicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                effects
            )
            setBackgroundColor(cardColor)
        }

        effectsCard.addView(effectPicker)
        effectsCard.addView(
            text("Advanced Voice Tuning", 17f)
        )

        fun control(
            label: String,
            minValue: Float,
            maxValue: Float,
            initial: Float
        ): SeekBar {

            val caption = text(
                "$label  ${"%.2f".format(initial)}x",
                14f, muted
            )

            effectsCard.addView(caption)

            val bar = SeekBar(this).apply {

                // Fixed: explicitly use SeekBar.max
                this.max = 100

                progress = (
                    (initial - minValue) /
                    (maxValue - minValue) * 100f
                ).toInt().coerceIn(0, 100)

                progressTintList =
                    ColorStateList.valueOf(accentColor)

                thumbTintList =
                    ColorStateList.valueOf(accentColor)

                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {

                        override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progressValue: Int,
                            fromUser: Boolean
                        ) {
                            val current = minValue +
                                (maxValue - minValue) *
                                progressValue / 100f

                            caption.text =
                                "$label  ${"%.2f".format(current)}x"
                        }

                        override fun onStartTrackingTouch(
                            seekBar: SeekBar?
                        ) {}

                        override fun onStopTrackingTouch(
                            seekBar: SeekBar?
                        ) {}
                    }
                )
            }

            effectsCard.addView(bar)
            return bar
        }

        pitchBar = control(
            "Pitch", 0.5f, 2f, 1f
        )

        speedBar = control(
            "Speed", 0.5f, 2f, 1f
        )

        toneBar = control(
            "Formant-style Tone", 0.7f, 1.5f, 1f
        )

        effectsCard.addView(
            button("Natural Female (Offline)") {
                effectPicker.setSelection(
                    effects.indexOf("Female Voice")
                )
                pitchBar.progress = 33
                speedBar.progress = 33
                toneBar.progress = 68

                notice("Female preset selected")
            }
        )

        effectsCard.addView(
            button("Reset Tuning") {
                pitchBar.progress = 33
                speedBar.progress = 33
                toneBar.progress = 38
                notice("Tuning reset")
            }
        )

        effectsCard.addView(
            text(
                "Offline voice effects are approximations, not neural AI conversion.",
                12f, muted
            )
        )

        val editCard = card()
        editCard.addView(
            text("Trim and Export", 18f)
        )

        startTrim = field(
            "Start seconds", "0"
        )

        endTrim = field(
            "End seconds (blank = full)"
        )

        editCard.addView(startTrim)
        editCard.addView(endTrim)

        editCard.addView(
            button("Apply Effect / Create WAV") {
                process()
            }
        )

        editCard.addView(
            button("Play Original") {
                play(source)
            }
        )

        editCard.addView(
            button("Play Result") {
                play(output)
            }
        )

        editCard.addView(
            button("Stop Playback") {
                player?.release()
                player = null
                notice("Stopped")
            }
        )

        editCard.addView(
            button("Save WAV") {
                save()
            }
        )

        val aiCard = card()
        aiCard.addView(
            text("Online AI Voice", 18f)
        )

        aiCard.addView(
            text(
                "Requires a compatible HTTPS server. Audio will be uploaded.",
                12f, muted
            )
        )

        endpoint = field(
            "https://your-server.example/convert",
            getPreferences(0)
                .getString("endpoint", "") ?: ""
        )

        aiCard.addView(endpoint)

        aiCard.addView(
            button("AI Female") {
                ai("female")
            }
        )

        aiCard.addView(
            button("AI Male") {
                ai("male")
            }
        )

        setContentView(scroll)
    }

    private fun level(
        bar: SeekBar,
        minValue: Float,
        maxValue: Float
    ): Float {
        return minValue +
            (maxValue - minValue) *
            bar.progress / 100f
    }

    private fun startRecord() {
        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
            return
        }

        try {
            player?.release()
            player = null

            recorder = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder!!.apply {
                setAudioSource(
                    MediaRecorder.AudioSource.MIC
                )
                setOutputFormat(
                    MediaRecorder.OutputFormat.MPEG_4
                )
                setAudioEncoder(
                    MediaRecorder.AudioEncoder.AAC
                )
                setOutputFile(source.absolutePath)
                prepare()
                start()
            }

            recording = true
            output.delete()
            notice("Recording... tap again to stop")

        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            notice("Recording failed: ${e.message}")
        }
    }

    private fun stopRecord() {
        try {
            recorder?.stop()
            notice("Recording ready")
        } catch (e: Exception) {
            source.delete()
            notice("Recording too short")
        } finally {
            recorder?.release()
            recorder = null
            recording = false
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode, resultCode, data
        )

        if (
            resultCode != RESULT_OK ||
            data?.data == null
        ) return

        val uri = data.data!!

        when (requestCode) {

            201 -> worker.execute {
                try {
                    val imported = File(
                        cacheDir, "imported_audio"
                    )

                    contentResolver.openInputStream(uri)!!
                        .use { input ->
                            imported.outputStream().use {
                                input.copyTo(it)
                            }
                        }

                    source = imported
                    output.delete()
                    notice("Audio imported")

                } catch (e: Exception) {
                    notice("Import failed: ${e.message}")
                }
            }

            202 -> worker.execute {
                try {
                    contentResolver.openOutputStream(uri)!!
                        .use { out ->
                            output.inputStream().use {
                                it.copyTo(out)
                            }
                        }

                    notice("WAV saved")

                } catch (e: Exception) {
                    notice("Save failed: ${e.message}")
                }
            }
        }
    }

    private fun process() {
        if (!source.exists()) {
            notice("Record or import first")
            return
        }

        val effect =
            effects[effectPicker.selectedItemPosition]

        val start =
            startTrim.text.toString().toFloatOrNull()
                ?: 0f

        val end =
            endTrim.text.toString().toFloatOrNull()

        val pitch = level(
            pitchBar, 0.5f, 2f
        )

        val speed = level(
            speedBar, 0.5f, 2f
        )

        val tone = level(
            toneBar, 0.7f, 1.5f
        )

        worker.execute {
            try {
                notice("Processing $effect...")

                val audio = AudioEngine.decode(source)

                val duration =
                    audio.samples.size.toFloat() /
                    audio.rate

                val result = AudioEngine.transform(
                    audio,
                    effect,
                    start,
                    end ?: duration,
                    pitch,
                    speed,
                    tone
                )

                AudioEngine.saveWav(
                    result, output
                )

                notice("Ready: $effect")

            } catch (e: Exception) {
                notice("Processing error: ${e.message}")
            }
        }
    }

    private fun play(file: File) {
        if (
            !file.exists() ||
            file.length() == 0L
        ) {
            notice("Audio not ready")
            return
        }

        try {
            player?.release()

            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()

                setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                    notice("Playback complete")
                }

                start()
            }

            notice("Playing...")

        } catch (e: Exception) {
            player?.release()
            player = null
            notice("Playback failed: ${e.message}")
        }
    }

    private fun save() {
        if (!output.exists()) {
            notice("Apply an effect first")
            return
        }

        startActivityForResult(
            Intent(
                Intent.ACTION_CREATE_DOCUMENT
            ).apply {
                type = "audio/wav"
                addCategory(
                    Intent.CATEGORY_OPENABLE
                )
                putExtra(
                    Intent.EXTRA_TITLE,
                    "ALAM-voice-${System.currentTimeMillis()}.wav"
                )
            },
            202
        )
    }

    private fun ai(voice: String) {
        val address =
            endpoint.text.toString().trim()

        if (!address.startsWith("https://")) {
            notice("Enter a valid HTTPS AI server URL")
            return
        }

        if (!source.exists()) {
            notice("Record or import audio first")
            return
        }

        getPreferences(0).edit()
            .putString("endpoint", address)
            .apply()

        worker.execute {
            var connection: HttpURLConnection? = null

            try {
                notice("Preparing AI audio...")

                val temp = File(
                    cacheDir, "ai_input.wav"
                )

                AudioEngine.saveWav(
                    AudioEngine.decode(source),
                    temp
                )

                require(temp.length() <= 15_000_000) {
                    "Audio exceeds 15 MB"
                }

                val separator =
                    if (address.contains("?")) "&" else "?"

                connection = (
                    URL(
                        "$address${separator}voice=$voice"
                    ).openConnection()
                        as HttpURLConnection
                ).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 90000
                    doOutput = true
                    instanceFollowRedirects = false

                    setRequestProperty(
                        "Content-Type",
                        "audio/wav"
                    )

                    setRequestProperty(
                        "Accept",
                        "audio/wav"
                    )

                    setFixedLengthStreamingMode(
                        temp.length()
                    )
                }

                connection.outputStream.use { out ->
                    temp.inputStream().use {
                        it.copyTo(out)
                    }
                }

                require(connection.responseCode == 200) {
                    "Server HTTP ${connection.responseCode}"
                }

                val candidate = File(
                    cacheDir, "ai_result.tmp"
                )

                connection.inputStream.use { input ->
                    candidate.outputStream().use {
                        input.copyTo(it)
                    }
                }

                require(
                    candidate.length() >= 44 &&
                    candidate.inputStream().use {
                        val header = ByteArray(4)
                        it.read(header) == 4 &&
                            String(
                                header,
                                Charsets.US_ASCII
                            ) == "RIFF"
                    }
                ) {
                    "Server did not return WAV"
                }

                candidate.copyTo(
                    output, overwrite = true
                )
                candidate.delete()

                notice("AI $voice result ready")

            } catch (e: Exception) {
                notice("AI unavailable: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }
    }

    override fun onDestroy() {
        if (recording) stopRecord()

        player?.release()
        recorder?.release()
        worker.shutdown()

        super.onDestroy()
    }
}
