package app.phantom.core

import libXray.LibXray
import org.json.JSONObject

class CoreFailure(val category: String) : Exception(category)

object Core {
    // Native errors may contain credentials or provider addresses. Only classify them for UI/logs.
    fun call(method: String, payload: JSONObject = JSONObject()): JSONObject {
        val response=JSONObject(LibXray.invoke(JSONObject().put("apiVersion",3).put("method",method).put("payload",payload).toString()))
        if(!response.optBoolean("success")) {
            val message=response.optString("error").lowercase()
            throw CoreFailure(when {
                "already running" in message || "active" in message -> "Проверка ядра требует остановленного VPN"
                "geo" in message -> "Не найден или несовместим файл GeoIP/GeoSite"
                "address already" in message -> "Локальный порт уже занят"
                "uuid" in message -> "Некорректный идентификатор VLESS/VMess"
                "public key" in message || "publickey" in message -> "Некорректный ключ Reality"
                "flow" in message -> "Несовместимый flow протокола или транспорта"
                "transport protocol" in message || "unknown network" in message -> "Транспорт этой конфигурации не поддерживается Xray"
                "unknown" in message || "unsupported" in message -> "Неподдерживаемый протокол или параметр Xray"
                "invalid" in message || "failed to parse" in message -> "Некорректные параметры конфигурации"
                else -> "Xray отклонил конфигурацию или не смог выполнить операцию"
            })
        }
        return response.optJSONObject("data") ?: JSONObject()
    }
    fun validate(config: String) { call("testXray",JSONObject().put("xrayJson",config)) }
    fun start(config: String) { call("runXray",JSONObject().put("xrayJson",config)) }
    fun stop() { call("stopXray") }
    fun running() = call("getXrayState").optBoolean("running")
}
