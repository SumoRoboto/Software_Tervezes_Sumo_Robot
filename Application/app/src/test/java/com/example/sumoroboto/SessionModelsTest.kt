package com.example.sumoroboto

import com.example.sumoroboto.data.model.SessionUploadDto
import com.example.sumoroboto.data.model.TelemetryPointDto
import com.example.sumoroboto.data.model.UploadResponse
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModelsTest {

    private val gson = Gson()

    @Test
    fun testSessionUploadSerializationMatchesFastApiSchema() {
        val point = TelemetryPointDto(
            relTimeMs = 120L,
            vbat = 7.92f,
            distL = 15,
            distR = 40,
            lineL = 0,
            lineR = 1,
            pwmL = 200,
            pwmR = -150
        )

        val session = SessionUploadDto(
            sessionType = "MATCH",
            createdAt = "2026-10-28T16:00:00Z",
            durationMs = 4500L,
            startVbat = 8.1f,
            endVbat = 7.8f,
            notes = "Test Match 1",
            points = listOf(point)
        )

        val json = gson.toJson(session)

        // Verify JSON keys match FastAPI expected snake_case field names
        assertTrue(json.contains("\"session_type\":\"MATCH\""))
        assertTrue(json.contains("\"created_at\":\"2026-10-28T16:00:00Z\""))
        assertTrue(json.contains("\"duration_ms\":4500"))
        assertTrue(json.contains("\"start_vbat\":8.1"))
        assertTrue(json.contains("\"end_vbat\":7.8"))
        assertTrue(json.contains("\"notes\":\"Test Match 1\""))
        assertTrue(json.contains("\"points\":["))

        // Check telemetry point keys
        assertTrue(json.contains("\"rel_time_ms\":120"))
        assertTrue(json.contains("\"vbat\":7.92"))
        assertTrue(json.contains("\"dist_l\":15"))
        assertTrue(json.contains("\"dist_r\":40"))
        assertTrue(json.contains("\"line_l\":0"))
        assertTrue(json.contains("\"line_r\":1"))
        assertTrue(json.contains("\"pwm_l\":200"))
        assertTrue(json.contains("\"pwm_r\":-150"))
    }

    @Test
    fun testUploadResponseDeserialization() {
        val responseJson = """
            {
                "status": "success",
                "session_id": 42,
                "saved_points": 350
            }
        """.trimIndent()

        val response = gson.fromJson(responseJson, UploadResponse::class.java)

        assertEquals("success", response.status)
        assertEquals(42, response.sessionId)
        assertEquals(350, response.savedPoints)
    }
}
