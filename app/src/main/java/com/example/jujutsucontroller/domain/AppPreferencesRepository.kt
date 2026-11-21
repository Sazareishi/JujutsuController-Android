package com.example.jujutsucontroller.domain

import android.content.Context
import java.io.File

data class SetupTemplate(
    val id: String = "",
    val templateName: String = "デフォルト初期設定",
    val gitUserName: String = "",
    val gitUserEmail: String = "",
    val repositoryUrl: String = "",
    val parentDirectoryPath: String = "/storage/emulated/0/jjc",
    val authTypeName: String = AuthType.HTTPS.name,
    val sshPrivateKeyPath: String = "/data/data/com.termux/files/home/.ssh/id_ed25519",
    val registeredRepoPath: String? = null
) {
    fun resolvedAuthType(): AuthType {
        return runCatching { AuthType.valueOf(authTypeName) }.getOrDefault(AuthType.HTTPS)
    }
}

data class SavedAppSettings(
    val gitUserName: String = "",
    val gitUserEmail: String = "",
    val repositoryUrl: String = "",
    val parentDirectoryPath: String = "/storage/emulated/0/jjc",
    val activeRepositoryPath: String? = null,
    val registeredRepositories: List<String> = emptyList(),
    val templateName: String = "デフォルト初期設定",
    val authTypeName: String = AuthType.HTTPS.name,
    val sshPrivateKeyPath: String = "/data/data/com.termux/files/home/.ssh/id_ed25519",
    val hasTemplate: Boolean = false,
    val templates: List<SetupTemplate> = emptyList()
) {
    fun resolvedAuthType(): AuthType {
        return runCatching { AuthType.valueOf(authTypeName) }.getOrDefault(AuthType.HTTPS)
    }
}

class AppPreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("jujutsu_controller_prefs", Context.MODE_PRIVATE)

    fun loadSettings(): SavedAppSettings {
        val rawRepos = prefs.getString(KEY_REGISTERED_REPOS, "").orEmpty()
        val repos = rawRepos
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val active = prefs.getString(KEY_ACTIVE_REPO, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: repos.firstOrNull()

        val savedParent = prefs.getString(KEY_PARENT_DIR, "")?.trim().orEmpty()
        val savedUserName = prefs.getString(KEY_GIT_USER_NAME, "").orEmpty()
        val savedUserEmail = prefs.getString(KEY_GIT_USER_EMAIL, "").orEmpty()
        val savedRepoUrl = prefs.getString(KEY_REPO_URL, "").orEmpty()
        val savedTemplateName = prefs.getString(KEY_TEMPLATE_NAME, "")?.trim().orEmpty()
            .ifEmpty { "デフォルト初期設定" }
        val savedAuthType = prefs.getString(KEY_AUTH_TYPE, AuthType.HTTPS.name).orEmpty()
        val savedSshKey = prefs.getString(KEY_SSH_KEY_PATH, "")?.trim().orEmpty()
            .ifEmpty { "/data/data/com.termux/files/home/.ssh/id_ed25519" }

        val loadedTemplates = loadTemplatesInternal(
            fallbackName = savedTemplateName,
            fallbackUserName = savedUserName,
            fallbackUserEmail = savedUserEmail,
            fallbackRepoUrl = savedRepoUrl,
            fallbackParentDir = if (savedParent.isNotEmpty()) savedParent else "/storage/emulated/0/jjc",
            fallbackAuthType = savedAuthType,
            fallbackSshKey = savedSshKey,
            repos = repos
        )

        val hasSavedTemplate = prefs.getBoolean(KEY_HAS_TEMPLATE, false) || loadedTemplates.isNotEmpty()

        return SavedAppSettings(
            gitUserName = savedUserName,
            gitUserEmail = savedUserEmail,
            repositoryUrl = savedRepoUrl,
            parentDirectoryPath = if (savedParent.isNotEmpty()) savedParent else "/storage/emulated/0/jjc",
            activeRepositoryPath = active,
            registeredRepositories = repos,
            templateName = savedTemplateName,
            authTypeName = savedAuthType,
            sshPrivateKeyPath = savedSshKey,
            hasTemplate = hasSavedTemplate,
            templates = loadedTemplates
        )
    }

    fun findExistingClonedRepositoryPath(
        repositoryUrl: String,
        parentDirectoryPath: String,
        registeredRepositories: List<String> = loadSettings().registeredRepositories
    ): String? {
        val cleanedUrl = repositoryUrl.trim()
        val repoFolderName = extractRepoFolderName(cleanedUrl)
        val cleanedParent = parentDirectoryPath.trim().trimEnd('/')
        val expectedPath = if (cleanedParent.isNotEmpty() && repoFolderName.isNotEmpty()) {
            "$cleanedParent/$repoFolderName"
        } else {
            ""
        }

        val candidates = buildList {
            if (expectedPath.isNotEmpty()) add(expectedPath)
            registeredRepositories.forEach { add(it.trim()) }
        }.filter { it.isNotEmpty() }.distinct()

        for (candidate in candidates) {
            if (repoFolderName.isNotEmpty() && candidate.trimEnd('/').endsWith("/$repoFolderName")) {
                val dir = File(candidate)
                if (dir.exists() && dir.isDirectory) {
                    return candidate
                }
            }
        }
        return null
    }

    fun saveTemplate(
        templateName: String,
        userName: String,
        userEmail: String,
        repositoryUrl: String,
        parentDirectoryPath: String,
        authType: AuthType,
        sshPrivateKeyPath: String,
        registeredRepoPath: String? = null
    ) {
        val cleanedName = templateName.trim().ifEmpty { "デフォルト初期設定" }
        val cleanedParent = parentDirectoryPath.trim().ifEmpty { "/storage/emulated/0/jjc" }
        val cleanedSshKey = sshPrivateKeyPath.trim().ifEmpty { "/data/data/com.termux/files/home/.ssh/id_ed25519" }
        val cleanedRepoUrl = repositoryUrl.trim()

        val currentRepos = loadSettings().registeredRepositories.toMutableList()
        val inferredRepoPath = registeredRepoPath?.trim()?.takeIf { it.isNotEmpty() }
            ?: run {
                val folder = extractRepoFolderName(cleanedRepoUrl)
                if (folder.isNotEmpty()) "${cleanedParent.trimEnd('/')}/$folder" else null
            }

        if (!inferredRepoPath.isNullOrBlank()) {
            currentRepos.remove(inferredRepoPath)
            currentRepos.add(0, inferredRepoPath)
        }

        val existingList = loadSettings().templates.toMutableList()
        val newEntry = SetupTemplate(
            id = "${cleanedName}|${cleanedRepoUrl}",
            templateName = cleanedName,
            gitUserName = userName.trim(),
            gitUserEmail = userEmail.trim(),
            repositoryUrl = cleanedRepoUrl,
            parentDirectoryPath = cleanedParent,
            authTypeName = authType.name,
            sshPrivateKeyPath = cleanedSshKey,
            registeredRepoPath = inferredRepoPath
        )
        existingList.removeAll { it.templateName == cleanedName || (cleanedRepoUrl.isNotEmpty() && it.repositoryUrl == cleanedRepoUrl && it.templateName == cleanedName) }
        existingList.add(0, newEntry)

        val serialized = existingList.take(20).joinToString("\n") { tpl ->
            listOf(
                encodeField(tpl.templateName),
                encodeField(tpl.gitUserName),
                encodeField(tpl.gitUserEmail),
                encodeField(tpl.repositoryUrl),
                encodeField(tpl.parentDirectoryPath),
                encodeField(tpl.authTypeName),
                encodeField(tpl.sshPrivateKeyPath),
                encodeField(tpl.registeredRepoPath.orEmpty())
            ).joinToString("\t")
        }

        prefs.edit()
            .putString(KEY_TEMPLATE_NAME, cleanedName)
            .putString(KEY_GIT_USER_NAME, userName.trim())
            .putString(KEY_GIT_USER_EMAIL, userEmail.trim())
            .putString(KEY_REPO_URL, cleanedRepoUrl)
            .putString(KEY_PARENT_DIR, cleanedParent)
            .putString(KEY_AUTH_TYPE, authType.name)
            .putString(KEY_SSH_KEY_PATH, cleanedSshKey)
            .putString(KEY_REGISTERED_REPOS, currentRepos.joinToString("\n"))
            .putString(KEY_TEMPLATES_LIST, serialized)
            .putBoolean(KEY_HAS_TEMPLATE, true)
            .apply()
    }

    fun saveCloneSession(
        userName: String,
        userEmail: String,
        repositoryUrl: String,
        parentDirectoryPath: String,
        clonedRepoPath: String,
        authType: AuthType? = null,
        sshPrivateKeyPath: String? = null,
        templateName: String? = null
    ) {
        val current = loadSettings().registeredRepositories.toMutableList()
        val cleanedRepo = clonedRepoPath.trim()
        if (cleanedRepo.isNotEmpty()) {
            current.remove(cleanedRepo)
            current.add(0, cleanedRepo)
        }

        val resolvedAuth = authType ?: loadSettings().resolvedAuthType()
        val resolvedKey = sshPrivateKeyPath?.trim()?.takeIf { it.isNotEmpty() } ?: loadSettings().sshPrivateKeyPath
        val resolvedTplName = templateName?.trim()?.takeIf { it.isNotEmpty() } ?: loadSettings().templateName

        val editor = prefs.edit()
            .putString(KEY_GIT_USER_NAME, userName.trim())
            .putString(KEY_GIT_USER_EMAIL, userEmail.trim())
            .putString(KEY_REPO_URL, repositoryUrl.trim())
            .putString(KEY_PARENT_DIR, parentDirectoryPath.trim().ifEmpty { "/storage/emulated/0/jjc" })
            .putString(KEY_REGISTERED_REPOS, current.joinToString("\n"))
            .putString(KEY_AUTH_TYPE, resolvedAuth.name)
            .putString(KEY_SSH_KEY_PATH, resolvedKey)
            .putBoolean(KEY_HAS_TEMPLATE, true)

        if (cleanedRepo.isNotEmpty()) {
            editor.putString(KEY_ACTIVE_REPO, cleanedRepo)
        }
        editor.apply()

        saveTemplate(
            templateName = resolvedTplName,
            userName = userName,
            userEmail = userEmail,
            repositoryUrl = repositoryUrl,
            parentDirectoryPath = parentDirectoryPath,
            authType = resolvedAuth,
            sshPrivateKeyPath = resolvedKey,
            registeredRepoPath = cleanedRepo.ifEmpty { null }
        )
    }

    fun updateInitialSettings(
        userName: String,
        userEmail: String,
        parentDirectoryPath: String
    ) {
        prefs.edit()
            .putString(KEY_GIT_USER_NAME, userName.trim())
            .putString(KEY_GIT_USER_EMAIL, userEmail.trim())
            .putString(KEY_PARENT_DIR, parentDirectoryPath.trim().ifEmpty { "/storage/emulated/0/jjc" })
            .apply()
    }

    fun setActiveRepository(repoPath: String) {
        val cleaned = repoPath.trim()
        if (cleaned.isEmpty()) return
        val current = loadSettings().registeredRepositories.toMutableList()
        current.remove(cleaned)
        current.add(0, cleaned)
        prefs.edit()
            .putString(KEY_REGISTERED_REPOS, current.joinToString("\n"))
            .putString(KEY_ACTIVE_REPO, cleaned)
            .apply()
    }

    fun removeRepository(repoPath: String) {
        val cleaned = repoPath.trim()
        val current = loadSettings().registeredRepositories.filterNot { it == cleaned }
        val active = prefs.getString(KEY_ACTIVE_REPO, null)?.trim()
        val editor = prefs.edit()
            .putString(KEY_REGISTERED_REPOS, current.joinToString("\n"))
        if (active == cleaned) {
            editor.putString(KEY_ACTIVE_REPO, current.firstOrNull() ?: "")
        }
        editor.apply()
    }

    private fun loadTemplatesInternal(
        fallbackName: String,
        fallbackUserName: String,
        fallbackUserEmail: String,
        fallbackRepoUrl: String,
        fallbackParentDir: String,
        fallbackAuthType: String,
        fallbackSshKey: String,
        repos: List<String>
    ): List<SetupTemplate> {
        val raw = prefs.getString(KEY_TEMPLATES_LIST, "").orEmpty()
        val parsed = raw.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size < 4) return@mapNotNull null
                val name = decodeField(parts.getOrElse(0) { "" }).ifEmpty { "デフォルト初期設定" }
                val uName = decodeField(parts.getOrElse(1) { "" })
                val uEmail = decodeField(parts.getOrElse(2) { "" })
                val rUrl = decodeField(parts.getOrElse(3) { "" })
                val pDir = decodeField(parts.getOrElse(4) { fallbackParentDir }).ifEmpty { fallbackParentDir }
                val aType = decodeField(parts.getOrElse(5) { fallbackAuthType }).ifEmpty { AuthType.HTTPS.name }
                val sKey = decodeField(parts.getOrElse(6) { fallbackSshKey }).ifEmpty { fallbackSshKey }
                val regPath = decodeField(parts.getOrElse(7) { "" }).takeIf { it.isNotEmpty() }
                SetupTemplate(
                    id = "$name|$rUrl",
                    templateName = name,
                    gitUserName = uName,
                    gitUserEmail = uEmail,
                    repositoryUrl = rUrl,
                    parentDirectoryPath = pDir,
                    authTypeName = aType,
                    sshPrivateKeyPath = sKey,
                    registeredRepoPath = regPath
                )
            }

        if (parsed.isNotEmpty()) return parsed

        if (fallbackUserName.isNotBlank() || fallbackUserEmail.isNotBlank() || fallbackRepoUrl.isNotBlank()) {
            return listOf(
                SetupTemplate(
                    id = "$fallbackName|$fallbackRepoUrl",
                    templateName = fallbackName,
                    gitUserName = fallbackUserName,
                    gitUserEmail = fallbackUserEmail,
                    repositoryUrl = fallbackRepoUrl,
                    parentDirectoryPath = fallbackParentDir,
                    authTypeName = fallbackAuthType,
                    sshPrivateKeyPath = fallbackSshKey,
                    registeredRepoPath = repos.firstOrNull()
                )
            )
        }
        return emptyList()
    }

    private fun extractRepoFolderName(url: String): String {
        val cleaned = url.trim().trimEnd('/')
        if (cleaned.isEmpty()) return ""
        val lastSegment = cleaned.substringAfterLast('/').substringAfterLast(':')
        return lastSegment.removeSuffix(".git").trim()
    }

    private fun encodeField(value: String): String {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    }

    private fun decodeField(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2; continue }
                    't' -> { sb.append('\t'); i += 2; continue }
                    'n' -> { sb.append('\n'); i += 2; continue }
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    companion object {
        private const val KEY_GIT_USER_NAME = "git_user_name"
        private const val KEY_GIT_USER_EMAIL = "git_user_email"
        private const val KEY_REPO_URL = "repository_url"
        private const val KEY_PARENT_DIR = "parent_directory_path"
        private const val KEY_ACTIVE_REPO = "active_repository_path"
        private const val KEY_REGISTERED_REPOS = "registered_repositories"
        private const val KEY_TEMPLATE_NAME = "template_name"
        private const val KEY_AUTH_TYPE = "auth_type_name"
        private const val KEY_SSH_KEY_PATH = "ssh_private_key_path"
        private const val KEY_HAS_TEMPLATE = "has_template"
        private const val KEY_TEMPLATES_LIST = "setup_templates_list"
    }
}