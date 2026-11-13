package dev.brahmkshatriya.echo.ui.extensions.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.exceptions.InvalidExtensionListException
import dev.brahmkshatriya.echo.extensions.repo.ExtensionVerifier
import dev.brahmkshatriya.echo.ui.extensions.ExtensionsViewModel
import dev.brahmkshatriya.echo.utils.AppUpdater.downloadUpdate
import dev.brahmkshatriya.echo.utils.AppUpdater.getUpdateFileUrl
import dev.brahmkshatriya.echo.utils.Serializer.toData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class AddViewModel(
    private val app: App,
    private val extensionLoader: ExtensionLoader
) : ViewModel() {

    fun getList() = (addingFlow.value as? AddState.AddList)?.list.orEmpty()

    fun selectAll(select: Boolean) {
        val list = getList()
        addingFlow.value = AddState.AddList(list.map { it.copy(isChecked = select) })
    }

    fun toggleItem(item: ExtensionAssetResponse, isChecked: Boolean) {
        val list = getList()
        val index = list.indexOfFirst { it.item.id == item.id }
        if (index == -1) return
        val currentItem = list[index]
        addingFlow.value = AddState.AddList(list.toMutableList().apply {
            set(index, currentItem.copy(isChecked = isChecked))
        })
    }

    private val client = OkHttpClient()
    private suspend fun getExtensionList(
        link: String,
        client: OkHttpClient
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .addHeader("Cookie", "preview=1")
                .url(link).build()
            client.newCall(request).await().use { response ->
                // A redirect to a cleartext URL must not be allowed to carry the
                // extension list: it controls every updateUrl we then fetch.
                val scheme = response.request.url.scheme
                if (!scheme.equals("https", ignoreCase = true)) {
                    throw InvalidExtensionListException(
                        link,
                        Exception(
                            "Refusing to load an extension list over insecure " +
                                "$scheme:// (${response.request.url})"
                        )
                    )
                }
                response.body.string()
                    .toData<List<ExtensionAssetResponse>>().getOrThrow()
            }
        }.getOrElse {
            throw InvalidExtensionListException(link, it)
        }
    }

    @Serializable
    data class ExtensionAssetResponse(
        val id: String,
        val name: String,
        val subtitle: String? = null,
        val iconUrl: String? = null,
        val updateUrl: String,
        val sha256: String? = null
    )

    sealed class AddState {
        data object Init : AddState()
        data object Loading : AddState()
        data class AddList(val list: List<ExtensionsAddListAdapter.Item>?) : AddState()
        data class Downloading(val item: ExtensionAssetResponse) : AddState()
        data class Final(val files: List<File>) : AddState()
    }

    var opened = false
    val addingFlow = MutableStateFlow<AddState>(AddState.Init)
    fun addFromLinkOrCode(link: String) = viewModelScope.launch {
        addingFlow.value = AddState.Loading
        val actualLink = when {
            link.startsWith("https://") -> link
            link.startsWith("http://") -> {
                app.throwFlow.emit(
                    IllegalArgumentException(
                        "Insecure http:// extension links are not allowed, use https://"
                    )
                )
                addingFlow.value = AddState.Init
                return@launch
            }

            else -> "https://v.gd/$link"
        }

        val list = runCatching { getExtensionList(actualLink, client) }.getOrElse {
            app.throwFlow.emit(it)
            null
        }
        val installed = extensionLoader.all.value.map { it.id }
        val shouldBeChecked = (list?.size ?: 0) <= 3
        addingFlow.value = AddState.AddList(list?.map {
            val isInstalled = it.id in installed
            ExtensionsAddListAdapter.Item(
                it,
                isChecked = shouldBeChecked && !isInstalled,
                isInstalled = isInstalled
            )
        })
    }

    fun download(
        download: Boolean, extensionsViewModel: ExtensionsViewModel
    ) = viewModelScope.launch {
        val selected =
            if (download) getList().filter { it.isChecked }.map { it.item } else listOf()
        val files = selected.mapNotNull { item ->
            addingFlow.value = AddState.Downloading(item)
            val url = getUpdateFileUrl("", item.updateUrl, client).getOrElse {
                app.throwFlow.emit(it)
                null
            } ?: return@mapNotNull null
            val file = downloadUpdate(app.context, url, client).getOrElse {
                app.throwFlow.emit(it)
                null
            } ?: return@mapNotNull null
            val sha256 = item.sha256
            if (sha256?.isNotBlank() == true) {
                val verified = runCatching {
                    ExtensionVerifier(app.context).verify(file, item.id, sha256)
                }
                if (verified.isFailure) {
                    app.throwFlow.emit(
                        verified.exceptionOrNull()
                            ?: Exception("Extension integrity check failed")
                    )
                    return@mapNotNull null
                }
            }
            file
        }
        extensionsViewModel.installWithPrompt(files)
        addingFlow.value = AddState.Final(files)
    }
}