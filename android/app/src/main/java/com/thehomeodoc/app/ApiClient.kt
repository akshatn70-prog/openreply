package com.thehomeodoc.app

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class ApiClient(private val store: SessionStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val base = BuildConfig.API_BASE_URL.trimEnd('/')

    fun get(path: String): JsonObject = request("GET", path, null)
    fun post(path: String, body: JsonObject = JsonObject()): JsonObject = request("POST", path, gson.toJson(body))
    fun patch(path: String, body: JsonObject = JsonObject()): JsonObject = request("PATCH", path, gson.toJson(body))
    fun delete(path: String, body: JsonObject = JsonObject()): JsonObject = request("DELETE", path, gson.toJson(body))

    private fun request(method: String, path: String, body: String?): JsonObject {
        val builder = Request.Builder().url(base + path).header("Accept", "application/json")
        store.token?.let { builder.header("Authorization", "Bearer $it") }
        if (method == "POST") builder.post((body ?: "{}").toRequestBody(json))
        if (method == "PATCH") builder.patch((body ?: "{}").toRequestBody(json))
        if (method == "DELETE") builder.delete((body ?: "{}").toRequestBody(json))
        val response = http.newCall(builder.build()).execute()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw ApiException(response.code, parseError(text))
        return gson.fromJson(text, JsonObject::class.java) ?: JsonObject()
    }
    private fun parseError(text: String): String = runCatching {
        gson.fromJson(text, JsonObject::class.java).get("error")?.asString
    }.getOrNull() ?: "Request failed"
}
class ApiException(val code: Int, message: String): Exception(message)
