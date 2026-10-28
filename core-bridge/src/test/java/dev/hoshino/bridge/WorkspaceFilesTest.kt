package dev.hoshino.bridge

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceFilesTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun store():WorkspaceFiles=WorkspaceFiles(temporary.newFolder("workspace"),temporary.newFolder("trash"))
    @Test fun pathsAndStaleWritesAreRefused() {
        val store=store();store.write("hello.txt","one\n","absent");val original=store.editable("hello.txt")
        assertEquals("one\n",original.text)
        store.write("hello.txt","two",original.version)
        assertThrows(IllegalStateException::class.java){store.write("hello.txt","lost",original.version)}
        assertEquals("two",store.editable("hello.txt").text)
        for(path in listOf("../outside","/etc/passwd","/root/workspace-other/secret","a/../../outside"))assertThrows(IllegalArgumentException::class.java){store.resolve(path)}
    }
    @Test fun trashRestoresExactBytesAndCannotOverwrite() {
        val store=store();store.write("hello.txt","exact\ncontent\n","absent");val entry=store.delete("hello.txt")
        assertFalse(store.resolve("hello.txt").exists());store.restore(entry.id)
        assertEquals("exact\ncontent\n",store.editable("hello.txt").text);assertTrue(store.trashList().isEmpty())
        val second=store.delete("hello.txt");store.write("hello.txt","new","absent")
        assertThrows(IllegalStateException::class.java){store.restore(second.id)}
    }
    @Test fun escapingSymlinksAreRefused() {
        val store=store();val outside=temporary.newFolder("outside");File(outside,"secret").writeText("private")
        val link=File(store.resolve(""),"link")
        try{Files.createSymbolicLink(link.toPath(),outside.toPath())}catch(e:java.nio.file.FileSystemException){org.junit.Assume.assumeNoException("Host cannot create test symlink",e)}
        assertThrows(IllegalArgumentException::class.java){store.resolve("link/secret")}
    }
    @Test fun prootHardLinksKeepTheirPublicNameAndRejectEscapes() {
        val store=store();val root=store.resolve("").toPath()
        val staging=Files.createDirectory(root.resolve(".hello.txt.123.fixture.tmpdir"))
        val intermediate=staging.resolve(".l2s.hello.tmp0001");val backing=staging.resolve(".l2s.hello.tmp0001.0001")
        Files.writeString(backing,"from kernel\n")
        try {Files.createSymbolicLink(intermediate,backing);Files.createSymbolicLink(root.resolve("hello.txt"),intermediate)}catch(e:java.nio.file.FileSystemException){org.junit.Assume.assumeNoException("Host cannot create test symlink",e)}
        assertEquals("from kernel\n",store.editable("hello.txt").text)
        assertEquals("/root/workspace/hello.txt",store.publicPath(store.resolve("hello.txt")))
        assertTrue(store.list().any{it.name=="hello.txt" && it.path=="/root/workspace/hello.txt"})
        val outside=temporary.newFile("secret").toPath();Files.delete(intermediate);Files.createSymbolicLink(intermediate,outside)
        assertThrows(IllegalArgumentException::class.java){store.resolve("hello.txt")}
    }
    @Test fun largeFilesArePagedAndNeverSilentlyEditable() {
        val store=store();val large=store.resolve("large.txt");large.bufferedWriter().use{out->repeat(25000){out.write("line $it "+"x".repeat(100)+"\n")}}
        assertThrows(IllegalArgumentException::class.java){store.editable("large.txt")}
        val page=store.read("large.txt",501,500);assertTrue(page.text.startsWith("line 500 "));assertEquals(500,page.text.lines().size);assertFalse(page.eof)
    }
    @Test fun prootHardLinksAlsoWorkThroughAndroidStyleRootAliases() {
        val actual=temporary.newFolder("actual").toPath();val alias=temporary.root.toPath().resolve("alias")
        val intermediate=actual.resolve(".l2s.hello0001");val backing=actual.resolve(".l2s.hello0001.0001");Files.writeString(backing,"kernel")
        try {Files.createSymbolicLink(alias,actual);Files.createSymbolicLink(intermediate,backing);Files.createSymbolicLink(actual.resolve("hello.txt"),intermediate)}catch(e:java.nio.file.FileSystemException){org.junit.Assume.assumeNoException("Host cannot create test symlink",e)}
        val store=WorkspaceFiles(alias.toFile(),temporary.newFolder("trash"))
        assertEquals("kernel",store.editable("hello.txt").text)
        assertEquals("/root/workspace/hello.txt",store.publicPath(store.resolve("hello.txt")))
    }
}
