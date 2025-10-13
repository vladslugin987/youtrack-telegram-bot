data class Notification(
    val id: String,
    val content: String,
    val metadata: NotificationMetadata
)

data class NotificationMetadata(
    val issueId: String?,
    val issueTitle: String?,
    val issueStatus: String?,
    val description: String?
)
