package com.phonemic

import android.content.Context
import android.os.Build
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.Vad

object BilingualSpeechModel {
    private fun inferenceThreads(): Int {
        // MT6993: 4 threads beat both bracketing 3-thread runs with identical text.
        // Keep the existing setting on hardware that has not been measured.
        return if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.equals("MT6993", ignoreCase = true)) 4 else 3
    }
    fun create(context: Context): OfflineRecognizer {
        val directory = "model-qwen"
        return OfflineRecognizer(
            assetManager = context.assets,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = "$directory/conv_frontend.onnx",
                        encoder = "$directory/encoder.int8.onnx",
                        decoder = "$directory/decoder.int8.onnx",
                        tokenizer = "$directory/tokenizer",
                        maxTotalLen = 1024,
                        maxNewTokens = 256,
                    ),
                    numThreads = inferenceThreads(),
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            ),
        )
    }

    fun decode(recognizer: OfflineRecognizer, samples: FloatArray, hotwords: String = ""): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        try {
            // Qwen reads per-stream context; the hotword CreateStream overload is transducer-only.
            if (hotwords.isNotBlank()) stream.setOption("hotwords", hotwords)
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            return clean(recognizer.getResult(stream).text)
        } finally { stream.release() }
    }

    fun transcribe(recognizer: OfflineRecognizer, samples: FloatArray, detector: Vad, hotwords: String = "", acceptBoundary: ((FloatArray) -> Boolean)? = null, accept: ((FloatArray) -> Boolean)? = null): String {
        var text = ""
        for (chunk in SpeechAudio.chunks(samples, detector, acceptBoundary = acceptBoundary, accept = accept)) {
            text = combine(text, decode(recognizer, chunk, hotwords))
        }
        return text
    }

    fun clean(text: String): String = text.trim()
        .replace(Regex("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])"), "")

    fun correct(text: String, hotwords: String): String {
        var result = clean(text)
        for (word in hotwords.split(',').map { it.trim() }.filter { it.isNotEmpty() && it.any { c -> c in 'A'..'Z' || c in 'a'..'z' } }) {
            val pattern = Regex("(?<![A-Za-z0-9_])${Regex.escape(word)}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
            result = pattern.replace(result) { word }
        }
        return result
    }

    fun combine(first: String, second: String): String {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        val bothChinese = Character.UnicodeScript.of(first.last().code) == Character.UnicodeScript.HAN &&
            Character.UnicodeScript.of(second.first().code) == Character.UnicodeScript.HAN
        return if (bothChinese) first + second else "$first $second"
    }
}
