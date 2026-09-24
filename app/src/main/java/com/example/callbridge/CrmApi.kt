package com.example.callbridge

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Lead(
    val id: String,
    val name: String,
    val phone: String,
    val company: String,
    val notes: String,
    val status: String,
    val nextFollowupAt: String?,
    val expectedJoiningAt: String?,
    val lastCallOutcome: String?,
    val callCount: Int,
)

data class DispositionOption(
    val value: String,
    val label: String,
    val targetStatus: String,
    val targetStatusLabel: String,
    val connected: Boolean,
    val requiresFollowup: Boolean,
    val requiresInterview: Boolean,
    val requiresClosureReason: Boolean,
    val requiresExpectedJoiningDate: Boolean,
)

data class DispositionGroup(
    val key: String,
    val label: String,
    val options: List<DispositionOption>,
)

data class InterviewType(val value: String, val label: String)

data class DispositionMetadata(
    val groups: List<DispositionGroup>,
    val interviewTypes: List<InterviewType>,
) {
    val allOptions: List<DispositionOption> get() = groups.flatMap { it.options }
}

data class CallHistoryItem(
    val id: String,
    val outcome: String,
    val outcomeLabel: String,
    val notes: String,
    val durationSeconds: Int,
    val connected: Boolean,
    val createdAt: String,
)

class CrmApiException(message: String, val statusCode: Int = 0) : Exception(message)

class CrmApi(context: Context) {
    private val prefs = context.getSharedPreferences("callbridge_crm", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("crm_base_url", "") ?: ""
        set(value) {
            val normalized = normalizeBaseUrl(value)
            prefs.edit().putString("crm_base_url", normalized).apply()
        }

    var isDemoMode: Boolean
        get() = prefs.getBoolean("crm_is_demo", false)
        set(value) = prefs.edit().putBoolean("crm_is_demo", value).apply()

    private var cookies: String
        get() = prefs.getString("crm_cookies", "") ?: ""
        set(value) = prefs.edit().putString("crm_cookies", value).apply()

    private var bearerToken: String
        get() = prefs.getString("crm_bearer", "") ?: ""
        set(value) = prefs.edit().putString("crm_bearer", value).apply()

    private var csrfToken: String
        get() = prefs.getString("crm_csrf", "") ?: ""
        set(value) = prefs.edit().putString("crm_csrf", value).apply()

    // In-memory demo data for offline testing
    private val demoLeads = mutableListOf(
        Lead("demo-1", "Rahul Sharma", "+919876543210", "Tech Corp", "Interested in Senior Kotlin Developer role", "contacted", null, null, "Interested - Callback Requested", 1),
        Lead("demo-2", "Priya Patel", "+919812345678", "Global Systems", "Scheduled technical round for Friday", "interview_scheduled", "2026-09-26T14:30:00Z", null, "Interview Scheduled", 2),
        Lead("demo-3", "Amit Verma", "+919988776655", "Apex Innovations", "Notice period 30 days, expecting 18 LPA", "in_process", null, null, "Connected - Positive", 1),
        Lead("demo-4", "Sneha Roy", "+919123456789", "CloudWave Ltd", "Did not answer 1st call", "new", null, null, "No Answer", 1)
    )

    private val demoCalls = mutableMapOf<String, MutableList<CallHistoryItem>>()

    init {
        demoCalls["demo-1"] = mutableListOf(
            CallHistoryItem("c1", "interested", "Interested - Callback", "Discussed project scope, asks for callback on Friday", 145, true, "2026-09-23 10:15 AM")
        )
        demoCalls["demo-2"] = mutableListOf(
            CallHistoryItem("c2", "interview_scheduled", "Interview Scheduled", "Cleared screening round", 320, true, "2026-09-22 04:00 PM")
        )
    }

    fun hasSession(): Boolean = isDemoMode || (baseUrl.isNotBlank() && (cookies.isNotBlank() || bearerToken.isNotBlank()))

    fun startDemoSession() {
        isDemoMode = true
        baseUrl = "https://demo.oaksphere.local"
    }

    fun clearSession(keepServer: Boolean = true) {
        val editor = prefs.edit().remove("crm_cookies").remove("crm_bearer").remove("crm_csrf").remove("crm_is_demo")
        if (!keepServer) editor.remove("crm_base_url")
        editor.apply()
    }

    fun login(server: String, email: String, password: String): String {
        isDemoMode = false
        baseUrl = server
        clearSession(keepServer = true)
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val response = request("POST", "/auth/login", body, includeSession = false)
        val token = response.optString("access_token", "")
        if (token.isNotBlank()) bearerToken = token
        return response.optJSONObject("user")?.optString("name") ?: email.trim()
    }

    fun logout() {
        try {
            if (!isDemoMode) {
                ensureCsrf()
                request("POST", "/auth/logout", JSONObject())
            }
        } catch (_: Exception) {
            // Local sign-out still succeeds
        } finally {
            clearSession(keepServer = true)
        }
    }

    fun me(): JSONObject {
        if (isDemoMode) return JSONObject().put("name", "Demo Recruiter").put("email", "demo@oaksphere.com")
        return request("GET", "/auth/me")
    }

    fun listLeads(search: String = ""): List<Lead> {
        if (isDemoMode) {
            val q = search.trim().lowercase()
            return if (q.isBlank()) demoLeads.toList()
            else demoLeads.filter { it.name.lowercase().contains(q) || it.phone.contains(q) || it.company.lowercase().contains(q) }
        }
        val encoded = Uri.encode(search.trim())
        val path = "/leads?page=1&page_size=200&sort_by=last_activity_at&sort_dir=desc" +
            if (encoded.isNotBlank()) "&search=$encoded" else ""
        val root = request("GET", path)
        return jsonArrayToLeads(root.optJSONArray("items") ?: JSONArray())
    }

    fun getLead(leadId: String): Lead {
        if (isDemoMode) {
            return demoLeads.firstOrNull { it.id == leadId } ?: demoLeads.first()
        }
        return parseLead(request("GET", "/leads/${Uri.encode(leadId)}"))
    }

    fun createLead(name: String, phone: String, company: String, notes: String, duplicateAck: Boolean): Lead {
        if (isDemoMode) {
            val newLead = Lead(
                id = "demo-${System.currentTimeMillis()}",
                name = name.trim(),
                phone = phone.trim(),
                company = company.trim(),
                notes = notes.trim(),
                status = "new",
                nextFollowupAt = null,
                expectedJoiningAt = null,
                lastCallOutcome = null,
                callCount = 0
            )
            demoLeads.add(0, newLead)
            return newLead
        }
        ensureCsrf()
        val body = JSONObject()
            .put("name", name.trim())
            .put("phone", phone.trim())
            .put("client", if (company.trim().isBlank()) JSONObject.NULL else company.trim())
            .put("notes", if (notes.trim().isBlank()) JSONObject.NULL else notes.trim())
            .put("source", "manual")
            .put("duplicate_ack", duplicateAck)
        return parseLead(request("POST", "/leads", body))
    }

    fun updateLead(leadId: String, name: String, phone: String, company: String, notes: String): Lead {
        if (isDemoMode) {
            val index = demoLeads.indexOfFirst { it.id == leadId }
            if (index >= 0) {
                val existing = demoLeads[index]
                val updated = existing.copy(name = name.trim(), phone = phone.trim(), company = company.trim(), notes = notes.trim())
                demoLeads[index] = updated
                return updated
            }
            return demoLeads.first()
        }
        ensureCsrf()
        val body = JSONObject()
            .put("name", name.trim())
            .put("phone", phone.trim())
            .put("client", if (company.trim().isBlank()) JSONObject.NULL else company.trim())
            .put("notes", if (notes.trim().isBlank()) JSONObject.NULL else notes.trim())
        return parseLead(request("POST", "/leads/${Uri.encode(leadId)}/mobile-update", body))
    }

    fun dispositionMetadata(leadId: String): DispositionMetadata {
        if (isDemoMode) {
            return DispositionMetadata(
                groups = listOf(
                    DispositionGroup(
                        key = "connected",
                        label = "Connected",
                        options = listOf(
                            DispositionOption("interested", "Interested - Callback", "callback_requested", "Callback Scheduled", true, true, false, false, false),
                            DispositionOption("interview_scheduled", "Interview Scheduled", "interview_scheduled", "Interview Scheduled", true, false, true, false, false),
                            DispositionOption("selected", "Selected / Offered", "offered", "Offer Released", true, false, false, false, true),
                            DispositionOption("not_interested", "Not Interested", "rejected", "Closed - Not Interested", true, false, false, true, false)
                        )
                    ),
                    DispositionGroup(
                        key = "not_connected",
                        label = "Not Connected",
                        options = listOf(
                            DispositionOption("no_answer", "No Answer / Ringing", "no_answer", "No Answer", false, true, false, false, false),
                            DispositionOption("busy", "Busy / Disconnected", "busy", "Busy / Dropped", false, true, false, false, false),
                            DispositionOption("switched_off", "Switched Off", "switched_off", "Switched Off", false, true, false, false, false),
                            DispositionOption("wrong_number", "Wrong Number", "invalid_number", "Invalid Number", false, false, false, false, false)
                        )
                    )
                ),
                interviewTypes = listOf(
                    InterviewType("telephonic", "Telephonic Screening"),
                    InterviewType("video", "Google Meet / Zoom Video"),
                    InterviewType("in_person", "Face to Face / In-Person")
                )
            )
        }
        val root = request("GET", "/leads/${Uri.encode(leadId)}/disposition-options")
        val groupsJson = root.optJSONArray("groups") ?: JSONArray()
        val groups = mutableListOf<DispositionGroup>()
        for (i in 0 until groupsJson.length()) {
            val groupJson = groupsJson.getJSONObject(i)
            val optionsJson = groupJson.optJSONArray("options") ?: JSONArray()
            val options = mutableListOf<DispositionOption>()
            for (j in 0 until optionsJson.length()) {
                val o = optionsJson.getJSONObject(j)
                options += DispositionOption(
                    value = o.getString("value"),
                    label = o.getString("label"),
                    targetStatus = o.optString("target_status", ""),
                    targetStatusLabel = o.optString("target_status_label", ""),
                    connected = o.optBoolean("connected", false),
                    requiresFollowup = o.optBoolean("requires_followup", false),
                    requiresInterview = o.optBoolean("requires_interview", false),
                    requiresClosureReason = o.optBoolean("requires_closure_reason", false),
                    requiresExpectedJoiningDate = o.optBoolean("requires_expected_joining_date", false),
                )
            }
            groups += DispositionGroup(
                key = groupJson.optString("key"),
                label = groupJson.optString("label"),
                options = options,
            )
        }
        val typesJson = root.optJSONArray("interview_types") ?: JSONArray()
        val types = mutableListOf<InterviewType>()
        for (i in 0 until typesJson.length()) {
            val t = typesJson.getJSONObject(i)
            types += InterviewType(t.getString("value"), t.getString("label"))
        }
        return DispositionMetadata(groups, types)
    }

    fun saveDisposition(leadId: String, payload: JSONObject): Lead {
        if (isDemoMode) {
            val outcome = payload.optString("outcome", "call_completed")
            val notes = payload.optString("notes", "")
            val duration = payload.optInt("duration_seconds", 0)
            val dateStr = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US).format(Date())
            val list = demoCalls.getOrPut(leadId) { mutableListOf() }
            list.add(0, CallHistoryItem("c-${System.currentTimeMillis()}", outcome, outcome.replace('_', ' ').capitalize(Locale.US), notes, duration, duration > 0, dateStr))

            val index = demoLeads.indexOfFirst { it.id == leadId }
            if (index >= 0) {
                val existing = demoLeads[index]
                val updated = existing.copy(
                    lastCallOutcome = outcome.replace('_', ' ').capitalize(Locale.US),
                    callCount = existing.callCount + 1,
                    status = if (payload.optBoolean("requires_interview", false)) "interview_scheduled" else existing.status
                )
                demoLeads[index] = updated
                return updated
            }
            return demoLeads.first()
        }
        ensureCsrf()
        return parseLead(request("POST", "/leads/${Uri.encode(leadId)}/disposition", payload))
    }

    fun callHistory(leadId: String): List<CallHistoryItem> {
        if (isDemoMode) {
            return demoCalls[leadId]?.toList() ?: emptyList()
        }
        val array = requestArray("GET", "/leads/${Uri.encode(leadId)}/calls")
        val result = mutableListOf<CallHistoryItem>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            result += CallHistoryItem(
                id = o.optString("id"),
                outcome = o.optString("outcome"),
                outcomeLabel = o.optString("outcome_label", o.optString("outcome")),
                notes = o.optString("notes", "").takeUnless { it == "null" } ?: "",
                durationSeconds = o.optInt("duration_seconds", 0),
                connected = o.optBoolean("connected", false),
                createdAt = o.optString("created_at", ""),
            )
        }
        return result
    }

    private fun parseLead(o: JSONObject): Lead = Lead(
        id = o.getString("id"),
        name = o.optString("name", ""),
        phone = o.optString("phone", ""),
        company = o.optString("client", "").takeUnless { it == "null" } ?: "",
        notes = o.optString("notes", "").takeUnless { it == "null" } ?: "",
        status = o.optString("status", "new"),
        nextFollowupAt = o.optString("next_followup_at", "").takeUnless { it.isBlank() || it == "null" },
        expectedJoiningAt = o.optString("expected_joining_at", "").takeUnless { it.isBlank() || it == "null" },
        lastCallOutcome = o.optString("last_call_outcome", "").takeUnless { it.isBlank() || it == "null" },
        callCount = o.optInt("call_count", 0),
    )

    private fun jsonArrayToLeads(array: JSONArray): List<Lead> {
        val result = mutableListOf<Lead>()
        for (i in 0 until array.length()) result += parseLead(array.getJSONObject(i))
        return result
    }

    private fun ensureCsrf() {
        if (bearerToken.isNotBlank() || isDemoMode) return
        val response = request("GET", "/auth/csrf")
        csrfToken = response.optString("csrf_token", "")
        if (csrfToken.isBlank()) throw CrmApiException("Unable to establish a secure CRM session")
    }

    private fun request(method: String, path: String, body: JSONObject? = null, includeSession: Boolean = true): JSONObject {
        val text = rawRequest(method, path, body?.toString(), includeSession)
        if (text.isBlank()) return JSONObject()
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            throw CrmApiException("CRM returned an unexpected response")
        }
    }

    private fun requestArray(method: String, path: String): JSONArray {
        val text = rawRequest(method, path, null, true)
        return try {
            JSONArray(text)
        } catch (_: Exception) {
            throw CrmApiException("CRM returned an unexpected response")
        }
    }

    private fun rawRequest(method: String, path: String, body: String?, includeSession: Boolean): String {
        if (baseUrl.isBlank()) throw CrmApiException("Enter your CRM server URL")
        val urlTarget = if (baseUrl.endsWith("/")) "${baseUrl}api$path" else "$baseUrl/api$path"
        val connection = (URL(urlTarget).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "OAKsphere-CallBridge-Android/1.1")
            if (includeSession) {
                if (cookies.isNotBlank()) setRequestProperty("Cookie", cookies)
                if (bearerToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $bearerToken")
                if (csrfToken.isNotBlank() && method !in setOf("GET", "HEAD", "OPTIONS")) {
                    setRequestProperty("X-CSRF-Token", csrfToken)
                }
            }
            if (body != null) {
                doOutput = true
                outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
        }
        try {
            val code = connection.responseCode
            captureCookies(connection)
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use(BufferedReader::readText) } ?: ""
            if (code !in 200..299) throw CrmApiException(extractMessage(text, code), code)
            return text
        } catch (e: CrmApiException) {
            throw e
        } catch (e: Exception) {
            throw CrmApiException("Could not reach CRM server. Check URL and network connection.", -1)
        } finally {
            connection.disconnect()
        }
    }

    private fun captureCookies(connection: HttpURLConnection) {
        val setCookies = connection.headerFields.entries
            .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
            .flatMap { it.value ?: emptyList() }
        if (setCookies.isEmpty()) return
        val map = linkedMapOf<String, String>()
        cookies.split(';').map { it.trim() }.filter { it.contains('=') }.forEach {
            map[it.substringBefore('=')] = it.substringAfter('=')
        }
        setCookies.forEach { header ->
            val pair = header.substringBefore(';').trim()
            if (pair.contains('=')) map[pair.substringBefore('=')] = pair.substringAfter('=')
        }
        cookies = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun extractMessage(text: String, code: Int): String {
        return try {
            val root = JSONObject(text)
            val error = root.optJSONObject("error")
            when {
                error != null -> error.optString("message", "CRM request failed ($code)")
                root.has("detail") -> root.opt("detail")?.toString() ?: "CRM request failed ($code)"
                else -> "CRM request failed ($code)"
            }
        } catch (_: Exception) {
            "CRM request failed ($code)"
        }
    }

    private fun normalizeBaseUrl(value: String): String {
        var v = value.trim().trimEnd('/')
        if (v.endsWith("/api", ignoreCase = true)) v = v.dropLast(4).trimEnd('/')
        return v
    }
}
