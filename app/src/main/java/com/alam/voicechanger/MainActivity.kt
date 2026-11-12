package com.alam.voicechanger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
    private var isRecording = false

    private lateinit var status: TextView
    private lateinit var source: File
    private lateinit var output: File
    private lateinit var startTrim: EditText
    private lateinit var endTrim: EditText
    private lateinit var endpoint: EditText
    private lateinit var effectPicker: Spinner

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val effects = listOf(
        "Original",
        "Female Voice",
        "Funny Girl",
        "Funny Cartoon",
        "Deep",
        "Baby",
        "Chipmunk",
        "Monster",
        "Old Man",
        "Helium",
        "Robot",
        "Alien",
        "Echo",
        "Fast",
        "Slow",
        "Noise Gate",
        "Bass Boost",
        "Normalize"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        source = File(cacheDir, "source.m4a")
        output = File(cacheDir, "processed.wav")

        val scroll = ScrollView(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 38, 26, 38)
            setBackgroundColor(Color.rgb(8, 13, 25))
        }

        scroll.addView(root)

        fun label(t: String, sz: Float = 15f): TextView =
            TextView(this).apply {
                text = t
                textSize = sz
                setTextColor(Color.WHITE)
                setPadding(0, 9, 0, 12)
                root.addView(this)
            }

        fun button(t: String, action: () -> Unit) {
            root.addView(
                Button(this).apply {
                    text = t
                    setTextColor(Color.WHITE)
                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(
                            Color.rgb(30, 91, 145)
                        )
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = 8
                }
            )
        }

        fun field(hint: String, default: String = ""): EditText =
            EditText(this).apply {
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.LTGRAY)
                this.hint = hint
                setText(default)
                root.addView(this)
            }

        label("ALAM VOICE CHANGER PRO", 24f)
        label("OFFLINE FEMALE + FUNNY VOICE | v0.3", 12f)

        status = label("Record or import audio to begin")

        button("RECORD / STOP") {
            if (isRecording) stopRecord() else startRecord()
        }

        button("IMPORT AUDIO") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "audio/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                },
                201
            )
        }

        label("OFFLINE VOICE EFFECTS", 18f)

        effectPicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                effects
            )
            root.addView(this)
        }

        startTrim = field("Start seconds", "0")
        endTrim = field("End seconds (blank = full length)")

        button("APPLY EFFECT / CREATE WAV") {
            process()
        }

        button("PLAY ORIGINAL") {
            play(source)
        }

        button("PLAY RESULT") {
            play(output)
        }

        button("SAVE / SHARE WAV") {
            save()
        }

        label("ONLINE AI VOICE", 18f)

        label(
            "Optional HTTPS AI server. Audio is uploaded to the configured server.",
            12f
        )

        endpoint = field("https://your-server.example/convert")
        endpoint.setText(
            getPreferences(0).getString("endpoint", "")
        )

        button("AI FEMALE VOICE") {
            ai("female")
        }

        button("AI MALE VOICE") {
            ai("male")
        }

        label(
            "Offline effects work without internet. Natural AI voices require a compatible server.",
            12f
        )

        setContentView(scroll)
    }

    private fun notifyUser(message: String) {
        main.post {
            status.text = message
        }
    }

    private fun startRecord() {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
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
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(source.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            notifyUser("Recording... tap again to stop")

        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            notifyUser("Record failed: ${e.message}")
        }
    }

    private fun stopRecord() {
        try {
            recorder?.stop()
            notifyUser("Recording ready")
        } catch (e: Exception) {
            source.delete()
            notifyUser("Recording too short")
        } finally {
            recorder?.release()
            recorder = null
            isRecording = false
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (
            requestCode == 201 &&
            resultCode == RESULT_OK &&
            data?.data != null
        ) {
            val uri = data.data!!

            worker.execute {
                try {
                    val imported = File(cacheDir, "imported_audio")

                    contentResolver.openInputStream(uri)!!.use { input ->
                        imported.outputStream().use {
                            input.copyTo(it)
                        }
                    }

                    source = imported
                    output.delete()
                    notifyUser("Audio imported")

                } catch (e: Exception) {
                    notifyUser("Import failed: ${e.message}")
                }
            }

        } else if (
            requestCode == 202 &&
            resultCode == RESULT_OK &&
            data?.data != null
        ) {
            val uri = data.data!!

            worker.execute {
                try {
                    contentResolver.openOutputStream(uri)!!.use { out ->
                        output.inputStream().use {
                            it.copyTo(out)
                        }
                    }
                    notifyUser("WAV saved")
                } catch (e: Exception) {
                    notifyUser("Save failed: ${e.message}")
                }
            }
        }
    }

    private fun process() {
        if (!source.exists()) {
            notifyUser("Record or import first")
            return
        }

        val effect = effects[effectPicker.selectedItemPosition]
        val start = startTrim.text.toString().toFloatOrNull() ?: 0f
        val end = endTrim.text.toString().toFloatOrNull()

        worker.execute {
            try {
                notifyUser("Processing: $effect")

                val audio = AudioEngine.decode(source)
                val maxEnd =
                    audio.samples.size.toFloat() / audio.rate

                val selectedEffect =
                    if (effect == "Funny Cartoon") {
                        "Chipmunk"
                    } else {
                        effect
                    }

                val result = AudioEngine.transform(
                    audio,
                    selectedEffect,
                    start,
                    end ?: maxEnd
                )

                AudioEngine.saveWav(result, output)

                notifyUser("Ready: $effect | WAV created")

            } catch (e: Exception) {
                notifyUser("Processing error: ${e.message}")
            }
        }
    }

    private fun play(file: File) {
        if (!file.exists() || file.length() == 0L) {
            notifyUser("Audio not ready")
            return
        }

        try {
            player?.release()

            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    notifyUser("Playback complete")
                }
                start()
            }

            notifyUser("Playing...")

        } catch (e: Exception) {
            player?.release()
            player = null
            notifyUser("Playback failed: ${e.message}")
        }
    }

    private fun save() {
        if (!output.exists()) {
            notifyUser("Apply an effect first")
            return
        }

        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "audio/wav"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(
                    Intent.EXTRA_TITLE,
                    "ALAM-voice-${System.currentTimeMillis()}.wav"
                )
            },
            202
        )
    }

    private fun ai(voice: String) {
        val url = endpoint.text.toString().trim()

        if (!url.startsWith("https://")) {
            notifyUser("Enter a valid HTTPS AI server URL")
            return
        }

        if (!source.exists()) {
            notifyUser("Record or import audio first")
            return
        }

        getPreferences(0).edit()
            .putString("endpoint", url)
            .apply()

        worker.execute {
            var connection: HttpURLConnection? = null

            try {
                notifyUser("Preparing audio for AI...")

                val temp = File(cacheDir, "ai_input.wav")

                AudioEngine.saveWav(
                    AudioEngine.decode(source),
                    temp
                )

                require(temp.length() <= 15_000_000) {
                    "Audio too large (max 15 MB)"
                }

                val separator =
                    if (url.contains("?")) "&" else "?"

                connection = (
                    URL("$url${separator}voice=$voice")
                        .openConnection() as HttpURLConnection
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

                    setFixedLengthStreamingMode(temp.length())
                }

                connection.outputStream.use { out ->
                    temp.inputStream().use {
                        it.copyTo(out)
                    }
                }

                require(connection.responseCode == 200) {
                    "Server HTTP ${connection.responseCode}"
                }

                val contentType =
                    connection.contentType?.lowercase() ?: ""

                require(
                    contentType.contains("audio/") ||
                    contentType.contains("octet-stream")
                ) {
                    "Server must return WAV audio"
                }

                val candidate = File(
                    cacheDir,
                    "ai_result.tmp"
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
                            String(header, Charsets.US_ASCII) == "RIFF"
                    }
                ) {
                    "Server did not return WAV"
                }

                candidate.copyTo(output, overwrite = true)
                candidate.delete()

                notifyUser("AI $voice voice ready")

            } catch (e: Exception) {
                notifyUser("AI unavailable: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }
    }

    override fun onDestroy() {
        if (isRecording) {
            stopRecord()
        }

        player?.release()
        recorder?.release()
        worker.shutdown()

        super.onDestroy()
    }
}
