package com.phonemic

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object ResidentQwenModel {
    val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>())
    @Volatile private var model: OfflineRecognizer? = null
    private var initializations = 0
    private var detector: Vad? = null
    private var speaker: SpeakerEmbeddingExtractor? = null

    fun speakerExtractor(context: Context): SpeakerEmbeddingExtractor {
        speaker?.let { return it }
        return SpeakerEmbeddingExtractor(context.applicationContext.assets, SpeakerEmbeddingExtractorConfig(
            model = "model-speaker/campplus.onnx", numThreads = 2,
        )).also { speaker = it }
    }

    fun voiceDetector(context: Context): Vad {
        detector?.let { return it }
        return newVoiceDetector(context).also { detector = it }
    }
    fun newVoiceDetector(context: Context): Vad = Vad(context.applicationContext.assets, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "model-vad/silero_vad.onnx", threshold = 0.35f,
                minSpeechDuration = 0.12f, minSilenceDuration = 0.9f,
                maxSpeechDuration = 20f,
            ), numThreads = 1,
        ))

    fun isReady(): Boolean = model != null
    fun isBusy(): Boolean = worker.activeCount != 0 || worker.queue.isNotEmpty()

    // Access and inference run on the shared worker; activities never own the model.
    fun load(context: Context): OfflineRecognizer {
        model?.let { return it }
        val started = System.nanoTime()
        Log.i("PhoneMicModel", "Loading local Qwen model")
        val loaded = BilingualSpeechModel.create(context.applicationContext)
        model = loaded
        initializations++
        Log.i("PhoneMicModel", "Local model ready: initialization=$initializations, loadMs=${(System.nanoTime() - started) / 1000000}")
        return loaded
    }

}
