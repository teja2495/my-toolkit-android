package com.tk.myapp.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenAiApiService {
    @POST("v1/chat/completions")
    suspend fun getCompletion(
        @Header("Authorization") apiKey: String,
        @Body requestBody: OpenAiRequest
    ): Response<OpenAiResponse>
}

data class OpenAiRequest(
    val model: String = "gpt-5-mini",
    val messages: List<Message>,
    val max_completion_tokens: Int = 150
)

data class Message(
    val role: String = "user",
    val content: String
)

data class OpenAiResponse(
    val choices: List<Choice>?,
    val error: ApiError?
)

data class Choice(
    val message: ResponseMessage?
)

data class ResponseMessage(
    val role: String?,
    val content: String?
)

data class ApiError(
    val message: String?,
    val type: String?,
    val param: String?,
    val code: String?
)
