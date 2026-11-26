package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList

/** Отвечает из `handler` и записывает, что ушло (аналог StubServer в iOS-тестах). */
class FakeTransport(var handler: (HttpRequest) -> HttpResponse = { json("""{"ok":true}""") }) : HttpTransport {
    val requests = CopyOnWriteArrayList<HttpRequest>()

    override suspend fun execute(request: HttpRequest, progress: TransferProgress?): HttpResponse {
        requests += request
        val r = handler(request)
        progress?.invoke(r.body.size.toLong(), r.body.size.toLong())
        return r
    }

    fun client(token: String? = null) = DeviceClient(DeviceEndpoint("10.0.0.2"), token, this)

    companion object {
        fun json(text: String, status: Int = 200) = HttpResponse(status, text.toByteArray(), "application/json")
        fun text(text: String, status: Int = 200) = HttpResponse(status, text.toByteArray(), "text/plain")
    }
}

fun HttpRequest.jsonBody(): JsonObject = Json.parseToJsonElement(body!!.toString(Charsets.UTF_8)) as JsonObject
