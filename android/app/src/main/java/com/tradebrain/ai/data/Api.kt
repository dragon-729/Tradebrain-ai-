package com.tradebrain.ai.data

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface SupabaseAuthApi {
    @POST("auth/v1/token?grant_type=password") suspend fun login(@Body b: Credentials): AuthSession
    @POST("auth/v1/signup") suspend fun signup(@Body b: Credentials): SignupResp
    @POST("auth/v1/token?grant_type=refresh_token") suspend fun refresh(@Body b: RefreshBody): AuthSession
    @POST("auth/v1/recover") suspend fun recover(@Body b: RecoverBody): Response<Unit>
}

interface TradeBrainApi {
    @GET("v1/stats") suspend fun stats(): Stats

    @Multipart @POST("v1/documents")
    suspend fun uploadDocument(@Part file: MultipartBody.Part, @Part("visibility") visibility: RequestBody): DocumentDto
    @GET("v1/documents") suspend fun documents(): List<DocumentDto>
    @PATCH("v1/documents/{id}") suspend fun setVisibility(@Path("id") id: String, @Body b: VisibilityBody): DocumentDto
    @POST("v1/documents/{id}/reprocess") suspend fun reprocess(@Path("id") id: String): Response<Unit>
    @DELETE("v1/documents/{id}") suspend fun deleteDocument(@Path("id") id: String): Response<Unit>

    @Multipart @POST("v1/analyses") suspend fun analyze(@Part image: MultipartBody.Part): AnalysisDto
    @GET("v1/analyses") suspend fun analyses(): List<AnalysisSummary>
    @GET("v1/analyses/{id}") suspend fun analysis(@Path("id") id: String): AnalysisDto
    @POST("v1/analyses/{id}/messages") suspend fun askAbout(@Path("id") id: String, @Body b: AskBody): AnswerDto
    @DELETE("v1/analyses/{id}") suspend fun deleteAnalysis(@Path("id") id: String): Response<Unit>

    @POST("v1/chat") suspend fun chat(@Body b: ChatBody): ChatReply
}
