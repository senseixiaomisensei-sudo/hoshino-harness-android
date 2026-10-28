package app.hoshino.harness.core

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.time.Instant

/** Read-only projection. Original dsh JSONL and SessionStore remain untouched. */
object OriginalSessionHistory {
    fun time(value:Any?):Long=when(value){is Number->value.toLong().takeIf{it>0}?:0L;is String->value.toLongOrNull()?.takeIf{it>0}?:runCatching{Instant.parse(value).toEpochMilli()}.getOrDefault(0L);else->0L}
    data class Entry(val id:String,val created:Long,val updated:Long,val title:String,val cwd:String,val events:List<JSONObject>)
    fun read(context:Context):List<Entry>{
        val root=File(context.filesDir,"dsh-home/sessions");if(!root.isDirectory)return emptyList()
        return root.walkTopDown().filter{it.isFile&&it.name.matches(Regex("session(?:\\.v[0-9]+)?\\.jsonl"))}.groupBy{it.parentFile}.values.mapNotNull{files->
            val file=files.maxByOrNull{Regex("\\.v([0-9]+)\\.").find(it.name)?.groupValues?.get(1)?.toIntOrNull()?:0}?:return@mapNotNull null
            runCatching{file.bufferedReader().use{reader->
                val header=JSONObject(reader.readLine()?:return@use null);val id=header.optString("id").takeIf{it.isNotBlank()}?:return@use null
                var updated=0L;var title="会话 "+id.take(8);val events=ArrayDeque<JSONObject>()
                reader.forEachLine{line->runCatching{val raw=JSONObject(line);val e=raw.optJSONObject("event")?:raw;val type=e.optString("type");if(type in setOf("user/message","assistant/message"))updated=maxOf(updated,time(e.opt("time")));if(type=="session/title")title=e.optJSONObject("data")?.optString("title",title)?:title;if(e.has("seq")&&e.has("data")){events.addLast(e);if(events.size>300)events.removeFirst()}}}
                Entry(id,time(header.opt("createdAt")),updated,title,header.optString("cwd","/root/workspace"),events.toList())
            }}.getOrNull()
        }.sortedByDescending{it.updated}
    }
    fun row(e:Entry,metadata:android.content.SharedPreferences)=SessionInfo(e.id,e.title,metadata.getString("workspace:${e.id}","ws-default")?:"ws-default",metadata.getString("preset:${e.id}","standard")?:"standard",PermissionTier.entries.firstOrNull{it.key==metadata.getString("tier:${e.id}",null)}?:PermissionTier.WorkspaceWrite,e.created,e.updated,archived=metadata.getBoolean("archived:${e.id}",false))
}

class NativeSessionCache(private val context:Context){
    private val file=File(context.filesDir,"native-session-cache.json")
    @Synchronized fun load():JSONObject?=runCatching{if(file.length()>4*1024*1024)null else JSONObject(file.readText())}.getOrNull()
    @Synchronized fun save(rows:List<SessionInfo>,events:Map<String,List<JSONObject>>,redact:(String)->String){
        val data=JSONObject().put("rows",JSONArray(rows.map{r->JSONObject().put("id",r.id).put("title",r.title).put("workspace",r.workspaceId).put("preset",r.presetId).put("tier",r.tier.key).put("created",r.createdAt).put("updated",r.updatedAt).put("archived",r.archived)}))
        val messages=JSONObject();for((id,value)in events)messages.put(id,JSONArray(value));data.put("events",messages)
        val temp=File(context.filesDir,"native-session-cache.tmp");temp.writeText(redact(data.toString()));check(temp.renameTo(file))
    }
    fun rows(data:JSONObject)=data.optJSONArray("rows")?.let{a->(0 until a.length()).map{a.getJSONObject(it)}.map{r->SessionInfo(r.getString("id"),r.getString("title"),r.getString("workspace"),r.getString("preset"),PermissionTier.entries.firstOrNull{it.key==r.optString("tier")}?:PermissionTier.WorkspaceWrite,r.optLong("created"),r.optLong("updated"),archived=r.optBoolean("archived"))}}.orEmpty()
}
