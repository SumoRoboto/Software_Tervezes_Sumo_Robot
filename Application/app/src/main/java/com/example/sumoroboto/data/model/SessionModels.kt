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
