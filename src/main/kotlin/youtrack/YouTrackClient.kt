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
            throw IllegalStateException("YouTrack API error: ${response.code}")
        }

        val body = response.body?.string()
        if (body == null) return emptyList()

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
        val idElement = obj.get("id")
        val id = if (idElement != null && !idElement.isJsonNull) {
            idElement.asString
        } else return null

        // content might be base64+gzip encoded
        val contentRaw = obj.get("content")
        var content = ""
        if (contentRaw != null && !contentRaw.isJsonNull) {
            if (contentRaw.isJsonPrimitive) {
                val rawStr = contentRaw.asString
                val decoded = tryDecodeGzip(rawStr)
                content = decoded ?: rawStr
            } else {
                content = contentRaw.toString()
            }
        }

        val metaElement = obj.get("metadata")
        val metadata = when {
            metaElement == null || metaElement.isJsonNull -> {
                extractMetadata(content)
            }
            metaElement.isJsonObject -> {
                parseMetadata(metaElement.asJsonObject, content)
            }
            metaElement.isJsonPrimitive -> {
                val metaStr = metaElement.asString
                val decoded = tryDecodeGzip(metaStr)
                if (decoded != null) {
                    try {
                        val metaObj = gson.fromJson(decoded, JsonObject::class.java)
                        parseMetadata(metaObj, content)
                    } catch (e: Exception) {
                        extractMetadata(content)
                    }
                } else {
                    extractMetadata(content)
                }
            }
            else -> extractMetadata(content)
        }

        return Notification(id, content, metadata)
    }

    // Get metadata when API doesn't give it to us
    private fun extractMetadata(content: String): NotificationMetadata {
        val issueIdRegex = Regex("""([A-Z]+-\d+)""")
        val issueId = issueIdRegex.find(content)?.groupValues?.get(1)

        val titleRegex = Regex("""[A-Z]+-\d+\s+(.+?)(?:\n|<)""")
        val title = titleRegex.find(content)?.groupValues?.get(1)?.trim()

        // Try to find state/status
        val statusRegex = Regex("""State:\s*(\w+)""", RegexOption.IGNORE_CASE)
        val status = statusRegex.find(content)?.groupValues?.get(1)

        return NotificationMetadata(issueId, title, status, content)
    }

    private fun parseMetadata(obj: JsonObject, content: String = ""): NotificationMetadata {
        // Try to extract readable ID from content first (e.g., DEMO-35)
        val readableIdFromContent = if (content.isNotBlank()) {
            val issueIdRegex = Regex("""([A-Z]+-\d+)""")
            issueIdRegex.find(content)?.groupValues?.get(1)
        } else null
        
        // Use readable ID from content, fallback to metadata issueId
        val issueIdElement = obj.get("issueId")
        val metadataIssueId = if (issueIdElement != null && !issueIdElement.isJsonNull) {
            issueIdElement.asString
        } else null
        val issueId = readableIdFromContent ?: metadataIssueId
        
        val issueTitleElement = obj.get("issueTitle")
        val issueTitle = if (issueTitleElement != null && !issueTitleElement.isJsonNull) {
            issueTitleElement.asString
        } else null
        
        val issueStatusElement = obj.get("issueStatus")
        val issueStatus = if (issueStatusElement != null && !issueStatusElement.isJsonNull) {
            issueStatusElement.asString
        } else null

        var description: String? = null
        val descElement = obj.get("description")
        val descRaw = if (descElement != null && !descElement.isJsonNull) {
            descElement.asString
        } else null
        if (descRaw != null) {
            description = tryDecodeGzip(descRaw) ?: descRaw
        }

        return NotificationMetadata(issueId, issueTitle, issueStatus, description)
    }

    private fun tryDecodeGzip(value: String): String? {
        try {
            val decoded = Base64.getDecoder().decode(value)
            val result = GZIPInputStream(ByteArrayInputStream(decoded))
                .bufferedReader()
                .use { it.readText() }
            return result
        } catch (e: Exception) {
            return null
        }
    }

    fun getRecentIssues(sinceTimestamp: Long): List<Issue> {
        val url = "${baseUrl.trimEnd('/')}/api/issues?fields=id,idReadable,summary,description,updated,customFields(name,value(name))&\$top=50"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("YouTrack API error: ${response.code}")
        }

        val body = response.body?.string()
        if (body == null) return emptyList()

        val root = gson.fromJson(body, JsonElement::class.java)
        if (!root.isJsonArray) return emptyList()

        val issues = mutableListOf<Issue>()

        for (element in root.asJsonArray) {
            if (!element.isJsonObject) continue

            val obj = element.asJsonObject
            
            // Use idReadable (e.g., DEMO-35) instead of internal id (e.g., 3-45)
            val idReadableElement = obj.get("idReadable")
            val idReadable = if (idReadableElement != null && !idReadableElement.isJsonNull) {
                idReadableElement.asString
            } else null
            
            val idElement = obj.get("id")
            val id = idReadable ?: if (idElement != null && !idElement.isJsonNull) {
                idElement.asString
            } else null
            if (id == null) continue

            val updated = obj.get("updated")?.asLong
            if (updated == null || updated <= sinceTimestamp) continue

            val summaryElement = obj.get("summary")
            val summary = if (summaryElement != null && !summaryElement.isJsonNull) {
                summaryElement.asString
            } else ""
            
            val descriptionElement = obj.get("description")
            val description = if (descriptionElement != null && !descriptionElement.isJsonNull) {
                descriptionElement.asString
            } else null

            // Find State field
            var state: String? = null
            val customFields = obj.getAsJsonArray("customFields")
            if (customFields != null) {
                for (field in customFields) {
                    val fieldObj = field.asJsonObject
                    val fieldNameElement = fieldObj.get("name")
                    val fieldName = if (fieldNameElement != null && !fieldNameElement.isJsonNull) {
                        fieldNameElement.asString
                    } else null
                    
                    if (fieldName == "State") {
                        val valueObj = fieldObj.getAsJsonObject("value")
                        if (valueObj != null) {
                            val stateNameElement = valueObj.get("name")
                            state = if (stateNameElement != null && !stateNameElement.isJsonNull) {
                                stateNameElement.asString
                            } else null
                        }
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

        val payload = mutableMapOf<String, Any>(
            "project" to mapOf("id" to projectId),
            "summary" to summary
        )
        if (description != null) {
            payload["description"] = description
        }

        val jsonBody = gson.toJson(payload)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to create issue: ${response.code}")
        }

        val body = response.body?.string()
        if (body == null) throw IllegalStateException("Empty response")

        val root = gson.fromJson(body, JsonObject::class.java)

        val idReadableElement = root.get("idReadable")
        val idReadable = if (idReadableElement != null && !idReadableElement.isJsonNull) {
            idReadableElement.asString
        } else null
        if (idReadable != null) return idReadable

        val idElement = root.get("id")
        val idFallback = if (idElement != null && !idElement.isJsonNull) {
            idElement.asString
        } else null
        return idFallback ?: "CREATED"
    }
}