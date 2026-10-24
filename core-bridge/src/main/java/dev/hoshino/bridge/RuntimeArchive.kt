package dev.hoshino.bridge

import android.system.Os
import android.system.OsConstants
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** The launcher and compression benchmark use the identical filesystem extraction path. */
object RuntimeArchive {
    fun extract(input: InputStream, format: String, directory: File): String {
        directory.mkdirs()
        val base=directory.canonicalFile.toPath()
        val decoded=when(format) { "xz" -> XZCompressorInputStream(input); "gzip" -> GzipCompressorInputStream(input); else -> error("Unsupported runtime compression") }
        val digest=MessageDigest.getInstance("SHA-256")
        TarArchiveInputStream(DigestInputStream(decoded,digest)).use { tar ->
            val links=mutableListOf<Pair<File,String>>()
            while(true) {
                val entry=tar.nextEntry ?: break
                val dest=File(directory,entry.name).canonicalFile
                require(dest.toPath().startsWith(base)) { "Unsafe rootfs path" }
                when {
                    entry.isDirectory -> dest.mkdirs()
                    entry.isSymbolicLink -> links+=dest to entry.linkName
                    entry.isFile -> { dest.parentFile?.mkdirs();dest.outputStream().use { tar.copyTo(it) };Os.chmod(dest.path,entry.mode and 511) }
                    entry.isLink -> error("Rootfs must contain no hard links")
                }
            }
            links.forEach { (dest,target) -> dest.parentFile?.mkdirs();Os.symlink(target,dest.path) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Only generated immediate children of the explicitly supplied private parent are removed. */
    fun removeGenerated(directory: File, parent: File) {
        require(directory.parentFile!!.canonicalFile==parent.canonicalFile)
        fun remove(entry: File) {
            val mode=try {Os.lstat(entry.path).st_mode} catch(e: android.system.ErrnoException) {if(e.errno==OsConstants.ENOENT)return else throw e}
            if(OsConstants.S_ISDIR(mode)) {
                Os.chmod(entry.path,(mode and 511) or 0x1c0)
                (entry.listFiles() ?: error("Cannot enumerate generated runtime")).forEach(::remove)
            }
            check(entry.delete()) {"Cannot remove generated runtime"}
        }
        remove(directory)
    }
}
