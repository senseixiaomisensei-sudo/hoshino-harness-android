package app.hoshino.harness.core

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Durable shell outbox; not part of the dsh log format or SessionStore. */
class PendingKernelMessages(context:Context){
    private val file=File(context.filesDir,"waiting-for-kernel.json")
    data class Pending(val id:String,val sessionId:String,val text:String,val attachments:List<Attachment>,val options:TurnOptions,val cwd:String="/root/workspace")
    @Synchronized fun list():List<Pending> = runCatching{val a=JSONArray(file.readText());(0 until a.length()).map{i->val r=a.getJSONObject(i);val o=r.getJSONObject("options");val media=r.optJSONArray("attachments")?:JSONArray();Pending(r.getString("id"),r.getString("session"),r.getString("text"),(0 until media.length()).map{j->val m=media.getJSONObject(j);Attachment(m.getString("id"),m.getString("name"),AttachmentKind.valueOf(m.getString("kind")),m.optString("size"))},TurnOptions(o.getString("preset"),PermissionTier.valueOf(o.getString("tier")),o.optJSONObject("model")?.let{ModelRef(it.getString("provider"),it.getString("id"))},ReasoningEffort.valueOf(o.getString("effort")),o.optBoolean("plan")),r.optString("cwd","/root/workspace"))}}.getOrDefault(emptyList())
    @Synchronized fun add(p:Pending){write(list().filterNot{it.id==p.id}+p)}
    @Synchronized fun remove(id:String){write(list().filterNot{it.id==id})}
    private fun write(rows:List<Pending>){val data=JSONArray(rows.map{p->JSONObject().put("id",p.id).put("session",p.sessionId).put("cwd",p.cwd).put("text",p.text).put("attachments",JSONArray(p.attachments.map{a->JSONObject().put("id",a.id).put("name",a.name).put("kind",a.kind.name).put("size",a.sizeLabel)})).put("options",JSONObject().put("preset",p.options.presetId).put("tier",p.options.tier.name).put("model",p.options.model?.let{JSONObject().put("provider",it.providerId).put("id",it.modelId)}).put("effort",p.options.effort.name).put("plan",p.options.planMode))});val temp=File(file.parentFile,"waiting-for-kernel.tmp");temp.writeText(data.toString());check(temp.renameTo(file))}
}
