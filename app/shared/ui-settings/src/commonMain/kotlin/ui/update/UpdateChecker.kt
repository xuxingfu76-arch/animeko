/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.network.protocol.ReleaseClass
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.tools.TimeFormatter
import me.him188.ani.utils.coroutines.withExceptionCollector
import me.him188.ani.utils.ktor.getPlatformKtorEngine
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.Platform
import me.him188.ani.utils.platform.currentPlatform
import me.him188.ani.utils.platform.isAndroidTv

/**
 * GitHub Releases API 返回的最小字段集. 只取本仓库更新检测需要的部分.
 */
@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("assets") val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

/**
 * @param serverUrls 版本 API 的服务器, 按顺序尝试, 前一个失败才用下一个. 端到端测试用它指向本机 mock 的服务器.
 */
class UpdateChecker(
    private val serverUrls: List<String> = DEFAULT_SERVER_URLS,
) {
    /**
     * 检查是否有更新的版本. 返回最新版本的信息, 或者 `null` 表示没有新版本.
     */
    suspend fun checkLatestVersion(
        releaseClass: ReleaseClass,
        currentVersion: String = currentAniBuildConfig.versionName,
    ): NewVersion? {
        require(serverUrls.isNotEmpty()) { "serverUrls must not be empty" }
        HttpClient(getPlatformKtorEngine()) {
            expectSuccess = true
        }.use { client ->
            withExceptionCollector {
                for (baseUrl in serverUrls) {
                    try {
                        return client.getVersionFromGithubReleases(baseUrl, currentVersion, releaseClass).also {
                            logger.info { "Got latest version from $baseUrl: ${it?.name}" }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        collect(e)
                    }
                }
                val finalException = getLast()!!
                if (finalException !is IOException) {
                    logger.error(finalException) { "Failed to get latest version" }
                }
                throwLast()
            }
        }
    }

    private suspend fun HttpClient.getVersionFromGithubReleases(
        baseUrl: String,
        currentVersion: String,
        releaseClass: ReleaseClass,
    ): NewVersion? {
        val releases = get(baseUrl) {
            url {
                appendPathSegments("repos", GITHUB_REPO, "releases")
            }
            parameter("per_page", "30")
        }.bodyAsText().let {
            json.decodeFromString(List<GithubRelease>.serializer(), it)
        }

        val platform = currentPlatform()
        val clientPlatform = platform.updateClientPlatform()
        val clientArch = platform.arch.displayName

        val candidates = releases.filter { release ->
            !release.draft && matchesReleaseClass(release, releaseClass)
        }

        if (candidates.isEmpty()) {
            return null
        }

        val relevant = candidates.filter { release ->
            hasAssetFor(release, clientPlatform, clientArch)
        }.ifEmpty { candidates }

        val sorted = relevant.sortedWith(
            compareByDescending<GithubRelease> { it.publishTimeEpochSeconds() }
                .thenByDescending { versionKey(it.tagName) },
        )

        val latest = sorted.firstOrNull() ?: return null
        val currentKey = versionKey(currentVersion.trimStart('v'))
        if (versionKey(latest.tagName.trimStart('v')) <= currentKey) {
            return null
        }

        val newer = sorted.filter { versionKey(it.tagName.trimStart('v')) > currentKey }

        return NewVersion(
            name = latest.tagName.removePrefix("v"),
            changelogs = newer.asSequence()
                .take(10)
                .map { Changelog(it.tagName.removePrefix("v"), formatTime(it.publishTimeEpochSeconds()), it.body) }
                .toList(),
            downloadUrlAlternatives = assetUrlsFor(latest, clientPlatform, clientArch),
            publishedAt = formatTime(latest.publishTimeEpochSeconds()),
        )
    }

    private fun matchesReleaseClass(release: GithubRelease, releaseClass: ReleaseClass): Boolean {
        val tag = release.tagName
        val isStable = !tag.contains("-")
        return when (releaseClass) {
            ReleaseClass.STABLE -> isStable
            ReleaseClass.BETA -> true
            ReleaseClass.ALPHA -> true
            else -> true
        }
    }

    private fun hasAssetFor(release: GithubRelease, clientPlatform: String, clientArch: String): Boolean =
        assetUrlsFor(release, clientPlatform, clientArch).isNotEmpty()

    /**
     * Android 安装包资产命名规则见 .github/workflows/android-apk.yml:
     * `animeko-android-<abi>-<buildType>.apk`.
     * `universal` 包含全部 ABI, 因此任何 arch 都匹配. 未匹配到时返回全部 apk, 让用户自行选择.
     */
    private fun assetUrlsFor(release: GithubRelease, clientPlatform: String, clientArch: String): List<String> {
        val apks = release.assets.filter { it.name.endsWith(".apk") }
        if (apks.isEmpty()) return emptyList()
        if (clientPlatform != "android" && clientPlatform != "androidtv") {
            return apks.map { it.browserDownloadUrl }
        }
        val exact = apks.filter { it.name.contains("-$clientArch-") || it.name.endsWith("-universal-") || it.name.contains("-universal-") }
        return exact.ifEmpty { apks }.map { it.browserDownloadUrl }
    }

    companion object {
        private val logger = logger<UpdateChecker>()

        /**
         * GitHub Releases API 根地址. [GITHUB_REPO] 是 fork 自己的仓库, 远离上游更新通道.
         */
        val DEFAULT_SERVER_URLS = listOf("https://api.github.com/")

        private const val GITHUB_REPO = "xuxingfu76-arch/animeko"

        private val json = Json {
            ignoreUnknownKeys = true
        }

        /**
         * 把 `6.4.0` / `6.4.0-beta02` 之类版本号转成可比较的整数序列.
         * 预发布段 (alpha/beta/rc) 按 ALPHA=1, BETA=2, RC=3, 正式=4 折算, 保证正式版大于同号预发布版.
         */
        internal fun versionKey(version: String): List<Int> {
            val core = version.substringBefore('-')
            val pre = version.substringAfter('-', "")
            val numbers = core.split('.').mapNotNull { it.trim().toIntOrNull() }
            val preRank = when {
                pre.isEmpty() -> 4
                pre.startsWith("rc", ignoreCase = true) -> 3
                pre.startsWith("beta", ignoreCase = true) -> 2
                pre.startsWith("alpha", ignoreCase = true) -> 1
                else -> 1
            }
            val preNumber = pre.filter { it.isDigit() }.toIntOrNull() ?: 0
            return numbers + listOf(preRank, preNumber)
        }

        private fun formatTime(
            seconds: Long,
        ): String = kotlin.runCatching { TimeFormatter().format(seconds * 1000) }.getOrElse { seconds.toString() }
    }
}

/** GitHub 的 `published_at` 是 ISO-8601 字符串, 转成 epoch 秒用于排序与展示. */
internal fun GithubRelease.publishTimeEpochSeconds(): Long {
    val text = publishedAt
    if (text.isBlank()) return 0L
    val parsed = runCatching {
        java.time.Instant.parse(text).epochSecond
    }.getOrNull()
    if (parsed != null) return parsed
    return runCatching {
        java.time.LocalDateTime.parse(text)
            .atZone(java.time.ZoneId.systemDefault())
            .toEpochSecond()
    }.getOrNull() ?: 0L
}

internal fun Platform.updateClientPlatform(): String =
    if (isAndroidTv()) "androidtv" else name.lowercase()
