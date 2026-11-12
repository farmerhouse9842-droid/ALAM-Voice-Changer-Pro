package com.alam.voicechanger

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.content.pm.PackageManager
import android.os.Build
import android.widget.*
import java.io.File

class MainActivity : Activity() {

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private lateinit var audioFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioFile = File(cacheDir, "voice_record.m4a")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(35, 50, 35, 20)
            setBackgroundColor(0xFF101827.toInt())
        }

        fun addButton(title: String, action: () -> Unit) {
            val button = Button(this).apply {
                text = title
                setOnClickListener { action() }
            }
            layout.addView(button)
        }

        val title = TextView(this).apply {
            text = "ALAM VOICE CHANGER PRO"
            textSize = 23f
            setTextColor(-1)
        }
        layout.addView(title)

        addButton("Record Voice") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO), 100
                )
            } else {
                startRecording()
            }
        }

        addButton("Stop Recording") {
            stopRecording()
        }

        addButton("Play Original") {
            playVoice(1.0f, 1.0f)
        }

        addButton("Funny Voice") {
            playVoice(1.7f, 1.0f)
        }

        addButton("Deep Voice") {
            playVoice(0.7f, 1.0f)
        }

        addButton("Baby Voice") {
            playVoice(2.0f, 1.2f)
        }

        addButton("Slow Voice") {
            playVoice(1.0f, 0.7f)
        }

        addButton("Fast Voice") {
            playVoice(1.0f, 1.5f)
        }

        addButton("Stop Playback") {
            player?.release()
            player = null
        }

        setContentView(layout)
    }

    private fun startRecording() {
        try {
            stopRecording()

            recorder = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            Toast.makeText(this, "Recording...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        } finally {
            recorder?.release()
            recorder = null
        }
    }

    private fun playVoice(pitch: Float, speed: Float) {
        if (!audioFile.exists()) {
            Toast.makeText(this, "Record first", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                playbackParams = android.media.PlaybackParams()
                    .setPitch(pitch)
                    .setSpeed(speed)
                setOnCompletionListener {
                    it.release()
                    player = null
                }
                start()
            }
        } catch (e: Exception) {
            player?.release()
            player = null
            Toast.makeText(this, "Playback failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        stopRecording()
        player?.release()
        player = null
        super.onDestroy()
    }
}
