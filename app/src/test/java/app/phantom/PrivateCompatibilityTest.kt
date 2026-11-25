package app.phantom

import app.phantom.core.ConfigBuilder
import app.phantom.core.Importer
import app.phantom.data.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in local audit. Neither private input nor core output is copied into the project/report. */
class PrivateCompatibilityTest {
    @Test fun everyProviderJsonPassesAfterPhantomAdaptation() {
        val dir=System.getenv("PHANTOM_AUDIT_DIR")
        val exe=System.getenv("PHANTOM_XRAY_EXE")
        assumeTrue(dir!=null && exe!=null)
        val nodes=Importer.parse(File(dir!!,"subscription.json").readText())
        var failures=0
        nodes.forEachIndexed { i,s->
            val config=ConfigBuilder.build(s,Catalog(),File(exe!!).parent)
            val path=File(dir,"adapted-$i.json").apply {writeText(config)}
            val process=ProcessBuilder(exe,"run","-test","-config",path.absolutePath).redirectErrorStream(true).start()
            process.inputStream.use { it.readBytes() } // Never print raw native diagnostics from private configs.
            if(process.waitFor()!=0) failures++
        }
        println("Private configuration audit: count=${nodes.size}, rejected=$failures")
        assertEquals(0,failures)
    }
}
