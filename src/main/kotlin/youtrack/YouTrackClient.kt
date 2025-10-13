package youtrack

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import model.Notification
import model.NotificationMetadata
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

class YouTrackClient(
    private val baseUrl: String,
    private val token: String
) {
    private val client = OkHttpClient()
    private val gson = Gson()
    
    // Fetch notifications from YouTrack API
    fun getNotifications(): List<Notification> {
        val url = "${baseUrl.trimEnd('/')}/api/users/notifications?fields=id,content,metadata"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("YouTrack HTTP ${response.code}")
        }

        val body = response.body?.string() ?: return emptyList()
        val root = gson.fromJson(body, JsonElement::class.java)
        
        return if (root.isJsonArray) {
            root.asJsonArray.mapNotNull { parseNotification(it) }
        } else {
            emptyList()
        }
    }

    private fun parseNotification(element: JsonElement): Notification? {
        if (!element.isJsonObject) return null
        
        val obj = element.asJsonObject
        val id = obj.get("id")?.asString ?: return null
        val contentRaw = obj.get("content")
        
        val content = when {
            contentRaw?.isJsonPrimitive == true -> contentRaw.asString
            else -> contentRaw?.toString() ?: ""
        }

        val metadataObj = obj.getAsJsonObject("metadata")
        val metadata = parseMetadata(metadataObj)

        return Notification(
            id = id,
            content = content,
            metadata = metadata
        )
    }

    private fun parseMetadata(obj: JsonObject?): NotificationMetadata {
        if (obj == null) {
            return NotificationMetadata(null, null, null, null)
        }

        // YouTrack sometimes sends description as base64+gzipped string
        val description = obj.get("description")?.asString?.let { 
            decodeIfGzipped(it) 
        }

        return NotificationMetadata(
            issueId = obj.get("issueId")?.asString,
            issueTitle = obj.get("issueTitle")?.asString,
            issueStatus = obj.get("issueStatus")?.asString,
            description = description
        )
    }

    // Try to decode base64+gzip, fallback to original string if it fails
    private fun decodeIfGzipped(text: String): String {
        return try {
            val decoded = Base64.getDecoder().decode(text)
            val gzip = GZIPInputStream(ByteArrayInputStream(decoded))
            gzip.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            text
        }
    }
}