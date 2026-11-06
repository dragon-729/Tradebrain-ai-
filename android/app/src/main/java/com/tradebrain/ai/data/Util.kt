package com.tradebrain.ai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.max

fun extractErrorMessage(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return try {
        val o = JsonParser.parseString(body).asJsonObject
        listOf("detail", "error_description", "msg", "message")
            .firstNotNullOfOrNull { k -> o.get(k)?.takeIf { it.isJsonPrimitive }?.asString }
    } catch (e: Exception) {
        null
    }
}

fun rateLimitMessage(retryAfter: String?): String {
    val s = retryAfter?.trim()?.toDoubleOrNull()?.toInt()
    return if (s != null && s > 0) "The AI service is busy. Please try again in about $s seconds."
    else "The AI service is busy. Please try again shortly."
}

fun Throwable.friendly(): String = when (this) {
    is HttpException -> {
        val detail = extractErrorMessage(response()?.errorBody()?.string())
        when (code()) {
            429 -> rateLimitMessage(response()?.headers()?.get("Retry-After"))
            401 -> "Your session expired. Please sign in again."
            413 -> detail ?: "That file is too large."
            else -> detail ?: "Server error (${code()}). Please retry."
        }
    }
    is UnknownHostException, is ConnectException, is SocketTimeoutException ->
        "Can't reach the server. Check your connection and retry."
    is IOException -> "Network problem. Please retry."
    else -> message ?: "Something went wrong."
}

/** Runs [block], keeps coroutine cancellation intact, returns failures as Result. */
suspend fun <T> call(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

class Picked(val name: String, val mime: String, val bytes: ByteArray)

suspend fun readPicked(ctx: Context, uri: Uri, maxBytes: Int = 25 * 1024 * 1024): Picked =
    withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        var name = "file"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Cannot open the file")
        require(bytes.size <= maxBytes) { "File is larger than ${maxBytes / (1024 * 1024)} MB" }
        Picked(name, mime, bytes)
    }

/** Downscales (max side ~2400px) and re-encodes as JPEG to cut upload size. */
suspend fun prepareImage(ctx: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: throw IOException("Cannot read this image")
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
    bmp.recycle()
    out.toByteArray()
}
