package com.example.sumoroboto.data.logging

import com.example.sumoroboto.data.model.SessionUploadDto
import com.example.sumoroboto.data.model.TelemetryPointDto
import com.example.sumoroboto.data.network.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * State representing active session recording and cloud upload progress.
 */
data class SessionRecorderState(
    val isRecording: Boolean = false,
    val sessionType: String = "MATCH", // "MATCH" or "FREE_ROAM"
    val startVbat: Float = 0f,
    val pointCount: Int = 0,
    val elapsedSeconds: Long = 0L,
    val isUploading: Boolean = false,
    val lastUploadSuccess: Boolean? = null,
    val lastUploadMessage: String? = null
)

/**
 * Manages live match / free-roam session telemetry buffers and uploads completed sessions
 * to the FastAPI backend service via Retrofit.
 */
class SessionRecorder(private val coroutineScope: CoroutineScope) {

    private val _state = MutableStateFlow(SessionRecorderState())
    val state: StateFlow<SessionRecorderState> = _state.asStateFlow()

    private val pointsBuffer = Collections.synchronizedList(mutableListOf<TelemetryPointDto>())
    private var sessionStartTimeMs: Long = 0L
    private var startVbat: Float = 0f
    private var currentSessionType: String = "MATCH"
    private var timerJob: Job? = null

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Starts a new telemetry recording session.
     * Clears previous points buffer and records initial timestamp and voltage.
     */
    fun startSession(sessionType: String = "MATCH", startVbat: Float) {
        synchronized(pointsBuffer) {
            pointsBuffer.clear()
        }
        this.currentSessionType = if (sessionType.equals("FREE_ROAM", ignoreCase = true)) "FREE_ROAM" else "MATCH"
        this.startVbat = startVbat
        this.sessionStartTimeMs = System.currentTimeMillis()

        _state.value = SessionRecorderState(
            isRecording = true,
            sessionType = this.currentSessionType,
            startVbat = startVbat,
            pointCount = 0,
            elapsedSeconds = 0L,
            isUploading = false,
            lastUploadSuccess = null,
            lastUploadMessage = null
        )

        timerJob?.cancel()
        timerJob = coroutineScope.launch {
            while (isActive && _state.value.isRecording) {
                delay(1000L)
                val elapsed = (System.currentTimeMillis() - sessionStartTimeMs) / 1000L
                _state.value = _state.value.copy(elapsedSeconds = elapsed)
            }
        }
    }

    /**
     * Appends a real-time 40-50 Hz telemetry sample to the in-memory buffer.
     * Calculates the relative millisecond offset from the session start time.
     */
    fun recordPoint(
        vbat: Float,
        distL: Int,
        distR: Int,
        lineL: Int,
        lineR: Int,
        pwmL: Int,
        pwmR: Int
    ) {
        if (!_state.value.isRecording) return

        val nowMs = System.currentTimeMillis()
        val relTimeMs = (nowMs - sessionStartTimeMs).coerceAtLeast(0L)

        val point = TelemetryPointDto(
            relTimeMs = relTimeMs,
            vbat = vbat,
            distL = distL,
            distR = distR,
            lineL = lineL,
            lineR = lineR,
            pwmL = pwmL,
            pwmR = pwmR
        )
        pointsBuffer.add(point)

        _state.value = _state.value.copy(pointCount = pointsBuffer.size)
    }

    /**
     * Concludes the active run, compiles the SessionUploadDto payload,
     * and executes the HTTP POST /api/sessions upload on Dispatchers.IO.
     */
    fun stopAndUploadSession(
        endVbat: Float,
        notes: String = "",
        onResult: (Boolean, String) -> Unit
    ) {
        if (!_state.value.isRecording) {
            onResult(false, "Nincs aktív munkamenet a feltöltéshez.")
            return
        }

        timerJob?.cancel()
        timerJob = null

        val stopTimeMs = System.currentTimeMillis()
        val durationMs = (stopTimeMs - sessionStartTimeMs).coerceAtLeast(0L)
        val createdAtIso = isoDateFormat.format(Date(sessionStartTimeMs))

        val pointsCopy: List<TelemetryPointDto>
        synchronized(pointsBuffer) {
            pointsCopy = ArrayList(pointsBuffer)
        }

        val payload = SessionUploadDto(
            sessionType = currentSessionType,
            createdAt = createdAtIso,
            durationMs = durationMs,
            startVbat = startVbat,
            endVbat = endVbat,
            notes = notes,
            points = pointsCopy
        )

        _state.value = _state.value.copy(
            isRecording = false,
            isUploading = true,
            lastUploadMessage = "Feltöltés folyamatban a szerverre..."
        )

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val response = NetworkClient.api.uploadSession(payload)
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val successMsg = "Sikeres mentés! Session ID: ${body.sessionId}, Mentett pontok: ${body.savedPoints}"
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(
                            isUploading = false,
                            lastUploadSuccess = true,
                            lastUploadMessage = successMsg
                        )
                        onResult(true, successMsg)
                    }
                } else {
                    val errorDetail = response.errorBody()?.string() ?: "Ismeretlen hiba"
                    val errorMsg = "Szerver hiba (HTTP ${response.code()}): $errorDetail"
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(
                            isUploading = false,
                            lastUploadSuccess = false,
                            lastUploadMessage = errorMsg
                        )
                        onResult(false, errorMsg)
                    }
                }
            } catch (e: IOException) {
                val errorMsg = "Hálózati hiba: ${e.localizedMessage ?: "Nem érhető el a FastAPI szerver (${NetworkClient.currentBaseUrl})"}"
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        isUploading = false,
                        lastUploadSuccess = false,
                        lastUploadMessage = errorMsg
                    )
                    onResult(false, errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = "Kivétel a feltöltés során: ${e.localizedMessage ?: e.javaClass.simpleName}"
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        isUploading = false,
                        lastUploadSuccess = false,
                        lastUploadMessage = errorMsg
                    )
                    onResult(false, errorMsg)
                }
            }
        }
    }

    /**
     * Cancels an active session without uploading to backend.
     */
    fun cancelSession() {
        timerJob?.cancel()
        timerJob = null
        synchronized(pointsBuffer) {
            pointsBuffer.clear()
        }
        _state.value = SessionRecorderState(
            isRecording = false,
            pointCount = 0,
            elapsedSeconds = 0L,
            isUploading = false,
            lastUploadSuccess = null,
            lastUploadMessage = "Munkamenet elvetve."
        )
    }
}
