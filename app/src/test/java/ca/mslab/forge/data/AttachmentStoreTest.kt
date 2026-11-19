package ca.mslab.forge.data

import java.nio.file.Files
import java.io.InputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AttachmentStoreTest {
    @Test fun `owned files survive recreation and stay isolated by profile`() = runBlocking {
        val root=Files.createTempDirectory("forge-owned-files").toFile()
        try {
            val one=AttachmentStore(root,"one",100)
            val value=one.stage("../report\n.txt","text/plain"){"private bytes".byteInputStream()}
            assertEquals("report.txt",value.name);assertEquals(13,value.size)
            assertEquals("private bytes",AttachmentStore(root,"one",100).file(value.id).readText())
            assertFalse(AttachmentStore(root,"two",100).file(value.id).exists())
            one.clear();assertFalse(one.file(value.id).exists())
            assertTrue(runCatching{one.stage("late","text/plain"){"late".byteInputStream()}}.isFailure)
        } finally {root.deleteRecursively()}
    }
    @Test fun `storage bounds actual streamed bytes and removes failed partial files`() = runBlocking {
        val root=Files.createTempDirectory("forge-file-limit").toFile()
        try {
            val store=AttachmentStore(root,"p",10)
            store.stage("first","text/plain"){"123456".byteInputStream()}
            assertTrue(runCatching{store.stage("too large","text/plain"){"12345".byteInputStream()}}.isFailure)
            assertEquals(6,root.walkTopDown().filter{it.isFile}.sumOf{it.length()})
            assertTrue(runCatching{store.file("../../private")}.isFailure)
        } finally {root.deleteRecursively()}
    }
    @Test fun `cancelled import cleans its bytes and releases storage`() = runBlocking {
        val root=Files.createTempDirectory("forge-file-cancel").toFile()
        try {
            val store=AttachmentStore(root,"p",1000000);val entered=CompletableDeferred<Unit>()
            val job=launch { store.stage("slow","application/octet-stream") {object:InputStream(){
                override fun read():Int=0
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {entered.complete(Unit);Thread.sleep(10);return minOf(1000,length)}
            }} }
            entered.await();job.cancelAndJoin()
            assertEquals(0,root.walkTopDown().filter{it.isFile}.sumOf{it.length()})
        } finally {root.deleteRecursively()}
    }
    @Test fun `attachment identities include mode and content while text-only retries keep their identity`() {
        val image=MessageAttachment("image","id","image/png","image.png",12,"prompt")
        assertEquals("draft",messageFingerprint("draft",emptyList()))
        assertEquals(messageFingerprint("draft",listOf(image)),messageFingerprint("draft",listOf(image)))
        assertNotEquals(messageFingerprint("draft",listOf(image)),messageFingerprint("draft",listOf(image.copy(deliveryMode="path"))))
        assertNotEquals(messageFingerprint("draft",listOf(image)),messageFingerprint("changed",listOf(image)))
        assertEquals("application/octet-stream",normalizeMime("bad\r\nmime"))
        assertEquals("image/png",normalizeMime("IMAGE/PNG; charset=utf-8"))
    }
}
