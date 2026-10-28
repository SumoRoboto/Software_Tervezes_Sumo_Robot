package com.example.sumoroboto.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton Retrofit & OkHttp client manager for communicating with the FastAPI backend.
 *
 * Supported Base URLs:
 * - Physical Android phone on local Wi-Fi: "http://192.168.1.150:8000/"
 * - Android Emulator host machine loopback: "http://10.0.2.2:8000/"
 */
object NetworkClient {

    const val DEFAULT_BASE_URL = "http://192.168.1.150:8000/"
    const val EMULATOR_BASE_URL = "http://10.0.2.2:8000/"

    private val _baseUrlFlow = MutableStateFlow(DEFAULT_BASE_URL)
    val baseUrlFlow: StateFlow<String> = _baseUrlFlow.asStateFlow()

    @Volatile
    var currentBaseUrl: String = DEFAULT_BASE_URL
        private set

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    @Volatile
    private var _api: SumoApiService = buildService(currentBaseUrl)

    val api: SumoApiService
        get() = _api

    /**
     * Dynamically updates the backend server URL at runtime (e.g. from app settings or user input).
     */
    @Synchronized
    fun updateBaseUrl(newUrl: String) {
        val trimmed = newUrl.trim()
        val formatted = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        if (formatted == currentBaseUrl) return
        currentBaseUrl = formatted
        _baseUrlFlow.value = formatted
        _api = buildService(formatted)
    }

    private fun buildService(url: String): SumoApiService {
        return Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SumoApiService::class.java)
    }
}
