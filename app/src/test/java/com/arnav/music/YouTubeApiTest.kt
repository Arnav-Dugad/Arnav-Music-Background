package com.arnav.music

import com.arnav.music.core.youtube.MetadataEnricher
import com.arnav.music.core.youtube.YouTubeApi
import com.arnav.music.domain.provider.MusicError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class YouTubeApiTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun api(key: String? = "test-key"): YouTubeApi {
        // Route every request to the mock server regardless of host.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            val url = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(original.newBuilder().url(url).build())
        }.build()
        return YouTubeApi(client, json, { key }, "com.arnav.music", { "AA:BB" })
    }

    @Test fun `parses search results and sends key and android headers`() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":{"kind":"youtube#video","videoId":"abcdefghijk"},"snippet":{"title":"Song","channelTitle":"Artist - Topic"}}],"nextPageToken":"N"}"""))
        val r = api().search("daft punk", "video", null)
        assertEquals("abcdefghijk", r.items.first().id.videoId)
        assertEquals("N", r.nextPageToken)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("key=test-key"))
        assertTrue(req.path!!.contains("videoCategoryId=10"))
        assertEquals("com.arnav.music", req.getHeader("X-Android-Package"))
    }

    @Test fun `maps quotaExceeded to QuotaExhausted`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}]}}"""))
        try { api().search("x y", "video", null); fail() } catch (e: MusicError) { assertEquals(MusicError.QuotaExhausted, e) }
    }

    @Test fun `missing key fails fast without a network call`() = runTest {
        try { api(key = null).videos(listOf("a")); fail() } catch (e: MusicError) { assertEquals(MusicError.MissingApiKey, e) }
        assertEquals(0, server.requestCount)
    }

    @Test fun `server errors map to Http`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        try { api().chart("IN"); fail() } catch (e: MusicError) { assertEquals(MusicError.Http(500), e) }
    }

    @Test fun `metadata enricher only guesses with evidence`() {
        val g = MetadataEnricher.genres("Lofi beats to study to", listOf("chill", "lofi"))
        assertTrue("lofi" in g)
        val e = MetadataEnricher.energy("Lofi beats to study to", emptyList(), g)!!
        assertTrue(e < 0.4f)
        assertNull(MetadataEnricher.energy("Untitled", emptyList(), emptyList()))
        assertEquals(2019, MetadataEnricher.year("2019-05-01T00:00:00Z"))
    }
}
