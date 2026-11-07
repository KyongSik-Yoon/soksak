package com.voicerewriter.textproc

/**
 * Classifies the focused app into a context category (code/terminal, email, chat, ...).
 *
 * Terminals are not automatically code: most dictation into one is a natural-language prompt
 * to an AI agent, so a terminal counts as code only when the text looks like a shell command.
 * Dedicated code editors are always code. The package map is partial/substring-based for
 * robustness across OEM variants.
 */
object AppContext {

    enum class Category(val key: String, val label: String) {
        GENERIC("generic", "Other apps"),
        CODE("code", "Code & terminals"),
        EMAIL("email", "Email & docs"),
        CHAT("chat", "Chat & messaging"),
        SOCIAL("social", "Social"),
        NOTES("notes", "Notes"),
    }

    private val emailHints = listOf(
        "gmail", "com.google.android.gm", "outlook", "office.word", "office.outlook",
        "apps.docs", "yahoo.mobile.client.android.mail", "fastmail", "spark", "notion",
        "superhuman", "proton.android.mail", "protonmail",
    )
    private val chatHints = listOf(
        "whatsapp", "telegram", "org.thoughtcrime.securesms", "messaging", "messenger",
        "facebook.orca", "slack", "discord", "com.google.android.apps.messaging",
        "samsung.android.messaging", "wechat", "viber", "skype", "teams", "google.android.talk",
    )
    private val socialHints = listOf(
        "twitter", "com.twitter", "x.android", "reddit", "instagram", "threads", "mastodon",
        "bluesky", "bsky", "linkedin", "snapchat", "tiktok",
    )
    private val notesHints = listOf(
        "keep", "samsung.android.app.notes", "obsidian", "bear", "standardnotes",
        "simplenote", "joplin", "evernote", "onenote",
    )

    /** Category of [text] dictated into [pkg]; code/terminal is decided by content. */
    fun categoryFor(pkg: String?, text: String): Category {
        if (useCodeMode(pkg, text)) return Category.CODE
        val p = pkg?.lowercase() ?: return Category.GENERIC
        return when {
            emailHints.any { p.contains(it) } -> Category.EMAIL
            chatHints.any { p.contains(it) } -> Category.CHAT
            socialHints.any { p.contains(it) } -> Category.SOCIAL
            notesHints.any { p.contains(it) } -> Category.NOTES
            else -> Category.GENERIC
        }
    }

    // Dedicated code editors — content here is overwhelmingly code.
    private val codeEditors = setOf(
        "com.microsoft.vscode", "com.itsaky.androidide", "com.foxdebug.acode",
        "com.spck.editor", "com.rhmsoft.code", "com.sketchware.remod",
        "com.jetbrains.intellij",
    )

    // Terminals — could be commands OR (often) AI prompts; decide by content.
    private val terminals = setOf(
        "com.googlecode.iterm2", "com.apple.terminal", "com.termux", "com.termux.styling",
        "com.server.auditor.ssh.client", "org.connectbot", "dev.warp.warp",
    )

    private val terminalHints = listOf("term", "terminal", "iterm", "tmux", "ssh", "shell", "console")

    // Common command leaders — a strong "this is a shell command" signal.
    private val commandVerbs = setOf(
        "cd", "ls", "pwd", "git", "npm", "npx", "node", "yarn", "pnpm", "python", "python3",
        "pip", "pip3", "brew", "docker", "kubectl", "ssh", "scp", "sudo", "cat", "less",
        "tail", "head", "grep", "rg", "awk", "sed", "curl", "wget", "make", "cargo", "go",
        "rustc", "tmux", "chmod", "chown", "mkdir", "rm", "cp", "mv", "touch", "export",
        "source", "kill", "ps", "top", "vim", "nvim", "nano", "code", "open", "echo",
    )

    /** Whether [text] dictated into [pkg] is code/terminal input rather than prose. */
    private fun useCodeMode(pkg: String?, text: String): Boolean {
        val p = pkg?.lowercase() ?: return false
        if (p in codeEditors) return true
        if (p in terminals || terminalHints.any { p.contains(it) }) return looksLikeCommand(text)
        return false
    }

    /** Conservative: commands are short and start with a known verb / flag / path. */
    private fun looksLikeCommand(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val words = t.split(Regex("\\s+"))
        if (words.size > 10) return false // long => natural-language prompt, not a command
        val first = words[0].lowercase().trim('$', '>', '#')
        if (first in commandVerbs) return true
        if (t.startsWith("/") || t.startsWith("~/") || t.startsWith("./")) return true
        if (Regex("\\s-{1,2}[A-Za-z]").containsMatchIn(t)) return true // has a CLI flag
        return false
    }
}
