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
                         "Notifications sent: $sentCount\n" +
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


    // Main loop
    while (true) {
        try {
            // Chekc notifications
            val notifications = ytClient.getNotifications()
            notifications.forEach { notif ->
                if (sentNotificationIds.add(notif.id)) {
                    val issueId = notif.metadata.issueId ?: notif.id
                    val link = "${youTrackUrl.trimEnd('/')}/issue/$issueId"

                    val keyboard = InlineKeyboardMarkup.create(
                        listOf(InlineKeyboardButton.Url(text = "Open in YouTrack", url = link))
                    )

                    bot.sendMessage(
                        chatId = ChatId.fromId(chatId),
                        text = "<b>Notification</b>\n\n${buildNotificationMessageHtml(notif)}",
                        parseMode = ParseMode.HTML,
                        replyMarkup = keyboard
                    )
                    sentCount++
                }
            }

            // Check recent issues
            val currentTime = System.currentTimeMillis()
            val issues = ytClient.getRecentIssues(lastIssueCheckTime)
            issues.forEach { issue ->
                val link = "${youTrackUrl.trimEnd('/')}/issue/${issue.id}"

                val keyboard = InlineKeyboardMarkup.create(
                    listOf(InlineKeyboardButton.Url(text = "Open in YouTrack", url = link))
                )

                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "<b>Issue Update</b>\n\n${buildIssueMessageHtml(issue)}",
                    parseMode = ParseMode.HTML,
                    replyMarkup = keyboard
                )
                sentCount++
            }
            lastIssueCheckTime = currentTime
            
            // Cleanup
            if (sentNotificationIds.size > 1000) sentNotificationIds.clear()

        } catch (e: Exception) {
            println("Error fetching data: ${e.message}")
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

fun buildNotificationMessageHtml(n: Notification): String {
    val title = n.metadata.issueTitle ?: "No title"
    val status = n.metadata.issueStatus ?: "Unknown"
    val issueId = n.metadata.issueId ?: n.id
    val desc = n.metadata.description ?: n.content

    return "<b>${escapeHtml(issueId)}</b>\n" +
           "Status: ${escapeHtml(status)}\n" +
           "Title: ${escapeHtml(title)}\n\n" +
           convertMarkdownToHtml(desc)
}

fun buildIssueMessageHtml(issue: Issue): String {
    val status = issue.state ?: "No Status"

    var message = "<b>${escapeHtml(issue.id)}</b>\n" +
                  "Status: ${escapeHtml(status)}\n" +
                  "Summary: ${escapeHtml(issue.summary)}"

    if (!issue.description.isNullOrBlank()) {
        val desc = issue.description.take(300)
        message += "\n\n${convertMarkdownToHtml(desc)}"
    }

    return message
}

fun convertMarkdownToHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        .replace(Regex("\\*(.+?)\\*"), "<b>$1</b>")
        .replace(Regex("_(.+?)_"), "<i>$1</i>")
        .replace(Regex("~~(.+?)~~"), "<s>$1</s>")
        .replace(Regex("`(.+?)`"), "<code>$1</code>")
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
            text = "<b>Issue created</b>\n\nID: <code>$issueId</code>\nSummary: ${escapeHtml(summary)}",
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