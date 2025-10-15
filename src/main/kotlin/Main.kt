import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.entities.KeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import model.Issue
import model.Notification
import youtrack.YouTrackClient
import java.util.Properties

fun main(): Unit = runBlocking {
    val config = loadConfig()

    val youTrackUrl = config.getProperty("youtrack.url")
    val youTrackToken = config.getProperty("youtrack.token")
    val youTrackProjectId = config.getProperty("youtrack.projectId")
    val telegramToken = config.getProperty("telegram.token")
    val chatId = config.getProperty("telegram.chatId").toLong()
    val pollInterval = config.getProperty("polling.interval").toLong()

    val ytClient = YouTrackClient(youTrackUrl, youTrackToken)
    val sentNotificationIds = mutableSetOf<String>()
    var lastIssueCheckTime = System.currentTimeMillis()
    var sentCount = 0
    var awaitingIssueSummary = false

    val bot = bot {
        token = telegramToken

        dispatch {
            command("start") {
                val msg = "<b>YouTrack Telegram Bot</b>\n\n" +
                        "Automatic notifications from YouTrack and issue creation.\n\n" +
                        "Commands: /help"

                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = msg,
                    parseMode = ParseMode.HTML,
                    replyMarkup = getMainKeyboard()
                )
            }

            command("help") {
                val msg = """
                    <b>Commands:</b>
                    /create &lt;text&gt; - create an issue
                    /status - bot statistics
                    
                    <b>Example:</b>
                    /create Fix login button bug
                    
                    YouTrack: <code>${youTrackUrl}</code>
                    Poll interval: ${pollInterval}s
                """.trimIndent()

                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = msg,
                    parseMode = ParseMode.HTML
                )
            }

            command("status") {
                val msg = "<b>Status</b>\n\n" +
                        "YouTrack: <code>$youTrackUrl</code>\n" +
                        "Notifications: $sentCount\n" +
                        "Poll interval: ${pollInterval}s"

                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = msg,
                    parseMode = ParseMode.HTML
                )
            }

            command("create") {
                val text = args.joinToString(" ").trim()

                if (text.isBlank()) {
                    awaitingIssueSummary = true
                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "Please enter the issue summary:",
                        parseMode = ParseMode.HTML
                    )
                } else {
                    awaitingIssueSummary = false
                    createIssue(bot, chatId, text, ytClient, youTrackProjectId, youTrackUrl)
                }
            }

            text {
                val messageText = message.text ?: return@text
                if (awaitingIssueSummary && message.chat.id == chatId && !messageText.startsWith("/")) {
                    awaitingIssueSummary = false
                    val summary = messageText.trim()
                    if (summary.isNotBlank()) {
                        createIssue(bot, chatId, summary, ytClient, youTrackProjectId, youTrackUrl)
                    }
                }
            }
        }
    }

    bot.startPolling()
    println("Bot started, polling every ${pollInterval}s")

    while (true) {
        try {
            val notifications = ytClient.getNotifications()
            println("Fetched ${notifications.size} notifications")

            for (notif in notifications) {
                println("Processing notification: ${notif.id}, issueId: ${notif.metadata.issueId}")

                if (sentNotificationIds.add(notif.id)) {
                    println("NEW notification, sending to Telegram...")
                    val issueId = notif.metadata.issueId ?: notif.id
                    val link = "${youTrackUrl.trimEnd('/')}/issue/$issueId"

                    val keyboard = InlineKeyboardMarkup.create(
                        listOf(InlineKeyboardButton.Url(text = "Open in YouTrack", url = link))
                    )

                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "<b>YouTrack Notification</b>\n\n${buildNotificationMessage(notif)}",
                        parseMode = ParseMode.HTML,
                        replyMarkup = keyboard
                    )
                    sentCount++
                    println("Sent to Telegram successfully")
                } else {
                    println("DUPLICATE notification, skipping")
                }
            }

            val currentTime = System.currentTimeMillis()
            val issues = ytClient.getRecentIssues(lastIssueCheckTime)
            println("Fetched ${issues.size} recent issues (updated since $lastIssueCheckTime)")

            for (issue in issues) {
                println("Processing issue: ${issue.id}")
                val link = "${youTrackUrl.trimEnd('/')}/issue/${issue.id}"

                val keyboard = InlineKeyboardMarkup.create(
                    listOf(InlineKeyboardButton.Url(text = "Open in YouTrack", url = link))
                )

                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "<b>YouTrack Issue</b>\n\n${buildIssueMessage(issue)}",
                    parseMode = ParseMode.HTML,
                    replyMarkup = keyboard
                )
                sentCount++
                println("Sent issue update to Telegram: ${issue.id}")
            }

            lastIssueCheckTime = currentTime
            println("Updated lastIssueCheckTime to $currentTime")

            // Cleanup old notifications to avoid memory leak
            if (sentNotificationIds.size > 1000) {
                println("Clearing notification cache (${sentNotificationIds.size} items)")
                sentNotificationIds.clear()
            }

        } catch (e: Exception) {
            println("ERROR: ${e.message}")
            e.printStackTrace()
        }

        println("Sleeping for ${pollInterval}s...")
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

fun buildNotificationMessage(n: Notification): String {
    val issueId = n.metadata.issueId ?: extractIssueIdFromContent(n.content)
    val title = n.metadata.issueTitle ?: extractTitleFromContent(n.content)
    
    // Clean content from HTML and format for Telegram
    val cleanContent = cleanAndFormatContent(n.content)

    var message = ""
    
    if (issueId.isNotBlank()) {
        message += "<b>${escapeHtml(issueId)}</b>\n"
    }

    if (title.isNotBlank()) {
        message += "${escapeHtml(title)}\n"
    }

    if (cleanContent.isNotBlank()) {
        message += "\n${cleanContent}"
    }

    return message.ifBlank { "New notification from YouTrack" }
}

fun buildIssueMessage(issue: Issue): String {
    var message = "<b>${escapeHtml(issue.id)}</b>\n${escapeHtml(issue.summary)}"

    if (!issue.state.isNullOrBlank()) {
        message += "\n\nStatus: ${escapeHtml(issue.state)}"
    }

    if (!issue.description.isNullOrBlank()) {
        val desc = issue.description.take(200).trim()
        if (desc.isNotBlank()) {
            message += "\n\n${convertMarkdownToHtml(desc)}"
            if (issue.description.length > 200) {
                message += "..."
            }
        }
    }

    return message
}

// Clean HTML tags and format content for Telegram
fun cleanAndFormatContent(text: String): String {
    // Remove HTML paragraph tags but keep content
    var clean = text
        .replace(Regex("<p>\\s*"), "")
        .replace(Regex("</p>\\s*"), "\n")
        .replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "") // Remove all other HTML tags
        .trim()

    // Remove "You received this message" блок
    clean = clean.replace(Regex("You received this message.*", RegexOption.DOT_MATCHES_ALL), "")
    
    // Remove separators
    clean = clean.replace(Regex("-{4,}.*", RegexOption.DOT_MATCHES_ALL), "")
    
    // Remove URLs (they will be in the button)
    clean = clean.replace(Regex("https?://[^\\s]+"), "")
    
    // Remove YouTrack service messages
    clean = clean.replace(Regex("New issue was reported by.*"), "")
    clean = clean.replace(Regex("You changed issue:?.*"), "")
    clean = clean.replace(Regex("Issue was created by.*"), "")
    clean = clean.replace(Regex("Issue was updated by.*"), "")
    
    // Remove arrows
    clean = clean.replace("→", "")
    
    // Remove "Created at" info
    clean = clean.replace(Regex("Created at .*"), "")
    
    // Extract only useful info (Description, Tags, etc)
    val descriptionMatch = Regex("Description:\\s*(.+?)(?=\\n|Tags:|$)").find(clean)
    val tagsMatch = Regex("Tags:\\s*(.+?)(?=\\n|$)").find(clean)
    
    val parts = mutableListOf<String>()
    
    descriptionMatch?.groupValues?.get(1)?.trim()?.let { 
        if (it.isNotBlank()) parts.add(it)
    }
    
    tagsMatch?.groupValues?.get(1)?.trim()?.let { 
        if (it.isNotBlank()) parts.add("Tags: ${it.replace("+", "")}")
    }
    
    clean = if (parts.isNotEmpty()) {
        parts.joinToString("\n")
    } else {
        // If no structured data found, just clean up what we have
        clean.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.matches(Regex("[A-Z]+-\\d+.*")) } // Remove issue ID lines
            .take(3) // Max 3 lines
            .joinToString("\n")
    }

    // Remove multiple newlines
    clean = clean.replace(Regex("\n{2,}"), "\n").trim()

    // Limit content length
    if (clean.length > 300) {
        clean = clean.take(297) + "..."
    }

    // Now escape HTML entities
    clean = escapeHtml(clean)

    return clean
}

// Extract issue ID from content
fun extractIssueIdFromContent(content: String): String {
    val issueIdRegex = Regex("""([A-Z]+-\d+)""")
    return issueIdRegex.find(content)?.groupValues?.get(1) ?: ""
}

// Extract title from content when metadata doesn't have it
fun extractTitleFromContent(content: String): String {
    // Look for pattern: ISSUE-123 title
    val titleRegex = Regex("""[A-Z]+-\d+\s+(.+?)(?:\n|<|https)""")
    val match = titleRegex.find(content)
    return match?.groupValues?.get(1)?.trim() ?: ""
}

fun convertMarkdownToHtml(text: String): String {
    // First, clean HTML tags if present
    var result = text
        .replace(Regex("<p>\\s*"), "")
        .replace(Regex("</p>\\s*"), "\n")
        .replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "") // Remove all other HTML tags
    
    // Then escape HTML entities and convert markdown
    result = result
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        .replace(Regex("\\*(.+?)\\*"), "<b>$1</b>")
        .replace(Regex("_(.+?)_"), "<i>$1</i>")
        .replace(Regex("~~(.+?)~~"), "<s>$1</s>")
        .replace(Regex("`(.+?)`"), "<code>$1</code>")
    
    return result
}

fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

fun getMainKeyboard(): KeyboardReplyMarkup {
    return KeyboardReplyMarkup(
        keyboard = listOf(
            listOf(
                KeyboardButton("/status"),
                KeyboardButton("/help")
            ),
            listOf(
                KeyboardButton("/create")
            )
        ),
        resizeKeyboard = true,
        oneTimeKeyboard = false
    )
}

fun createIssue(
    bot: com.github.kotlintelegrambot.Bot,
    chatId: Long,
    summary: String,
    ytClient: YouTrackClient,
    projectId: String,
    baseUrl: String
) {
    try {
        val issueId = ytClient.createIssue(projectId, summary)
        val link = "${baseUrl.trimEnd('/')}/issue/$issueId"

        val keyboard = InlineKeyboardMarkup.create(
            listOf(InlineKeyboardButton.Url(text = "Open in YouTrack", url = link))
        )

        bot.sendMessage(
            chatId = ChatId.fromId(chatId),
            text = "<b>Issue Created</b>\n\n<b>$issueId</b>\n${escapeHtml(summary)}",
            parseMode = ParseMode.HTML,
            replyMarkup = keyboard
        )
    } catch (e: Exception) {
        bot.sendMessage(
            chatId = ChatId.fromId(chatId),
            text = "Failed to create issue: ${e.message}"
        )
    }
}