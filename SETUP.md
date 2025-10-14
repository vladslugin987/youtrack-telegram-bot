# Setup and Usage

## Getting Required Data

### YouTrack

**URL**: your instance address (e.g., `https://your-instance.youtrack.cloud`)

**Token**: 
1. Profile → Authentication → New token
2. Scope: YouTrack
3. Copy the token

**Project ID**:
1. Open your project
2. Settings → General
3. Find ID (e.g., `0-0`)

### Telegram

**Bot Token**:
1. Message [@BotFather](https://t.me/BotFather)
2. Create bot
3. Copy the token

**Chat ID**:
1. Send any message to your bot
2. Open `https://api.telegram.org/bot<YOUR_BOT_TOKEN>/getUpdates`
3. Find `"chat":{"id":123456789}`

## Configuration

Copy `config.properties.example` to `src/main/resources/config.properties`:

```properties
youtrack.url=https://your-instance.youtrack.cloud
youtrack.token=perm:YOUR_YOUTRACK_TOKEN
youtrack.projectId=0-0
telegram.token=1234567890:ABCdefGHIjklMNOpqrsTUVwxyz
telegram.chatId=123456789
polling.interval=60
```

## Running

Direct run:
```bash
./gradlew run
```

Or build JAR:
```bash
./gradlew shadowJar
java -jar build/libs/youtrack-telegram-bot-1.0.0-all.jar
```

## Usage

### Commands

- `/start` - start the bot and show keyboard
- `/help` - show command help
- `/status` - show bot statistics
- `/create <text>` - create an issue

### Examples

```
/create Fix login button not working on mobile
/create Add dark mode to settings page
```

### Keyboard

When running `/start`, a keyboard appears with `/status` and `/help` buttons.

### Inline Buttons

Each notification and created issue contains an "Open in YouTrack" button for quick navigation.

## Functionality

**Part 1 - Notifications**
- Automatic notification retrieval from YouTrack
- Real-time issue update tracking
- Markdown formatting
- Direct links to issues

**Part 2 - Interactive Commands**
- Issue creation via `/create`
- Command text → issue summary
- Confirmation with link

## Structure

```
src/main/kotlin/
├── Main.kt                      # Entry point, bot and handlers
├── model/
│   ├── Issue.kt                # Issue model
│   └── Notification.kt         # Notification model
└── youtrack/
    └── YouTrackClient.kt       # REST API client
```
