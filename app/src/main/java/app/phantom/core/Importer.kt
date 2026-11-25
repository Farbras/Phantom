package app.phantom.core

import app.phantom.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

object Importer {
    const val MAX_BYTES=8*1024*1024
    fun metadata(value: String): String = if(value.startsWith("base64:")) runCatching {
        String(Base64.getDecoder().decode(value.substring(7)),Charsets.UTF_8)
    }.getOrDefault("") else value

    fun parse(input: String): List<Server> {
        require(input.toByteArray().size<=MAX_BYTES) { "Конфигурация больше 8 МБ" }
        var text=input.trim().removePrefix("\uFEFF")
        require(text.isNotBlank()) { "Пустой ответ подписки" }
        require(!text.startsWith("<")) { "Сервер вернул HTML вместо конфигураций. Проверьте URL и User-Agent." }
        require(!text.startsWith("happ://crypt") && !text.startsWith("happ://crypto")) { "Зашифрованная ссылка Happ требует исходный URL от провайдера" }
        if(!text.startsWith("[") && !text.startsWith("{") && "://" !in text) {
            text=runCatching { String(Base64.getMimeDecoder().decode(text),Charsets.UTF_8) }.getOrElse { error("Неизвестный или зашифрованный формат подписки") }.trim()
        }
        if(text.startsWith("[") || text.startsWith("{")) {
            val configs=if(text.startsWith("[")) JSONArray(text).objects() else listOf(JSONObject(text))
            require(configs.isNotEmpty()) { "В подписке нет конфигураций" }
            return configs.mapIndexed { index,c ->
                val name=c.optString("remarks",c.optString("name","Сервер ${index+1}"))
                val raw=c.toString()
                val issue=when {
                    !c.has("outbounds") -> "JSON не содержит outbounds Xray"
                    c.optJSONArray("outbounds")?.length()==0 -> "Пустой список outbounds"
                    else -> unsupported(c)
                }
                c.put("_phantomProviderJson",true)
                Server(id=hash(raw),name=name.take(180),config=c.toString(),issue=issue)
            }
        }
        val lines=text.lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        require(lines.isNotEmpty()) { "Нет серверов" }
        return lines.mapIndexed { i,line ->
            val scheme=line.substringBefore("://","").lowercase()
            val name=runCatching { java.net.URLDecoder.decode(line.substringAfter('#',"Сервер ${i+1}"),"UTF-8") }.getOrDefault("Сервер ${i+1}")
            if(scheme !in listOf("vless","vmess","trojan","ss","hysteria2","hy2","socks","socks5","http","https","wireguard")) {
                Server(id=hash(line),name=name.take(180),config="{}",issue="Формат $scheme не поддерживается импортом Xray")
            } else try {
                // Convert separately: libXray otherwise silently skips invalid lines in a batch.
                val data=Core.call("convertShareLinksToXrayJson",JSONObject().put("text",line))
                Server(id=hash(line),name=name.take(180),config=data.toString())
            } catch(e: CoreFailure) { Server(id=hash(line),name=name.take(180),config="{}",issue=e.category) }
        }
    }
    private fun unsupported(c: JSONObject): String {
        val protocols=setOf("vless","vmess","trojan","shadowsocks","socks","http","hysteria","masque","wireguard","freedom","direct","blackhole","block","dns","loopback")
        val networks=setOf("tcp","raw","ws","websocket","grpc","xhttp","splithttp","httpupgrade","kcp","mkcp","hysteria","masque")
        for(o in c.getJSONArray("outbounds").objects()) {
            val p=o.optString("protocol")
            if(p !in protocols) return "Неподдерживаемый протокол Xray: ${p.take(30)}"
            val n=o.optJSONObject("streamSettings")?.optString("network","tcp") ?: "tcp"
            if(n !in networks) return "Неподдерживаемый транспорт Xray: ${n.take(30)}"
        }
        return ""
    }
}
