package com.example.sumoroboto.data.model

import com.google.gson.annotations.SerializedName

/**
 * Single telemetry sample point captured during a robot run session.
 * Matches backend TelemetryPointIn schema.
 */
data class TelemetryPointDto(
    @SerializedName("rel_time_ms")
    val relTimeMs: Long,

    @SerializedName("vbat")
    val vbat: Float,

    @SerializedName("dist_l")
    val distL: Int,

    @SerializedName("dist_r")
    val distR: Int,

    @SerializedName("line_l")
    val lineL: Int,

    @SerializedName("line_r")
    val lineR: Int,

    @SerializedName("pwm_l")
    val pwmL: Int,

    @SerializedName("pwm_r")
    val pwmR: Int
)

/**
 * Complete session payload uploaded to FastAPI POST /api/sessions.
 * Matches backend SessionUpload schema.
 */
data class SessionUploadDto(
    @SerializedName("session_type")
    val sessionType: String, // "MATCH" or "FREE_ROAM"

    @SerializedName("created_at")
    val createdAt: String, // ISO 8601 formatted UTC timestamp

    @SerializedName("duration_ms")
    val durationMs: Long,

    @SerializedName("start_vbat")
    val startVbat: Float,

    @SerializedName("end_vbat")
    val endVbat: Float,

    @SerializedName("notes")
    val notes: String = "",

    @SerializedName("points")
    val points: List<TelemetryPointDto>
)

/**
 * Server response from FastAPI POST /api/sessions.
 */
data class UploadResponse(
    @SerializedName("status")
    val status: String,

    @SerializedName("session_id")
    val sessionId: Int,

    @SerializedName("saved_points")
    val savedPoints: Int
)

/**
 * Summary DTO of a recorded session retrieved from GET /api/sessions.
 */
data class SessionSummaryDto(
    @SerializedName("id")
    val id: Int,

    @SerializedName("session_type")
    val sessionType: String,

    @SerializedName("created_at")
    val createdAt: String,

    @SerializedName("duration_ms")
    val durationMs: Long,

    @SerializedName("start_vbat")
    val startVbat: Float,

    @SerializedName("end_vbat")
    val endVbat: Float,

    @SerializedName("notes")
    val notes: String = "",

    @SerializedName("point_count")
    val pointCount: Int = 0
)

/**
 * Response containing full points array for a specific session from GET /api/sessions/{id}/points.
 */
data class SessionPointsResponse(
    @SerializedName("session_id")
    val sessionId: Int,

    @SerializedName("points")
    val points: List<TelemetryPointDto>
)

/**
 * Single item in history query (Hot Path safety alert or Cold Path telemetry log).
 */
data class HistoryItemDto(
    @SerializedName("type")
    val type: String = "COLD", // "HOT" or "COLD"

    @SerializedName("timestamp")
    val timestamp: String,

    @SerializedName("rule_broken")
    val ruleBroken: String? = null,

    @SerializedName("message")
    val message: String? = null,

    @SerializedName("data")
    val data: Map<String, Any?>? = null
)

/**
 * Response from GET /api/history.
 */
data class HistoryResponse(
    @SerializedName("items")
    val items: List<HistoryItemDto> = emptyList()
)
