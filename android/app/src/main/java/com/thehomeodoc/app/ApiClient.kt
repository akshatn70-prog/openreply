package com.thehomeodoc.app

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiClient(private val store: SessionStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val base = BuildConfig.API_BASE_URL.trimEnd('/')

    suspend fun get(path: String): JsonObject = request("GET", path, null)
    suspend fun post(path: String, body: JsonObject = JsonObject()): JsonObject =
        request("POST", path, gson.toJson(body))
    suspend fun patch(path: String, body: JsonObject = JsonObject()): JsonObject =
        request("PATCH", path, gson.toJson(body))
    suspend fun delete(path: String, body: JsonObject = JsonObject()): JsonObject =
        request("DELETE", path, gson.toJson(body))

    private suspend fun request(method: String, path: String, body: String?): JsonObject =
        suspendCancellableCoroutine { continuation ->
            val builder = Request.Builder()
                .url(base + path)
                .header("Accept", "application/json")
            store.token?.let { builder.header("Authorization", "Bearer $it") }

            when (method) {
                "POST" -> builder.post((body ?: "{}").toRequestBody(json))
                "PATCH" -> builder.patch((body ?: "{}").toRequestBody(json))
                "DELETE" -> builder.delete((body ?: "{}").toRequestBody(json))
                else -> Unit
            }

            val call = http.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val text = it.body?.string().orEmpty()
                            if (!it.isSuccessful) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        ApiException(it.code, parseError(text))
                                    )
                                }
                                return
                            }
                            val parsed = gson.fromJson(text, JsonObject::class.java) ?: JsonObject()
                            if (continuation.isActive) continuation.resume(parsed)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }

    private fun parseError(text: String): String = runCatching {
        gson.fromJson(text, JsonObject::class.java).get("error")?.asString
    }.getOrNull() ?: "Request failed"
}

class ApiException(val code: Int, message: String) : Exception(message)
