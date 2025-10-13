import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import model.Notification
import youtrack.YouTrackClient
import java.util.Properties

fun main(): Unit = runBlocking {
    val config = loadConfig()

    val youTrackUrl = config.getProperty("youtrack.url")
    val youTrackToken = config.getProperty("youtrack.token")
    val telegramToken = config.getProperty("telegram.token")
    val chatId = config.getProperty("telegram.chatId").toLong()
    val pollInterval = config.getProperty("polling.interval").toLong()

    val ytClient = YouTrackClient(youTrackUrl, youTrackToken)
    val bot = bot { token = telegramToken }
    bot.startPolling()

    val sent = mutableSetOf<String>()
    var messageCount = 0

    while (true) {
        try {
            val notifications = ytClient.getNotifications()
            println("Fetched ${notifications.size} notifications")
            
            for (n in notifications) {
                if (sent.add(n.id)) {
                    val message = buildMessage(n)
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = message,
                        parseMode = ParseMode.MARKDOWN
                    )
                    messageCount++
                    println("Sent notification #${messageCount}: ${n.id}")
                }
            }
            if (sent.size > 1000) {
                sent.clear()
                println("Cleared sent cache")
            }
        } catch (e: Exception) {
            println("Error: ${e.message}")
            e.printStackTrace()
        }

        delay(pollInterval * 1000)
    }
}

fun loadConfig(): Properties {
    val props = Properties()
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("config.properties")
        ?: error("config.properties not found")
    stream.use { props.load(it) }
    return props
}

fun buildMessage(n: Notification): String {
    val title = n.metadata.issueTitle ?: "No title"
    val status = n.metadata.issueStatus ?: "Unknown"
    val issueId = n.metadata.issueId ?: n.id
    val desc = n.metadata.description ?: n.content
    
    return """
        *ID:* ${escape(issueId)}
        *Title:* ${escape(title)}
        *Status:* ${escape(status)}
        *Description:*
        ${escape(desc)}
    """.trimIndent()
}

// Escape special markdown chars for Telegram
fun escape(s: String) = s
    .replace("\\", "\\\\")
    .replace("_", "\\_")
    .replace("*", "\\*")
    .replace("[", "\\[")
    .replace("]", "\\]")
    .replace("(", "\\(")
    .replace(")", "\\)")
    .replace("`", "\\`")