package app.phantom.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun InputStream.readBounded(limit: Int): ByteArray {
    val out=ByteArrayOutputStream(minOf(limit,8192))
    val buffer=ByteArray(8192)
    while(out.size()<limit) {
        val count=read(buffer,0,minOf(buffer.size,limit-out.size()))
        if(count<0) break
        if(count>0) out.write(buffer,0,count)
    }
    return out.toByteArray()
}
