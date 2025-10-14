package youtrack

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import model.Issue
import model.Notification
import model.NotificationMetadata
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

class YouTrackClient(
    private val baseUrl: String,
    private val token: String
) {
    private val client = OkHttpClient()
    private val gson = Gson()
    
    fun getNotifications(): List<Notification> {
        val url = "${baseUrl.trimEnd('/')}/api/users/me/notifications?fields=id,content,metadata"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .build()

        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IllegalStateException("YouTrack returned ${response.code}")
        }

        val body = response.body?.string() ?: return emptyList()
        val root = gson.fromJson(body, JsonElement::class.java)
        
        if (!root.isJsonArray) return emptyList()
        
        val notifications = mutableListOf<Notification>()
        for (element in root.asJsonArray) {
            val notif = parseNotification(element)
            if (notif != null) {
                notifications.add(notif)
            }
        }
        
        return notifications
    }

    private fun parseNotification(element: JsonElement): Notification? {
        if (!element.isJsonObject) return null
        
        val obj = element.asJsonObject
        val id = obj.get("id")?.asString ?: return null
        val contentRaw = obj.get("content")
        
        // API sometimes returns content as string, sometimes as object
        val content = when {
            contentRaw == null || contentRaw.isJsonNull -> ""
            contentRaw.isJsonPrimitive -> contentRaw.asString
            else -> contentRaw.toString()
        }

        val metadataObj = obj.get("metadata")
        val metadata = if (metadataObj == null || metadataObj.isJsonNull) {
            NotificationMetadata(null, null, null, null)
        } else {
            parseMetadata(metadataObj.asJsonObject)
        }

        return Notification(id, content, metadata)
    }

    private fun parseMetadata(obj: JsonObject): NotificationMetadata {
        val issueId = obj.get("issueId")?.asString
        val issueTitle = obj.get("issueTitle")?.asString
        val issueStatus = obj.get("issueStatus")?.asString
        
        // Description comes base64+gzipped for some reason
        val descRaw = obj.get("description")?.asString
        val description = if (descRaw != null) {
            try {
                val decoded = Base64.getDecoder().decode(descRaw)
                GZIPInputStream(ByteArrayInputStream(decoded))
                    .bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                descRaw // fallback
            }
        } else {
            null
        }

        return NotificationMetadata(issueId, issueTitle, issueStatus, description)
    }

    fun getRecentIssues(sinceTimestamp: Long): List<Issue> {
        val url = "${baseUrl.trimEnd('/')}/api/issues?fields=id,summary,description,updated,customFields(name,value(name))&\$top=50"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .build()

        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IllegalStateException("YouTrack returned ${response.code}")
        }

        val body = response.body?.string() ?: return emptyList()
        val root = gson.fromJson(body, JsonElement::class.java)

        if (!root.isJsonArray) return emptyList()

        val issues = mutableListOf<Issue>()
        
        for (element in root.asJsonArray) {
            if (!element.isJsonObject) continue

            val obj = element.asJsonObject
            val id = obj.get("id")?.asString ?: continue
            val summary = obj.get("summary")?.asString ?: ""
            val description = obj.get("description")?.asString
            val updated = obj.get("updated")?.asLong ?: continue

            // Skip old issues
            if (updated <= sinceTimestamp) continue

            // Extract State custom field
            var state: String? = null
            val customFields = obj.getAsJsonArray("customFields")
            if (customFields != null) {
                for (field in customFields) {
                    val fieldObj = field.asJsonObject
                    if (fieldObj.get("name")?.asString == "State") {
                        state = fieldObj.getAsJsonObject("value")?.get("name")?.asString
                        break
                    }
                }
            }

            issues.add(Issue(id, summary, description, state))
        }
        
        return issues
    }

    fun createIssue(projectId: String, summary: String, description: String? = null): String {
        val url = "${baseUrl.trimEnd('/')}/api/issues?fields=id,idReadable"

        // Build JSON manually
        var jsonBody = "{\"project\":{\"id\":\"$projectId\"},\"summary\":\"${escapeJson(summary)}\""
        if (!description.isNullOrBlank()) {
            jsonBody += ",\"description\":\"${escapeJson(description)}\""
        }
        jsonBody += "}"

        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to create issue: ${response.code}")
        }

        val body = response.body?.string() ?: throw IllegalStateException("Empty response")
        val root = gson.fromJson(body, JsonObject::class.java)
        
        return root.get("idReadable")?.asString 
            ?: root.get("id")?.asString 
            ?: "CREATED"
    }

    private fun escapeJson(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}