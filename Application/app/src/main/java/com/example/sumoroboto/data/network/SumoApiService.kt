package com.example.sumoroboto.data.network

import com.example.sumoroboto.data.model.HistoryResponse
import com.example.sumoroboto.data.model.SessionPointsResponse
import com.example.sumoroboto.data.model.SessionSummaryDto
import com.example.sumoroboto.data.model.SessionUploadDto
import com.example.sumoroboto.data.model.UploadResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit REST interface for the FastAPI / Flask Mini Sumo Telemetry backend.
 */
interface SumoApiService {

    /**
     * Uploads an entire match or free-roam session with all recorded telemetry points.
     * Maps to backend endpoint POST /api/sessions.
     */
    @POST("api/sessions")
    suspend fun uploadSession(@Body sessionData: SessionUploadDto): Response<UploadResponse>

    /**
     * Queries database history records (hot path alerts or cold path normal logs).
     * Maps to GET /api/history?type={all|cold|hot}&q={search}.
     */
    @GET("api/history")
    suspend fun getHistory(
        @Query("type") type: String = "all",
        @Query("q") query: String? = null
    ): Response<HistoryResponse>

    /**
     * Retrieves summary of all recorded sessions.
     * Maps to GET /api/sessions.
     */
    @GET("api/sessions")
    suspend fun getSessions(): Response<List<SessionSummaryDto>>

    /**
     * Retrieves all telemetry points captured in a specific session for graphing.
     * Maps to GET /api/sessions/{session_id}/points.
     */
    @GET("api/sessions/{session_id}/points")
    suspend fun getSessionPoints(
        @Path("session_id") sessionId: Int
    ): Response<SessionPointsResponse>

    /**
     * Starts backend simulated telemetry generation.
     * Maps to POST /api/simulator/start.
     */
    @POST("api/simulator/start")
    suspend fun startSimulator(): Response<Map<String, Any>>

    /**
     * Stops backend simulated telemetry generation.
     * Maps to POST /api/simulator/stop.
     */
    @POST("api/simulator/stop")
    suspend fun stopSimulator(): Response<Map<String, Any>>

    /**
     * Clears all generated logs, alerts, sessions, and points in the database.
     * Maps to DELETE /api/database/clear.
     */
    @DELETE("api/database/clear")
    suspend fun clearDatabase(): Response<Map<String, Any>>
}
