# YouTrack Telegram Bot

Internship task for JetBrains Internship 2025-2026 (project "YouTrack Integration with Microsoft Teams").

**Kotlin** was chosen as the programming language and **Telegram** as the messenger.

## Description

Bot for integrating YouTrack with Telegram. Sends notifications from YouTrack and allows creating issues directly from the messenger.

## Demo

[![Watch Demo](https://img.youtube.com/vi/IHGkljvpUqQ/maxresdefault.jpg)](https://youtu.be/IHGkljvpUqQ)

[Watch on YouTube](https://youtu.be/IHGkljvpUqQ)

## Implementation

**Part 1 - Notifications**
- Connection to YouTrack REST API
- Periodic polling for notifications
- Message formatting with markdown
- Tracking issue updates

**Part 2 - Commands**
- Creating issues via `/create` command
- Command text is used as issue summary

## Installation and Setup

### 1. YouTrack Setup

Get a token in YouTrack:
- Profile → Authentication → New token
- Scope: YouTrack

Find project ID:
- Open project → Settings → General → Project ID

### 2. Telegram Setup

Create a bot via [@BotFather](https://t.me/BotFather):

Get Chat ID:
- Send any message to your bot
- Open: `https://api.telegram.org/bot<YOUR_TOKEN>/getUpdates`
- Find `chat.id` value

### 3. Configuration

Copy `config.properties.example` to `src/main/resources/config.properties`:

```properties
youtrack.url=https://your-instance.youtrack.cloud
youtrack.token=perm:YOUR_TOKEN
youtrack.projectId=0-0
telegram.token=YOUR_BOT_TOKEN
telegram.chatId=YOUR_CHAT_ID
polling.interval=60
```

### 4. Running

```bash
./gradlew run
```

Or build JAR:
```bash
./gradlew shadowJar
java -jar build/libs/youtrack-telegram-bot-1.0.0-all.jar
```

## Commands

- `/start` - start the bot
- `/help` - show help
- `/status` - show statistics
- `/create <text>` - create an issue

Examples:
```
/create Fix login button bug
/create Add dark mode feature
```

## Project Structure

```
src/main/kotlin/
├── Main.kt                 # Entry point and bot commands
├── model/
│   ├── Issue.kt           # Issue model
│   └── Notification.kt    # Notification model
└── youtrack/
    └── YouTrackClient.kt  # REST API client
```

## Technologies

- Kotlin
- OkHttp - HTTP client
- Gson - JSON parsing
- kotlin-telegram-bot - Telegram API
- Gradle

## Implementation Details

- Avoiding duplicate notifications via ID caching
- Handling gzip-compressed data in metadata
- Inline buttons for quick navigation to YouTrack
- Reply keyboard for frequent commands
- JsonNull handling for stability

## License

MIT
