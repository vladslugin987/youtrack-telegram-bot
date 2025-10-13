class YouTrackClient(
    private val baseUrl: String,
    private val token: String
) {
    private val client = OkHttpClient()
    private val gson = Gson()
    
    fun getNotifications(): List<Notification> {
        val request = Request.Builder()
            .url("$baseUrl/api/users/notifications?fields=id,content,metadata")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .build()
    }
}
