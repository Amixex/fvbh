package com.amix.tutor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amix.tutor.data.ChatDao
import com.amix.tutor.data.ChatEntity
import com.amix.tutor.data.MessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(val text: String, val fromUser: Boolean)

/**
 * NEED_DOWNLOAD — модели нет ни в APK, ни на телефоне (запасной путь: скачать)
 * DOWNLOADING   — скачивание (запасной путь)
 * COPYING       — один раз после установки: вшитая модель копируется во внутреннюю память
 * LOADING       — модель запускается (несколько секунд при каждом старте)
 */
enum class AppStage { NEED_DOWNLOAD, DOWNLOADING, COPYING, LOADING, READY, ERROR }

enum class ErrorKind { NO_SPACE, NO_MEMORY, OTHER }

/** Ошибка без текста: текст на нужном языке подставляет экран. */
data class UiError(val kind: ErrorKind, val missingGb: Float = 0f, val detail: String = "")

@HiltViewModel
class ChatViewModel @Inject constructor(
    app: Application,
    private val dao: ChatDao
) : AndroidViewModel(app) {

    private val _stage = MutableStateFlow(initialStage(app))
    val stage: StateFlow<AppStage> = _stage.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<UiError?>(null)
    val error: StateFlow<UiError?> = _error.asStateFlow()

    private var engine: LlmEngine? = null
    private var working = false

    @Volatile
    private var chatId: Long? = null

    init {
        begin()
    }

    private fun initialStage(app: Application): AppStage = when {
        ModelDownloader.isDownloaded(app) -> AppStage.LOADING
        ModelDownloader.hasBundledModel(app) -> AppStage.COPYING
        else -> AppStage.NEED_DOWNLOAD
    }

    private fun begin() {
        val app = getApplication<Application>()
        when {
            ModelDownloader.isDownloaded(app) -> loadEngine()
            ModelDownloader.hasBundledModel(app) -> prepareBundled()
            else -> _stage.value = AppStage.NEED_DOWNLOAD
        }
    }

    /** Один раз после установки: копируем вшитую модель во внутреннюю память. */
    private fun prepareBundled() {
        if (working) return
        working = true
        _stage.value = AppStage.COPYING
        _progress.value = 0f
        _error.value = null
        viewModelScope.launch {
            try {
                ModelDownloader.copyFromAssets(getApplication()) { d, t ->
                    if (t > 0) _progress.value = (d.toFloat() / t.toFloat()).coerceIn(0f, 1f)
                }
                working = false
                loadEngine()
            } catch (t: Throwable) {
                working = false
                fail(t)
            }
        }
    }

    /** Запасной путь: скачивание, если модели нет в APK. */
    fun downloadModel() {
        if (working || _stage.value == AppStage.DOWNLOADING) return
        working = true
        _stage.value = AppStage.DOWNLOADING
        _progress.value = 0f
        _error.value = null
        viewModelScope.launch {
            try {
                ModelDownloader.download(getApplication()) { d, t ->
                    if (t > 0) _progress.value = (d.toFloat() / t.toFloat()).coerceIn(0f, 1f)
                }
                working = false
                loadEngine()
            } catch (t: Throwable) {
                working = false
                fail(t)
            }
        }
    }

    private fun loadEngine() {
        if (working) return
        working = true
        _stage.value = AppStage.LOADING
        viewModelScope.launch {
            try {
                val e = withContext(Dispatchers.IO) { LlmEngine.create(getApplication()) }
                engine = e
                _stage.value = AppStage.READY
            } catch (t: Throwable) {
                fail(t)
            } finally {
                working = false
            }
        }
    }

    fun retry() {
        val app = getApplication<Application>()
        _error.value = null
        when {
            ModelDownloader.isDownloaded(app) -> loadEngine()
            ModelDownloader.hasBundledModel(app) -> prepareBundled()
            else -> downloadModel()
        }
    }

    private fun fail(t: Throwable) {
        val msg = t.message.orEmpty()
        _error.value = when {
            t is ModelDownloader.NoSpaceException ->
                UiError(ErrorKind.NO_SPACE, missingGb = t.missingBytes / 1073741824f)
            t is OutOfMemoryError || msg.contains("memory", true) || msg.contains("alloc", true) ->
                UiError(ErrorKind.NO_MEMORY)
            else -> UiError(ErrorKind.OTHER, detail = msg)
        }
        _stage.value = AppStage.ERROR
    }

    // ---------- чат ----------

    fun send(text: String, systemBase: String, systemCreator: String) {
        val q = text.trim()
        if (q.isEmpty() || _busy.value || _stage.value != AppStage.READY) return
        val e = engine ?: return
        val history = _messages.value
        _messages.value = history + ChatMessage(q, fromUser = true)
        _busy.value = true
        var acc = ""
        val idx = _messages.value.size
        _messages.value = _messages.value + ChatMessage("", fromUser = false)
        saveMessage(q, fromUser = true, title = q)

        val prompt = buildPrompt(history, q, systemBase, systemCreator)
        e.generate(
            prompt = prompt,
            onToken = { chunk ->
                acc += chunk
                val list = _messages.value.toMutableList()
                if (idx < list.size) list[idx] = ChatMessage(acc, fromUser = false)
                _messages.value = list
            },
            onDone = { full ->
                if (full.isNotBlank()) saveMessage(full, fromUser = false, title = "")
                _busy.value = false
            },
            onError = {
                val list = _messages.value.toMutableList()
                if (idx < list.size && list[idx].text.isBlank()) list.removeAt(idx)
                _messages.value = list
                _busy.value = false
            }
        )
    }

    private fun saveMessage(text: String, fromUser: Boolean, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = chatId ?: dao.insertChat(
                ChatEntity(title = title.take(40), createdAt = now, updatedAt = now)
            ).also { chatId = it }
            dao.insertMessage(MessageEntity(chatId = id, text = text, fromUser = fromUser, createdAt = now))
            dao.touch(id, now)
        }
    }

    fun openChat(id: Long) {
        if (_busy.value) return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { dao.messagesOf(id) }
            chatId = id
            _messages.value = list.map { ChatMessage(it.text, it.fromUser) }
        }
    }

    fun newChat() {
        if (_busy.value) return
        chatId = null
        _messages.value = emptyList()
    }

    fun clearAllChats() {
        if (_busy.value) return
        chatId = null
        _messages.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { dao.clearAll() }
    }

    /**
     * Окно модели небольшое (1280 токенов на вопрос и ответ вместе), поэтому история
     * берётся по символьному бюджету, а блок про создателя добавляется только когда о нём спросили.
     */
    private fun buildPrompt(
        history: List<ChatMessage>,
        last: String,
        systemBase: String,
        systemCreator: String
    ): String {
        val question = last.take(600)
        val sys = if (CREATOR_RE.containsMatchIn(question)) systemBase + "\n" + systemCreator else systemBase
        var budget = PROMPT_CHAR_BUDGET - sys.length - question.length
        val picked = ArrayList<ChatMessage>()
        for (m in history.filter { it.text.isNotBlank() }.asReversed().take(6)) {
            val t = m.text.take(500)
            if (t.length > budget) break
            picked.add(0, ChatMessage(t, m.fromUser))
            budget -= t.length
        }
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(sys).append("<|im_end|>\n")
        for (m in picked) {
            val role = if (m.fromUser) "user" else "assistant"
            sb.append("<|im_start|>").append(role).append("\n").append(m.text).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(question).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    override fun onCleared() {
        super.onCleared()
        engine?.close()
    }

    private companion object {
        const val PROMPT_CHAR_BUDGET = 1800
        val CREATOR_RE = Regex(
            "(создател|разработчик|автор|кто тебя (сделал|создал|написал|разработал)|компани|creator|developer|author|who (made|built|created|developed) you)",
            RegexOption.IGNORE_CASE
        )
    }
}
