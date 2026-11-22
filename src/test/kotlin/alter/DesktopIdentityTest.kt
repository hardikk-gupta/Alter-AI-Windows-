package alter

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.URI
import java.net.http.*
import kotlin.test.*

class DesktopIdentityTest {
    @Test fun loopbackRequiresExactCallbackAndMatchingState() = runBlocking {
        DesktopOAuthReturn("expected", 0).use { listener ->
            val client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
            fun callback(suffix: String): Int = client.send(HttpRequest.newBuilder(URI(listener.redirect + suffix)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()
            assertEquals(400, callback("?state=wrong&code=a"))
            assertEquals(400, callback("/other?state=expected&code=a"))
            assertEquals(400, callback("?state=expected&code=a&code=b"))
            assertFalse(listener.code.isCompleted)
            assertEquals(200, callback("?state=expected&code=oauth%2Bcode"))
            assertEquals("oauth+code", listener.code.await())
        }
    }

    @Test fun providerDenialAndIdentityMismatchAreRejected() = runBlocking {
        DesktopOAuthReturn("expected", 0).use { listener ->
            HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build().send(
                HttpRequest.newBuilder(URI(listener.redirect + "?state=expected&error=access_denied")).build(),HttpResponse.BodyHandlers.discarding())
            assertFailsWith<IllegalStateException> { listener.code.await() }
        }
        val payload = JSONObject().put("localId","demo").put("email","demo@example.test").put("idToken","fixture").put("expiresIn","3600")
        val session = firebaseSession(payload, null)
        assertEquals("demo", firebaseSession(payload, session).uid)
        assertFailsWith<IllegalStateException> { firebaseSession(JSONObject(payload.toString()).put("localId", "other"), session) }
        assertFailsWith<IllegalStateException> { firebaseSession(JSONObject().put("needConfirmation", true), null) }
        assertFailsWith<IllegalArgumentException> { firebaseSession(payload.put("expiresIn",0), null) }
        Unit
    }
}
