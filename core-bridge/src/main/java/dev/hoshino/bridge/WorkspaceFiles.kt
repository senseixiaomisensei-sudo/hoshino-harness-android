package dev.hoshino.bridge

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class WorkspaceEntry(val path:String,val name:String,val directory:Boolean,val bytes:Long,val modified:Long)
data class FilePage(val text:String,val version:String,val totalBytes:Long,val startLine:Int,val eof:Boolean)
data class TrashEntry(val id:String,val originalPath:String,val deletedAt:Long)

/** App-private workspace backend. No access to credential storage, no symlink following on mutations. */
class WorkspaceFiles(private val root:File,private val trash:File) {
    init {root.mkdirs();trash.mkdirs()}
    fun resolve(path:String):File {
        require(!path.contains('\u0000')) {"文件路径无效"}
        require(!path.startsWith('/') || path=="/root/workspace" || path.startsWith("/root/workspace/") || path=="/workspace" || path.startsWith("/workspace/")){"只允许私有工作区路径"}
        val relative=path.removePrefix("/root/workspace").removePrefix("/workspace").trimStart('/')
        require(!File(relative).isAbsolute) {"只允许私有工作区路径"}
        val rootPath=root.toPath().toAbsolutePath().normalize()
        val requested=rootPath.resolve(relative).normalize()
        require(requested.startsWith(rootPath)){"路径越出工作区"}
        var ancestor=requested
        while(ancestor!=rootPath){require(!Files.isSymbolicLink(ancestor) || (ancestor==requested && isProotHardLink(ancestor))){"工作区文件操作不跟随符号链接"};ancestor=ancestor.parent ?: error("路径越界")}
        val target=File(root,relative).canonicalFile
        require(target.toPath().startsWith(root.canonicalFile.toPath())) {"路径或符号链接越出工作区"}
        // Keep the public filename: canonicalizing a PRoot hard link exposes its backing file.
        return if(Files.isSymbolicLink(requested))root.canonicalFile.toPath().resolve(rootPath.relativize(requested)).toFile() else target
    }
    /** Accept only PRoot's two-hop regular-file hard links contained in this workspace. */
    private fun isProotHardLink(path:Path):Boolean = runCatching {
        val intermediate=path.parent.resolve(Files.readSymbolicLink(path)).normalize()
        val boundary=root.toPath().toAbsolutePath().normalize()
        if(!intermediate.startsWith(boundary) || !intermediate.fileName.toString().matches(Regex("\\.(?:proot\\.)?l2s\\..+[0-9]{4}")) || !Files.isSymbolicLink(intermediate))return@runCatching false
        var parent=intermediate.parent
        while(parent!=boundary){if(Files.isSymbolicLink(parent))return@runCatching false;parent=parent.parent ?: return@runCatching false}
        val backing=intermediate.parent.resolve(Files.readSymbolicLink(intermediate)).normalize()
        backing.parent==intermediate.parent && backing.fileName.toString().matches(Regex(Regex.escape(intermediate.fileName.toString())+"\\.[0-9]{4,}")) && Files.isRegularFile(backing,NOFOLLOW_LINKS)
    }.getOrDefault(false)
    fun publicPath(file:File)="/root/workspace"+file.relativeTo(root.canonicalFile).invariantSeparatorsPath.let {if(it.isEmpty())"" else "/$it"}
    fun list(path:String=""):List<WorkspaceEntry> = resolve(path).listFiles().orEmpty().mapNotNull { file ->
        runCatching {val safe=resolve(publicPath(file.absoluteFile));WorkspaceEntry(publicPath(safe),file.name,safe.isDirectory,if(safe.isFile)safe.length() else 0,safe.lastModified())}.getOrNull()
    }.sortedWith(compareByDescending<WorkspaceEntry> {it.directory}.thenBy {it.name.lowercase()})
    fun version(file:File):String = if(!file.exists())"absent" else MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        digest.digest().joinToString(""){"%02x".format(it)}
    }
    fun read(path:String,startLine:Int=1,lines:Int=1000):FilePage {
        require(startLine>=1 && lines in 1..5000)
        val file=resolve(path);check(file.isFile){"不是可读文件"}
        val before=version(file);val text=StringBuilder();var number=1;var eof=true
        file.bufferedReader(Charsets.UTF_8).use {reader ->
            while(true) {
                val line=reader.readLine() ?: break
                require(!line.contains('\u0000')) {"二进制文件，请使用预览或导出"}
                if(number>=startLine) {
                    if(number>=startLine+lines || text.length+line.length>2*1024*1024){eof=false;break}
                    if(text.isNotEmpty())text.append('\n');text.append(line)
                }
                number++
            }
        }
        check(version(file)==before){"文件已在读取时改变，请刷新"}
        return FilePage(text.toString(),before,file.length(),startLine,eof)
    }
    /** Full UTF-8 content for editable files only; preserve exact final newline. */
    fun editable(path:String):FilePage {
        val file=resolve(path);check(file.isFile);require(file.length()<=2*1024*1024){"超过 2 MB，只读分页"}
        val before=version(file);val content=file.readText();require(!content.contains('\u0000')){"二进制文件"};check(version(file)==before){"文件已改变"}
        return FilePage(content,before,file.length(),1,true)
    }
    @Synchronized fun write(path:String,content:String,expected:String) {
        require(content.toByteArray().size<=2*1024*1024){"超过 2 MB"}
        val file=resolve(path);require(file!=root.canonicalFile);check(version(file)==expected){"外部修改冲突：文件已改变，请重新读取或保留草稿"}
        check(file.parentFile!!.isDirectory){"父目录不存在"}
        val temp=File(file.parentFile,".hoshino-${UUID.randomUUID()}.tmp")
        try {temp.writeText(content);check(version(file)==expected){"外部修改冲突"};Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)}finally{temp.delete()}
    }
    fun createDirectory(path:String) {val file=resolve(path);check(!file.exists());check(file.mkdir()){"无法新建文件夹"}}
    private fun noLinks(file:File) {
        Files.walk(file.toPath()).use {paths->paths.forEach {path->require(!Files.isSymbolicLink(path)){"含符号链接的树不能复制或移动"}}}
    }
    @Synchronized fun transfer(path:String,destination:String,move:Boolean) {
        val source=resolve(path);val target=resolve(destination)
        require(source!=root.canonicalFile && source.exists() && !target.exists() && !target.toPath().startsWith(source.toPath()))
        require(target.parentFile!!.isDirectory);noLinks(source)
        if(move)Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
        else check(source.copyRecursively(target,overwrite=false))
    }
    @Synchronized fun delete(path:String):TrashEntry {
        val source=resolve(path);require(source!=root.canonicalFile && source.exists());noLinks(source)
        val id=UUID.randomUUID().toString();val dir=File(trash,id).apply{mkdir()};val now=System.currentTimeMillis()
        File(dir,"path").writeText(publicPath(source));File(dir,"time").writeText(now.toString())
        Files.move(source.toPath(),File(dir,"data").toPath(),StandardCopyOption.ATOMIC_MOVE)
        return TrashEntry(id,publicPath(source),now)
    }
    fun trashList():List<TrashEntry> = trash.listFiles().orEmpty().mapNotNull {dir->runCatching {
        require(dir.name.matches(Regex("[a-f0-9-]{36}")) && dir.canonicalFile.parentFile==trash.canonicalFile)
        TrashEntry(dir.name,File(dir,"path").readText(),File(dir,"time").readText().toLong())
    }.getOrNull()}.sortedByDescending{it.deletedAt}
    @Synchronized fun restore(id:String) {
        val entry=trashList().first{it.id==id};val target=resolve(entry.originalPath);check(!target.exists()){"原位置已有文件，不能覆盖"}
        check(target.parentFile!!.isDirectory);val dir=File(trash,entry.id)
        Files.move(File(dir,"data").toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);dir.deleteRecursively()
    }
    fun cleanTrash(now:Long=System.currentTimeMillis()) {
        for(entry in trashList())if(now-entry.deletedAt>7L*24*60*60*1000)File(trash,entry.id).deleteRecursively()
    }
    fun search(query:String,content:Boolean=false):List<String> {
        require(query.isNotEmpty());val found=mutableListOf<String>()
        Files.walk(root.toPath()).use{paths->paths.filter{!Files.isSymbolicLink(it) && Files.isRegularFile(it,NOFOLLOW_LINKS)}.forEach {path->
            if(found.size<200) {
                val file=resolve(publicPath(path.toFile()));val matches=if(content && file.length()<=2*1024*1024)runCatching{file.useLines{lines->lines.any{it.contains(query,ignoreCase=true)}}}.getOrDefault(false) else file.name.contains(query,ignoreCase=true)
                if(matches)found+=publicPath(file)
            }
        }}
        return found
    }
}
