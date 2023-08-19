package info.nightscout.sdk

import android.content.Context
import info.nightscout.androidaps.TestBase
import info.nightscout.rx.logging.LTag
import info.nightscout.sdk.localmodel.heartrate.NSHeartRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mock
import org.mockito.Mockito.`when`
import java.io.File
import java.net.HttpURLConnection

class NSAndroidClientImplTest: TestBase() {
    private val webServer = MockWebServer()
    private lateinit var client: NSAndroidClientImpl
    @Mock private lateinit var context: Context

    @BeforeEach
    fun setup(@TempDir tempDir: File) {
        webServer.start()
        `when`(context.cacheDir).thenReturn(tempDir)
        client = NSAndroidClientImpl(
            webServer.url("").toString(),
            "access123",
            context,
            true,
            logger = { msg -> aapsLogger.debug(LTag.HTTP, msg) },
            Dispatchers.Default,
            retries = 0)
    }

    @AfterEach
    fun teardown() {
        webServer.shutdown()
    }

    @Test
    fun getApiStatus() {
        webServer.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("/api/v3/status", request.path)
                assertEquals("GET", request.method)
                assertEquals("", request.body.readUtf8())
                return MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody(
                        """{"status":200,"result":{"version":"14.2.6","apiVersion":"3.0.3-alpha","srvDate":1692198698177,"storage":{"storage":"mongodb","version":"6.0.8"},"apiPermissions":{"devicestatus":"crud","entries":"crud","food":"crud","profile":"crud","settings":"crud","treatments":"crud"}}}""")
            }
        }
        val status = runBlocking { client.getStatus() }
        assertEquals("14.2.6", status.version)
        assertTrue(status.apiPermissions.food.full)
        assertFalse(status.apiPermissions.heartRate.full)
    }

    @Test
    fun getApiStatus_heartRateSupport() {
        webServer.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("/api/v3/status", request.path)
                assertEquals("GET", request.method)
                assertEquals("", request.body.readUtf8())
                return MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody(
                        """{"status":200,"result":{"version":"14.2.6","apiVersion":"3.0.3-alpha","srvDate":1692198698177,"storage":{"storage":"mongodb","version":"6.0.8"},
                            |"apiPermissions":{"devicestatus":"crud","entries":"crud","food":"crud","profile":"crud","settings":"crud","treatments":"crud", "heartrate":"crud"}}}""".trimMargin())
            }
        }
        val status = runBlocking { client.getStatus() }
        assertEquals("14.2.6", status.version)
        assertTrue(status.apiPermissions.food.full)
        assertTrue(status.apiPermissions.heartRate.full)
    }

    @Test
    fun getLastModified() {
        webServer.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("/api/v3/lastModified", request.path)
                assertEquals("GET", request.method)
                assertEquals("", request.body.readUtf8())
                return MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody(
                        """{"status":200,"result":{"srvDate":1692200625533,"collections":{"devicestatus":1692200436245,"entries":1692200421180,"heartrate":1584724892900,"profile":1679307405880,"treatments":1692200161750}}}""")
            }
        }
        val lastModified = runBlocking { client.getLastModified() }
        assertEquals(1692200436245L, lastModified.collections.devicestatus)
        assertEquals(1584724892900L, lastModified.collections.heartRate)
    }

    @Test
    fun getHeartRatesModifiedSince() {
        webServer.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("/api/v3/heartrate/history/1111?limit=12", request.path)
                assertEquals("GET", request.method)
                assertEquals("", request.body.readUtf8())
                return MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody(
                        """{"result":[{"created_at":"1970-01-01T00:00:01Z","device":"foo",""" +
                        """"utcOffset":0,"isValid":true,"app":"AAPS","date":1001,""" +
                        """"duration":60000,"beatsPerMinute":99.9}]}""")
            }
        }
        val hrs = runBlocking { client.getHeartRatesModifiedSince(1111, limit = 12) }
        val nsHr = NSHeartRate(
            createdAt = "1970-01-01T00:00:01Z", device = "foo", identifier = null,
            utcOffset = 0L, isValid = true, date = 1001L, duration = 60_000, beatsPerMinute = 99.9)
        assertEquals(1, hrs.size)
        assertEquals(nsHr, hrs[0])
    }

    @Test
    fun createHeartRate_success() {
        val nsHr = NSHeartRate(
            createdAt = "1970-01-01T00:00:01Z", device = "foo", identifier = null,
            utcOffset = 0L, isValid = true, date = 1001L, duration = 60_000, beatsPerMinute = 99.9)
        webServer.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("/api/v3/heartrate", request.path)
                assertEquals("POST", request.method)
                assertEquals(
                    """{"created_at":"1970-01-01T00:00:01Z","device":"foo",""" +
                        """"utcOffset":0,"isValid":true,"app":"AAPS","date":1001,""" +
                        """"duration":60000,"beatsPerMinute":99.9}""",
                    request.body.readUtf8())
                return MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_CREATED)
                    .setBody("""{"identifier": "id1", "idDeduplication": false }""")
            }
        }
        val resp = runBlocking { client.createHeartRate(nsHr) }
        assertEquals(HttpURLConnection.HTTP_CREATED, resp.response)
        assertEquals("id1", resp.identifier)
        assertNull(resp.errorResponse)
        assertFalse(resp.isDeduplication == false)
    }

    @Test
    fun createHeartRate_fail() {
        val nsHr = NSHeartRate(
            createdAt = "1970-01-01T00:00:01Z", device = "foo", identifier = null,
            utcOffset = 0L, isValid = true, date = 1001L, duration = 60_000, beatsPerMinute = 99.9)
        webServer.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("/api/v3/heartrate", request.path)
                assertEquals("POST", request.method)
                assertEquals(
                    """{"created_at":"1970-01-01T00:00:01Z","device":"foo",""" +
                        """"utcOffset":0,"isValid":true,"app":"AAPS","date":1001,"duration":60000,""" +
                        """"beatsPerMinute":99.9}""",
                    request.body.readUtf8())
                return MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_BAD_REQUEST)
                    .setBody("{}")
            }
        }
        val resp = runBlocking { client.createHeartRate(nsHr) }
        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, resp.response)
        assertEquals("Client Error/400 {}", resp.errorResponse)
    }

    @Test
    fun updateHeartRate_success() {
        val nsHr = NSHeartRate(
            createdAt = "1970-01-01T00:00:01Z", device = "foo", identifier = "id1",
            utcOffset = 0L, isValid = true, date = 1001L, duration = 60_000, beatsPerMinute = 99.9)
        webServer.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("/api/v3/heartrate/id1", request.path)
                assertEquals("PATCH", request.method)
                assertEquals(
                    // """{"created_at":"1970-01-01T00:00:01Z","device":"foo","identifier":"id1","utcOffset":0,"isValid":true,"date":1001,"duration":60000,"beatsPerMinute":99.9}""",
                    """{"created_at":"1970-01-01T00:00:01Z","device":"foo",""" +
                        """"identifier":"id1","utcOffset":0,"isValid":true,"date":1001,""" +
                        """"duration":60000,"beatsPerMinute":99.9}""",
                    request.body.readUtf8())
                return MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("""{}""")
            }
        }
        val resp = runBlocking { client.updateHeartRate(nsHr) }
        assertEquals(HttpURLConnection.HTTP_OK, resp.response)
        assertNull(resp.errorResponse)
        assertEquals(false,resp.isDeduplication)
    }

    @Test
    fun updateHeartRate_delete() {
        val nsHr = NSHeartRate(
            createdAt = "1970-01-01T00:00:01Z", device = "foo", identifier = "id1",
            utcOffset = 0L, isValid = false, date = 1001L, duration = 60_000, beatsPerMinute = 99.9)
        webServer.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("/api/v3/heartrate/id1", request.path)
                assertEquals("DELETE", request.method)
                assertEquals("", request.body.readUtf8())
                return MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody("""{}""")
            }
        }
        val resp = runBlocking { client.updateHeartRate(nsHr) }
        assertEquals(HttpURLConnection.HTTP_OK, resp.response)
        assertNull(resp.errorResponse)
        assertEquals(false,resp.isDeduplication)
    }

    @Test
    fun updateHeartRate_fail() {
        val nsHr = NSHeartRate(
            createdAt = "1970-01-01T00:00:01Z", device = "foo", identifier = "id1",
            utcOffset = 0L, isValid = true, date = 1001L, duration = 60_000, beatsPerMinute = 99.9)
        webServer.dispatcher = object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("/api/v3/heartrate/id1", request.path)
                assertEquals("PATCH", request.method)
                assertEquals(
                    """{"created_at":"1970-01-01T00:00:01Z","device":"foo",""" +
                        """"identifier":"id1","utcOffset":0,"isValid":true,"date":1001,""" +
                        """"duration":60000,"beatsPerMinute":99.9}""",
                    request.body.readUtf8())
                return MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_BAD_REQUEST)
                    .setBody("""{}""")
            }
        }
        val resp = runBlocking { client.updateHeartRate(nsHr) }
        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, resp.response)
        assertEquals("Client Error/400 {}", resp.errorResponse)
        assertEquals(false,resp.isDeduplication)
    }
}