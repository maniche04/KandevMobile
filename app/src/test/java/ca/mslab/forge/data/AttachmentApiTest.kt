package ca.mslab.forge.data

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AttachmentApiTest {
    private lateinit var server:MockWebServer
    private val root=Files.createTempDirectory("forge-upload-test").toFile()
    @Before fun start(){server=MockWebServer();server.start()}
    @After fun stop(){server.shutdown();root.deleteRecursively()}
    private fun response(id:String,name:String="note.txt",size:Long=5)=MockResponse().setBody("""{"attachment_id":"$id","name":"$name","mime_type":"text/plain","kind":"resource","delivery_mode":"path","size_bytes":$size,"state":"staged","expires_at":"2099-01-01T00:00:00Z"}""")
    @Test fun `upload streams multipart metadata and bytes with a header credential`()=runBlocking {
        val file=root.resolve("owned").apply{writeText("hello")}
        server.enqueue(response("uploaded"))
        val local=LocalAttachment("id","note.txt","text/plain",5,"path")
        val uploaded=KandevApi(server.url("/").toString(),"test-token").uploadAttachment("workspace",local,file)
        assertEquals("uploaded",uploaded.id)
        val request=server.takeRequest(1,TimeUnit.SECONDS)!!;val body=request.body.readUtf8()
        assertEquals("/api/v1/attachments",request.path);assertEquals("POST",request.method)
        assertEquals("Bearer test-token",request.getHeader("Authorization"))
        for(part in listOf("name=\"workspace_id\"","workspace","name=\"delivery_mode\"","path","filename=\"note.txt\"","hello"))assertTrue(body.contains(part))
    }
    @Test fun `uncertain upload is not automatically replayed`()=runBlocking {
        val file=root.resolve("owned").apply{writeText("hello")}
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(runCatching{KandevApi(server.url("/").toString()).uploadAttachment("w",LocalAttachment("id","note.txt","text/plain",5,"path"),file)}.isFailure)
        assertEquals(1,server.requestCount)
    }
    @Test fun `authenticated content download writes exact bytes and rejects redirects`()=runBlocking {
        server.enqueue(MockResponse().setBody("file bytes").setHeader("Content-Type","text/plain"))
        val api=KandevApi(server.url("/").toString(),"test-token");val output=root.resolve("download")
        api.downloadAttachment("a/1",output);assertEquals("file bytes",output.readText())
        val request=server.takeRequest();assertEquals("/api/v1/attachments/a%2F1/content",request.path);assertEquals("Bearer test-token",request.getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://other.invalid/content"))
        assertTrue(runCatching{api.downloadAttachment("redirect",root.resolve("other"))}.isFailure)
        assertEquals(2,server.requestCount);assertFalse(root.resolve("other").exists())
    }
    @Test fun `interrupted download never publishes a partial destination`()=runBlocking {
        server.enqueue(MockResponse().setBody("abcdefghij").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val destination=root.resolve("partial")
        assertTrue(runCatching{KandevApi(server.url("/").toString()).downloadAttachment("broken",destination)}.isFailure)
        assertFalse(destination.exists());assertFalse(root.listFiles().orEmpty().any{it.extension=="tmp"})
    }
    @Test fun `restoration never uploads and partial upload retry reuses acknowledged IDs`()=runBlocking {
        val child=CoroutineScope(coroutineContext+SupervisorJob())
        val snapshots=SnapshotStore(root.resolve("snapshots"),"p");val bytes=AttachmentStore(root.resolve("files"),"p")
        val api=KandevApi(server.url("/").toString())
        fun model()=AttachmentController(child,{snapshots},{bytes},{api},{"w"},{root.resolve("previews")},{true})
        try {
            val first=model();first.selectSession("s");first.add(listOf(AttachmentSource("one.txt","text/plain"){"hello".byteInputStream()},AttachmentSource("two.txt","text/plain"){"world".byteInputStream()}))
            withTimeout(5000){while(first.importing)delay(5)};assertEquals(2,first.files.size);assertEquals(0,server.requestCount)
            server.enqueue(response("first","one.txt"));server.enqueue(MockResponse().setResponseCode(500))
            assertTrue(runCatching{first.prepareForSend()}.isFailure);assertEquals("first",first.files[0].uploaded?.id);assertNull(first.files[1].uploaded)
            val restored=model();restored.selectSession("s");restored.restore("s");assertEquals(2,server.requestCount)
            server.enqueue(response("second","two.txt"))
            val sent=restored.prepareForSend();assertEquals(listOf("first","second"),sent.map{it.attachmentId});assertEquals(3,server.requestCount)
            restored.acknowledged(sent);assertTrue(restored.files.isEmpty())
            assertTrue(snapshots.read("attachments:s",AttachmentDraft.serializer())!!.value.files.isEmpty())
            assertEquals(0,root.resolve("files").walkTopDown().filter{it.isFile}.count())
        } finally {child.cancel()}
    }
}
