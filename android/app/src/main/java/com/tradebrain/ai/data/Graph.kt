package com.tradebrain.ai.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.tradebrain.ai.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Tokens are kept in EncryptedSharedPreferences. No private API keys exist in this app. */
class SessionStore(ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "tb_session",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    val loggedIn = MutableStateFlow(prefs.getString("at", null) != null)
    val accessToken: String? get() = prefs.getString("at", null)
    val refreshToken: String? get() = prefs.getString("rt", null)
    val email: String? get() = prefs.getString("email", null)

    fun save(s: AuthSession) {
        prefs.edit().putString("at", s.access_token).putString("rt", s.refresh_token)
            .putString("email", s.user?.email ?: email).apply()
        loggedIn.value = true
    }

    fun clear() {
        prefs.edit().clear().apply()
        loggedIn.value = false
    }
}

object Graph {
    lateinit var session: SessionStore
    lateinit var api: TradeBrainApi
    lateinit var auth: SupabaseAuthApi

    private fun slash(u: String) = if (u.endsWith("/")) u else "$u/"

    fun init(ctx: Context) {
        session = SessionStore(ctx.applicationContext)
        val gson = Gson()

        val authClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { c ->
                c.proceed(c.request().newBuilder().header("apikey", BuildConfig.SUPABASE_ANON_KEY).build())
            }).build()
        auth = Retrofit.Builder().baseUrl(slash(BuildConfig.SUPABASE_URL)).client(authClient)
            .addConverterFactory(GsonConverterFactory.create(gson)).build()
            .create(SupabaseAuthApi::class.java)

        val refresher = Authenticator { _, response ->
            if (response.request.header("X-Retried") != null) return@Authenticator null
            val rt = session.refreshToken ?: return@Authenticator null
            val fresh = try {
                runBlocking { auth.refresh(RefreshBody(rt)) }
            } catch (e: Exception) {
                session.clear(); return@Authenticator null
            }
            session.save(fresh)
            response.request.newBuilder()
                .header("Authorization", "Bearer ${fresh.access_token}").header("X-Retried", "1").build()
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { c ->
                val t = session.accessToken
                val b = c.request().newBuilder()
                if (t != null) b.header("Authorization", "Bearer $t")
                c.proceed(b.build())
            })
            .authenticator(refresher).build()
        api = Retrofit.Builder().baseUrl(slash(BuildConfig.API_BASE_URL)).client(client)
            .addConverterFactory(GsonConverterFactory.create(gson)).build()
            .create(TradeBrainApi::class.java)
    }
}
