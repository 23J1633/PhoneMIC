package com.phonemic

import org.junit.Assert.*
import org.junit.Test

class SpeechRecordingStateTest {
    @Test fun oldSessionTerminationCannotStopTheNewRecording() {
        for (terminal in listOf("processing", "stopped", "error")) {
            val state = SpeechRecordingState()
            assertEquals(true, state.update("recording", "old"))
            assertEquals(true, state.update("recording", "new"))
            assertNull(state.update(terminal, "old"))
            assertEquals(false, state.update("stopped", "new"))
        }
    }

    @Test fun modelLifecycleEventsCannotStopAnActiveMicrophone() {
        val state = SpeechRecordingState()
        assertEquals(true, state.update("recording", "current"))
        assertNull(state.update("error", ""))
        assertNull(state.update("loading", ""))
        assertNull(state.update("ready", ""))
        assertEquals(false, state.update("processing", "current"))
    }

    @Test fun eachCurrentSessionTerminalStateStopsRecordingOnce() {
        for (terminal in listOf("processing", "stopped", "error")) {
            val state = SpeechRecordingState()
            assertEquals(true, state.update("recording", "current"))
            assertEquals(false, state.update(terminal, "current"))
            assertNull(state.update("stopped", "current"))
        }
    }

    @Test fun repeatedRecordingEventsDoNotRepeatRecordingSideEffects() {
        val state = SpeechRecordingState()
        assertEquals(true, state.update("recording", "current"))
        assertNull(state.update("recording", "current"))
        assertEquals(false, state.update("stopped", "current"))
    }

    @Test fun failedOpeningCannotChangeAnUnrelatedRecording() {
        val state = SpeechRecordingState()
        assertNull(state.update("error", "failed-open"))
        assertNull(state.update("recording", ""))
        assertEquals(true, state.update("recording", "current"))
        assertNull(state.update("error", "failed-open"))
        assertEquals(false, state.update("stopped", "current"))
    }
}
