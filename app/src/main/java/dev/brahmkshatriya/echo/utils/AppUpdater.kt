package dev.brahmkshatriya.echo.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.utils.ContextUtils.appVersion
import dev.brahmkshatriya.echo.utils.ContextUtils.getTempFile
import dev.brahmkshatriya.echo.utils.Serializer.toData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.use
import java.io.File
import java.util.zip.ZipFile

object AppUpdater {

    private val client = OkHttpClient()

    @Suppress("KotlinConstantConditions")
    suspend fun updateApp(app: App): File? {
        val throwableFlow = app.throwFlow
        val messageFlow = app.messageFlow
        val githubRepo = app.context.getString(R.string.app_github_repo)
        val appType = BuildConfig.BUILD_TYPE
        val version = appVersion()

        val urls = runCatching {
            when (appType) {
                "stable" -> {
                    val currentVersion = version.substringBefore('_')
                    val updateUrl = "https://api.github.com/repos/$githubRepo/releases"
                    listOfNotNull(getGithubUpdateUrl(currentVersion, updateUrl, client))
                }

                "nightly" -> {
                    val hash = version.substringBefore("(").substringAfter('_')
                    val id = getGithubWorkflowId(hash, githubRepo, client) ?: return null
                    nightlyArtifactUrls(id, githubRepo, client)
                }

                else -> return null
            }
        }.getOrElse {
            throwableFlow.emit(it)
            return null
        }

        messageFlow.emit(
            Message(
                app.context.run {
                    getString(R.string.downloading_update_for_x, getString(R.string.app_name))
                }
            )
        )
        return runCatching {
            var lastError: Throwable? = null
            urls.firstNotNullOfOrNull { url ->
                runCatching {
                    val file = downloadUpdate(app.context, url, client).getOrThrow()
                    if (appType == "stable") file else unzipApk(file)
                }.onFailure { lastError = it }.getOrNull()
            } ?: throw (lastError ?: Exception("No update source available"))
        }.getOrElse {
            throwableFlow.emit(it)
            return null
        }
    }

    private val githubRegex = Regex("https://api\\.github\\.com/repos/([^/]*)/([^/]*)/")
    suspend fun getGithubUpdateUrl(
        currentVersion: String,
        updateUrl: String,
        client: OkHttpClient
    ) = run {
        val (user, repo) = githubRegex.find(updateUrl)?.destructured
            ?: throw Exception("Invalid Github URL")
        val url = "https://api.github.com/repos/$user/$repo/releases/latest"
        val request = Request.Builder().url(url).build()
        val res = runCatching {
            client.newCall(request).await().use {
                it.body.string().toData<GithubReleaseResponse>()
            }.getOrThrow()
        }.getOrElse {
            throw Exception("Failed to fetch latest release", it)
        }
        if (res.tagName != currentVersion) {
            res.assets.sortedByDescending {
                it.name.contains(Build.SUPPORTED_ABIS.first())
            }.firstOrNull {
                it.name.endsWith("apk")
            }?.browserDownloadUrl ?: throw Exception("No EApk assets found")
        } else {
            null
        }
    }

    private suspend fun getGithubWorkflowId(
        hash: String,
        githubRepo: String,
        client: OkHttpClient
    ) = runCatching {
        val url =
            "https://api.github.com/repos/$githubRepo/actions/workflows/nightly.yml/runs?per_page=1&conclusion=success"
        val request = Request.Builder().url(url).build()
        client.newCall(request).await().body.string().toData<GithubRunsResponse>().getOrThrow()
            .workflowRuns.firstOrNull { it.sha.take(7) != hash }?.id
    }.getOrElse {
        throw Exception("Failed to fetch workflow ID", it)
    }

    /**
     * Builds the list of nightly.link URLs to try for a run, most likely first.
     *
     * nightly.link resolves an artifact *by name*: `.../runs/<id>/<name>.zip`.
     * Echo's CI used to upload a single artifact, but the current workflow uploads
     * several explicitly named ones (e.g. `echo-android-apk`), so the old hardcoded
     * `artifact.zip` now returns a 404 HTML page instead of a zip.
     */
    private suspend fun nightlyArtifactUrls(
        runId: Long,
        githubRepo: String,
        client: OkHttpClient
    ): List<String> {
        val base = "https://nightly.link/$githubRepo/actions/runs/$runId"
        val discovered = runCatching {
            getAndroidArtifactName(runId, githubRepo, client)
        }.getOrNull()
        return buildList {
            discovered?.let { add("${base}/${Uri.encode(it, null)}.zip") }
            add("$base/echo-android-apk.zip")
            add("$base/artifact.zip")
        }.distinct()
    }

    /**
     * Asks GitHub which artifacts exist for a run and picks the Android APK one.
     */
    private suspend fun getAndroidArtifactName(
        runId: Long,
        githubRepo: String,
        client: OkHttpClient
    ) = runCatching {
        val url = "https://api.github.com/repos/$githubRepo/actions/runs/$runId/artifacts"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        val artifacts = response.body.string().toData<GithubArtifactsResponse>().getOrThrow()
            .artifacts.filterNot { it.expired }
        artifacts.firstOrNull {
            it.name.contains("android", ignoreCase = true) &&
                it.name.contains("apk", ignoreCase = true)
        }?.name
            ?: artifacts.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }?.name
            ?: artifacts.minByOrNull { it.name.length }?.name
            ?: throw Exception(
                "No usable artifact found in run $runId" +
                    " (available: ${artifacts.joinToString { it.name }})"
            )
    }.getOrElse {
        throw Exception("Failed to fetch artifact list for run $runId", it)
    }

    @Serializable
    data class GithubReleaseResponse(
        @SerialName("tag_name")
        val tagName: String,
        @SerialName("created_at")
        val createdAt: String,
        val assets: List<Asset>
    ) {
        @Serializable
        data class Asset(
            val name: String,
            @SerialName("browser_download_url")
            val browserDownloadUrl: String
        )
    }

    @Serializable
    data class GithubRunsResponse(
        @SerialName("workflow_runs")
        val workflowRuns: List<Run>
    ) {
        @Serializable
        data class Run(
            val id: Long,
            @SerialName("head_sha")
            val sha: String,
        )
    }

    @Serializable
    data class GithubArtifactsResponse(
        @SerialName("total_count")
        val totalCount: Int = 0,
        val artifacts: List<Artifact> = emptyList()
    ) {
        @Serializable
        data class Artifact(
            val name: String,
            @SerialName("size_in_bytes")
            val sizeInBytes: Long = 0,
            val expired: Boolean = false
        )
    }

    suspend fun downloadUpdate(
        context: Context,
        url: String,
        client: OkHttpClient
    ) = runIOCatching {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            val detail = response.body.string()
                .replace(Regex("<[^>]*>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(200)
            throw Exception(
                "Download failed: HTTP ${response.code}" +
                    if (detail.isNotEmpty()) " - $detail" else ""
            )
        }
        val file = context.getTempFile()
        response.body.byteStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        if (file.length() == 0L) throw Exception("Download failed: empty file from $url")
        file
    }

    private fun unzipApk(file: File): File {
        if (!isZipFile(file)) {
            throw Exception(
                "Update archive is corrupt: not a ZIP file (${file.length()} bytes). " +
                    "The download source probably returned an error page instead of the APK."
            )
        }
        return try {
            val zipFile = ZipFile(file)
            val apkFile = File.createTempFile("temp", ".apk", file.parentFile!!)
            zipFile.use { zip ->
                val apkEntry = zip.entries().asSequence().firstOrNull {
                    !it.isDirectory && it.name.endsWith(".apk")
                } ?: throw Exception("No APK file found in the zip")
                zip.getInputStream(apkEntry).use { input ->
                    apkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            apkFile
        } catch (e: Exception) {
            throw Exception(
                "Failed to extract APK from update archive (${file.length()} bytes)", e
            )
        }
    }

    private fun isZipFile(file: File): Boolean {
        if (file.length() < 2) return false
        return file.inputStream().use { input ->
            val header = ByteArray(2)
            input.read(header) == 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        }
    }

    suspend fun getUpdateFileUrl(
        currentVersion: String,
        updateUrl: String,
        client: OkHttpClient
    ) = runIOCatching {
        if (updateUrl.isEmpty()) return@runIOCatching null
        if (updateUrl.startsWith("https://api.github.com/repos/")) {
            getGithubUpdateUrl(currentVersion, updateUrl, client)
        } else {
            throw Exception("Unsupported update url")
        }
    }

    private suspend fun <T> runIOCatching(
        block: suspend () -> T
    ) = withContext(Dispatchers.IO) {
        runCatching { runCatching { block() }.getOrElse { throw UpdateException(it) } }
    }

    class UpdateException(override val cause: Throwable) : Exception(cause) {
        override val message: String
            get() = "Update failed: ${cause.message}"
    }
}
