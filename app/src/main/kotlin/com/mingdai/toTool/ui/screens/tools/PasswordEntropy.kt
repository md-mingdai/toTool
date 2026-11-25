package com.mingdai.toTool.ui.screens.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class PasswordEntropyTab { SENSORS, DRAW }

internal data class PasswordEntropySource(
    val id: String,
    val label: String,
    val sensorType: Int? = null,
    val permission: String? = null,
    val microphone: Boolean = false,
    val camera: Boolean = false,
)

internal data class PasswordDrawPoint(
    val x: Float,
    val y: Float,
    val timeNanos: Long,
)

internal data class PasswordEntropyCollection(
    val parts: List<ByteArray>,
    val notes: List<String>,
)

/** 密码生成的额外熵源：设备传感器、麦克风、摄像头以及用户绘制笔迹。 */
internal object PasswordEntropy {
    private data class SensorSpec(val id: String, val label: String, val type: Int)

    private val sensorSpecs = listOf(
        SensorSpec("gravity", "重力", Sensor.TYPE_GRAVITY),
        SensorSpec("magnetic", "磁力计", Sensor.TYPE_MAGNETIC_FIELD),
        SensorSpec("accelerometer", "加速度", Sensor.TYPE_ACCELEROMETER),
        SensorSpec("gyroscope", "陀螺仪", Sensor.TYPE_GYROSCOPE),
        SensorSpec("light", "光线", Sensor.TYPE_LIGHT),
        SensorSpec("proximity", "距离", Sensor.TYPE_PROXIMITY),
        SensorSpec("pressure", "气压", Sensor.TYPE_PRESSURE),
        SensorSpec("rotation", "旋转矢量", Sensor.TYPE_ROTATION_VECTOR),
    )

    fun sources(context: Context): List<PasswordEntropySource> {
        val manager = context.getSystemService(SensorManager::class.java)
        val result = sensorSpecs.mapNotNull { spec ->
            if (manager?.getDefaultSensor(spec.type) == null) {
                null
            } else {
                PasswordEntropySource(
                    id = spec.id,
                    label = spec.label,
                    sensorType = spec.type,
                )
            }
        }.toMutableList()
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            result += PasswordEntropySource(
                id = "microphone",
                label = "麦克风",
                permission = Manifest.permission.RECORD_AUDIO,
                microphone = true,
            )
        }
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            result += PasswordEntropySource(
                id = "camera",
                label = "摄像头",
                permission = Manifest.permission.CAMERA,
                camera = true,
            )
        }
        return result
    }

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun collect(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        selected: List<PasswordEntropySource>,
    ): PasswordEntropyCollection {
        val parts = mutableListOf<ByteArray>()
        val notes = mutableListOf<String>()

        val sensorTypes = selected.mapNotNull { it.sensorType }
        if (sensorTypes.isNotEmpty()) {
            sampleSensors(context, sensorTypes)?.let { parts += it }
                ?: notes.add("传感器采样失败")
        }
        if (selected.any { it.microphone }) {
            if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                notes.add("麦克风未授权")
            } else {
                sampleMicrophone()?.let { parts += it } ?: notes.add("麦克风采样失败")
            }
        }
        if (selected.any { it.camera }) {
            if (!hasPermission(context, Manifest.permission.CAMERA)) {
                notes.add("摄像头未授权")
            } else {
                sampleCamera(context, lifecycleOwner)?.let { parts += it }
                    ?: notes.add("摄像头采样失败")
            }
        }
        if (selected.isEmpty()) notes.add("未选择熵源")
        return PasswordEntropyCollection(parts, notes)
    }

    fun mix(parts: List<ByteArray>): SecureRandom {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        digest.update(SecureRandom().generateSeed(32))
        val random = SecureRandom()
        random.setSeed(digest.digest())
        return random
    }

    fun hashDrawing(points: List<PasswordDrawPoint?>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(1 + 4 + 4 + 8)
        points.forEach { point ->
            buffer.clear()
            if (point == null) {
                buffer.put(0.toByte())
            } else {
                buffer.put(1.toByte())
                buffer.putFloat(point.x)
                buffer.putFloat(point.y)
                buffer.putLong(point.timeNanos)
            }
            buffer.flip()
            digest.update(buffer)
        }
        return digest.digest()
    }

    private suspend fun sampleSensors(context: Context, types: List<Int>): ByteArray? =
        withContext(Dispatchers.Main.immediate) {
            val manager = context.getSystemService(SensorManager::class.java) ?: return@withContext null
            val digest = MessageDigest.getInstance("SHA-256")
            var events = 0
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val buffer = ByteBuffer.allocate(4 + event.values.size * 4 + 8)
                    buffer.putInt(event.sensor.type)
                    event.values.forEach { buffer.putFloat(it) }
                    buffer.putLong(event.timestamp)
                    digest.update(buffer.array())
                    events++
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            val registered = types.mapNotNull { type ->
                manager.getDefaultSensor(type)?.also {
                    manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
                }
            }
            if (registered.isEmpty()) return@withContext null
            try {
                delay(400)
            } finally {
                manager.unregisterListener(listener)
            }
            if (events == 0) null else digest.digest()
        }

    private suspend fun sampleMicrophone(): ByteArray? = withContext(Dispatchers.IO) {
        var recorder: AudioRecord? = null
        try {
            val sampleRate = 16_000
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) return@withContext null
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, 4_096),
            )
            recorder = record
            if (record.state != AudioRecord.STATE_INITIALIZED) return@withContext null
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(2_048)
            val deadline = System.nanoTime() + 300_000_000L
            record.startRecording()
            while (System.nanoTime() < deadline) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) digest.update(buffer, 0, read)
            }
            digest.digest()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
        }
    }

    private suspend fun sampleCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
    ): ByteArray? {
        if (!hasPermission(context, Manifest.permission.CAMERA)) return null
        val provider = withContext(Dispatchers.IO) {
            runCatching {
                ProcessCameraProvider.getInstance(context).get(6, TimeUnit.SECONDS)
            }.getOrNull()
        } ?: return null
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val frame = CompletableDeferred<ByteArray?>()
        val executor = Executors.newSingleThreadExecutor()
        analysis.setAnalyzer(executor) { image ->
            try {
                if (!frame.isCompleted) {
                    val output = ByteArrayOutputStream()
                    image.planes.forEach { plane ->
                        val buffer = plane.buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        output.write(bytes)
                    }
                    frame.complete(output.toByteArray())
                }
            } catch (e: Exception) {
                frame.complete(null)
            } finally {
                image.close()
            }
        }
        return try {
            withContext(Dispatchers.Main.immediate) {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis,
                )
            }
            withTimeoutOrNull(2_500L) { frame.await() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { provider.unbind(analysis) }
            executor.shutdownNow()
        }
    }
}
