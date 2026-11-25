package app.phantom.core

import app.phantom.data.objects
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

object RoutingImport {
    fun parse(input: String): JSONArray {
        var text=input.trim()
        if(text.startsWith("happ://routing/")) text=String(Base64.getDecoder().decode(text.substringAfterLast('/')),Charsets.UTF_8)
        val result=if(text.startsWith("[")) JSONArray(text) else {
            val j=JSONObject(text)
            val xray=j.optJSONObject("routing") ?: j
            if(xray.has("rules")) {
                JSONArray().apply { xray.getJSONArray("rules").objects().forEach { r->
                    require(!r.has("balancerTag")) { "Правило с balancerTag требует полного JSON сервера" }
                    val out=r.optString("outboundTag")
                    val action=when(out.lowercase()) { "proxy"->"Proxy";"direct","freedom"->"Direct";"block","blackhole"->"Block";else->error("Для импорта правил используйте теги proxy/direct/block") }
                    require(r.keys().asSequence().all { it in listOf("type","domain","ip","outboundTag") }) { "Профиль поддерживает только domain/IP; остальные поля требуют полного JSON" }
                    put(JSONObject(r.toString()).apply { remove("outboundTag");put("action",action) })
                } }
            } else JSONArray().apply {
                var found=false
                listOf("Proxy","Direct","Block").forEach { action-> listOf("Sites" to "domain","Ip" to "ip").forEach { (suffix,field)->
                    val arr=j.optJSONArray(action+suffix)
                    if(arr!=null && arr.length()>0) { found=true;put(JSONObject().put("action",action).put(field,arr)) }
                } }
                require(found) { "Нет совместимых правил доменов и IP" }
                val meaningful=j.keys().asSequence().filter { k-> k !in listOf("Name","ProxySites","DirectSites","BlockSites","ProxyIp","DirectIp","BlockIp","GlobalProxy","DomainStrategy") && j.opt(k).toString() !in listOf("","[]","{}","null") }.toList()
                require(meaningful.isEmpty()) { "Профиль Happ содержит DNS/Geo или другие поля: используйте полный Xray JSON, чтобы не потерять настройки" }
            }
        }
        result.objects().forEach { rule->
            require(rule.optString("action") in listOf("Proxy","Direct","Block")) { "Направление: Proxy, Direct или Block" }
            require(rule.keys().asSequence().all { it in listOf("action","type","domain","ip") }) { "Неизвестное поле правила" }
            require(rule.has("domain") || rule.has("ip")) { "В правиле нет domain или ip" }
            for(key in listOf("domain","ip")) if(rule.has(key)) {
                val values=rule.getJSONArray(key);require(values.length()>0) { "Пустое правило" }
                for(i in 0 until values.length()) require(values.getString(i).isNotBlank()) { "Пустое значение правила" }
            }
        }
        return result
    }
}
