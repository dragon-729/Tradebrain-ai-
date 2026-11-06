package com.tradebrain.ai.data

data class AuthUser(val id: String?, val email: String?)
data class AuthSession(val access_token: String, val refresh_token: String, val user: AuthUser?)
data class SignupResp(val access_token: String?, val refresh_token: String?, val user: AuthUser?)
data class Credentials(val email: String, val password: String)
data class RefreshBody(val refresh_token: String)
data class RecoverBody(val email: String)

data class Stats(val documents: Int, val ready_documents: Int, val analyses: Int, val concepts: Int)

data class DocumentDto(
    val id: String, val title: String?, val filename: String?, val category: String?, val summary: String?,
    val concepts: List<String>?, val visibility: String?, val status: String?, val error: String?,
    val page_count: Int?, val created_at: String?
)

data class AnalysisSummary(val id: String, val title: String?, val created_at: String?, val pattern: String?)
data class MsgDto(val role: String, val content: String)
data class AnalysisDto(
    val id: String, val title: String?, val created_at: String?,
    val result: AnalysisResult?, val messages: List<MsgDto>?
)

data class AnalysisResult(
    val chart_overview: Overview?, val image_quality: Quality?, val patterns: List<Pattern>?, val trend: Trend?,
    val levels: Levels?, val indicators: List<Indicator>?, val scenarios: Scenarios?, val risks: List<String>?,
    val library_matches: List<LibraryMatch>?
)
data class Overview(val chart_type: String?, val timeframe: String?, val structure: String?)
data class Quality(val sufficient: Boolean?, val issues: List<String>?)
data class Pattern(
    val name: String?, val category: String?, val explanation: String?, val visual_evidence: String?,
    val confidence: String?
)
data class Trend(val classification: String?, val reasoning: String?)
data class Levels(
    val support: List<String>?, val resistance: List<String>?, val breakout_area: String?,
    val breakdown_area: String?, val note: String?
)
data class Indicator(
    val name: String?, val purpose: String?, val what_to_check: String?, val limitations: String?,
    val visible_on_chart: Boolean?
)
data class Scenario(val description: String?, val invalidation: String?)
data class Scenarios(val bullish: Scenario?, val bearish: Scenario?, val sideways: Scenario?)
data class LibraryMatch(val document_id: String?, val title: String?, val page: Int?, val snippet: String?)

data class AskBody(val question: String)
data class AnswerDto(val answer: String)
data class SourceDto(val n: Int, val document_id: String?, val title: String?, val page: Int?, val snippet: String?)
data class ChatBody(val message: String, val conversation_id: String?, val scope: String)
data class ChatReply(val conversation_id: String, val answer: String, val sources: List<SourceDto>?)
data class VisibilityBody(val visibility: String)
