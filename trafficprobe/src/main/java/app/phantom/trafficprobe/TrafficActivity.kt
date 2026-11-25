package app.phantom.trafficprobe

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.widget.TextView
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Separate UID: confirms application traffic, independent of the VPN service's own health request. */
class TrafficActivity: Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val label=TextView(this).apply {text="Testing application traffic…";textSize=20f;setPadding(32,72,32,32)}
        setContentView(label)
        val url=intent.getStringExtra("url") ?: "https://www.gstatic.com/generate_204"
        Thread {
            var code=-1
            try {
                val connection=URL(url).openConnection() as HttpsURLConnection
                connection.connectTimeout=10000;connection.readTimeout=10000;connection.instanceFollowRedirects=false
                code=connection.responseCode;connection.inputStream.use {it.readBytes()};connection.disconnect()
            } catch(e: Exception) { }
            sendBroadcast(Intent("app.phantom.TEST_TRAFFIC").setPackage("app.phantom").putExtra("code",code))
            runOnUiThread {label.text="HTTP result: $code"}
        }.start()
    }
}
