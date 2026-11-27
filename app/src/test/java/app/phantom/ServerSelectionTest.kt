package app.phantom

import app.phantom.data.Catalog
import app.phantom.data.Server
import app.phantom.data.ServerCountries
import app.phantom.data.Subscription
import app.phantom.data.groupServers
import app.phantom.data.orderServerGroups
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ServerSelectionTest {
    private val countries = ServerCountries(Locale.forLanguageTag("ru"))

    @Test fun countryUsesMetadataThenFlagThenUnambiguousName() {
        val metadata = server("🇵🇱 Poland 1", config = """{"meta":{"location":{"countryCode":"DEU"}}}""")
        assertEquals("DE", countries.resolve(metadata)?.code)
        assertEquals("FR", countries.resolve(server("Paris / 🇫🇷 1"))?.code)
        assertEquals("GB", countries.resolve(server("UK-2"))?.code)
        assertEquals("KR", countries.resolve(server("Южная Корея 3"))?.code)
        assertNull(countries.resolve(server("Корея 3")))
        assertNull(countries.resolve(server("Canada / Germany")))
        assertNull(countries.resolve(server("🇫🇷 🇩🇪 relay")))
        assertNull(countries.resolve(server("cdn node us-east relay")))
    }

    @Test fun sortsStablyAndKeepsOtherLastDuringAndAfterPing() {
        val servers = listOf(
            server("Relay", 1),
            server("France B", 30),
            server("Germany B", 20),
            server("France A", 15),
            server("France C", 15),
            server("Germany A", -1),
            server("Poland A"),
        )
        val groups = groupServers(servers, servers.associate { it.id to countries.resolve(it) })
        fun order(by: String, initial: Map<String, Long>? = null) = orderServerGroups(groups, by, countries, initial)
        assertEquals(listOf("FR", "DE", "PL", null), order("original").map { it.country?.code })
        assertEquals(listOf("France B", "France A", "France C"), order("original").first().servers.map { it.name })
        assertEquals(listOf("DE", "PL", "FR", null), order("alphabet").map { it.country?.code })
        assertEquals(listOf("Germany A", "Germany B"), order("alphabet").first().servers.map { it.name })
        assertEquals(listOf("FR", "DE", "PL", null), order("latency").map { it.country?.code })
        assertEquals(listOf("France A", "France C", "France B"), order("latency").first().servers.map { it.name })
        assertEquals(15L, order("latency").first().latency)
        assertNull(order("latency")[2].latency)

        val previous = mapOf("France B" to 36L, "Germany B" to 26L, "France A" to 56L)
        val during = order("latency", previous)
        assertEquals(listOf("DE", "FR", "PL", null), during.map { it.country?.code })
        assertEquals(listOf("France B", "France A", "France C"), during[1].servers.map { it.name })
        assertEquals(15L, during[1].latency)
    }

    @Test fun autoSelectionPersistsAndUsesOnlySuccessfulSupportedProfilesAcrossSources() {
        val untested = server("Untested")
        val fasterWithError = server("Error", 1).copy(latencyError = "Ошибка")
        val unsupported = server("Unsupported", 2).copy(issue = "Неподдерживаемый")
        val best = server("Best", 8)
        val favorite = server("Favorite", 30).copy(favorite = true)
        val catalog = Catalog(
            subscriptions = listOf(
                Subscription(name = "Подписка", servers = listOf(untested, fasterWithError, unsupported, favorite)),
                Subscription(id = "manual", name = "Мои серверы", servers = listOf(best)),
            ),
            selected = favorite.id, autoSelect = true, settings = mapOf("filter" to "Favorite"),
        )
        val reloaded = Catalog.from(catalog.json())
        assertTrue(reloaded.autoSelect)
        assertEquals(favorite, reloaded.selectedServer())
        assertEquals(best, reloaded.fastestServer())
        assertEquals(favorite, reloaded.fastestServer(setOf(best.id)))
        assertNull(reloaded.fastestServer(setOf(best.id, favorite.id)))
        assertFalse(Catalog.from(catalog.json().apply { remove("autoSelect") }).autoSelect)
    }

    private fun server(name: String, latency: Long? = null, config: String = "{}") =
        Server(id = name, name = name, config = config, latency = latency)
}
