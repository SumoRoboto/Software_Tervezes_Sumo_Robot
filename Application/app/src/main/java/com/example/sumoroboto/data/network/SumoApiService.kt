package com.example.sumoroboto.data.network

import com.example.sumoroboto.data.model.SessionUploadDto
import com.example.sumoroboto.data.model.UploadResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit REST interface for the FastAPI Mini Sumo Telemetry backend.
 */
interface SumoApiService {

    /**
     * Uploads an entire match or free-roam session with all recorded telemetry points.
     * Maps to backend endpoint POST /api/sessions.
     */
    @POST("api/sessions")
    suspend fun uploadSession(@Body sessionData: SessionUploadDto): Response<UploadResponse>
}
