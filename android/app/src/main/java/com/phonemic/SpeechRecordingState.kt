package com.phonemic

/** Call under the same lock as the recording side effects to preserve event order. */
internal class SpeechRecordingState {
    private var recordingSession: String? = null

    fun update(state: String, session: String): Boolean? {
        if (session.isEmpty()) return null
        return when (state) {
            "recording" -> {
                if (recordingSession == session) null
                else {
                    recordingSession = session
                    true
                }
            }
            "processing", "stopped", "error" -> {
                if (recordingSession != session) null
                else {
                    recordingSession = null
                    false
                }
            }
            else -> null
        }
    }
}
