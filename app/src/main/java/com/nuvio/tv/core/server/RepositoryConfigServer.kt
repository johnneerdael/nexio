package com.nuvio.tv.core.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RepositoryConfigServer(
    private val currentRepositoriesProvider: () -> List<RepositoryInfo>,
    private val onChangeProposed: (PendingRepoChange) -> Unit,
    port: Int = 8090
) : NanoHTTPD(port) {

    data class RepositoryInfo(
        val url: String,
        val name: String,
        val description: String?
    )

    data class PendingRepoChange(
        val id: String = UUID.randomUUID().toString(),
        val proposedUrls: List<String>,
        var status: ChangeStatus = ChangeStatus.PENDING
    )

    enum class ChangeStatus { PENDING, CONFIRMED, REJECTED }

    private val gson = Gson()
    private val pendingChanges = ConcurrentHashMap<String, PendingRepoChange>()

    fun confirmChange(id: String) {
        pendingChanges[id]?.status = ChangeStatus.CONFIRMED
    }

    fun rejectChange(id: String) {
        pendingChanges[id]?.status = ChangeStatus.REJECTED
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            method == Method.GET && uri == "/" -> serveWebPage()
            method == Method.GET && uri == "/api/repositories" -> serveRepositoryList()
            method == Method.POST && uri == "/api/repositories" -> handleRepositoryUpdate(session)
            method == Method.GET && uri.startsWith("/api/status/") -> serveChangeStatus(uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html", RepositoryWebPage.getHtml())
    }

    private fun serveRepositoryList(): Response {
        val repos = currentRepositoriesProvider()
        val json = gson.toJson(repos)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun handleRepositoryUpdate(session: IHTTPSession): Response {
        val hasPending = pendingChanges.values.any { it.status == ChangeStatus.PENDING }
        if (hasPending) {
            val error = mapOf("error" to "A change is already pending confirmation on the TV")
            return newFixedLengthResponse(
                Response.Status.CONFLICT,
                "application/json",
                gson.toJson(error)
            )
        }

        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val body = bodyMap["postData"] ?: ""

        val urls: List<String> = try {
            val parsed = gson.fromJson<Map<String, Any>>(body, object : TypeToken<Map<String, Any>>() {}.type)
            @Suppress("UNCHECKED_CAST")
            (parsed["urls"] as? List<String>) ?: emptyList()
        } catch (e: Exception) {
            val error = mapOf("error" to "Invalid request body")
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                gson.toJson(error)
            )
        }

        val change = PendingRepoChange(proposedUrls = urls)
        pendingChanges[change.id] = change
        onChangeProposed(change)

        val response = mapOf("status" to "pending_confirmation", "id" to change.id)
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
    }

    private fun serveChangeStatus(uri: String): Response {
        val id = uri.removePrefix("/api/status/")
        val change = pendingChanges[id]
        val status = change?.status?.name?.lowercase() ?: "not_found"
        val response = mapOf("status" to status)
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
    }

    companion object {
        fun startOnAvailablePort(
            currentRepositoriesProvider: () -> List<RepositoryInfo>,
            onChangeProposed: (PendingRepoChange) -> Unit,
            startPort: Int = 8090,
            maxAttempts: Int = 10
        ): RepositoryConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = RepositoryConfigServer(currentRepositoriesProvider, onChangeProposed, port)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (e: Exception) {
                    // Port in use, try next
                }
            }
            return null
        }
    }
}
