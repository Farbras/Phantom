package app.phantom.data

import org.json.JSONObject
import java.text.Collator
import java.util.Locale

internal data class ServerCountry(val code: String, val name: String) {
    val flag: String = code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

internal class ServerCountries(locale: Locale) {
    private val regions = Locale.getISOCountries().associateWith { Locale.Builder().setRegion(it).build() }
    private val countries = regions.mapValues { (code, region) ->
        ServerCountry(code, region.getDisplayCountry(locale))
    }
    private val iso3 = regions.entries.associate { (code, region) -> region.isO3Country to code }
    private val names = buildMap<String, Set<String>> {
        fun add(code: String, name: String) {
            val normalized = normalize(name)
            if (normalized.isNotEmpty()) put(normalized, get(normalized).orEmpty() + code)
        }
        for ((code, region) in regions) {
            add(code, countries.getValue(code).name)
            add(code, region.getDisplayCountry(Locale.ENGLISH))
            add(code, region.getDisplayCountry(RUSSIAN))
        }
        for ((code, aliases) in countryAliases) aliases.forEach { add(code, it) }
    }
    private val namesInLabel = names.keys.sortedByDescending { it.length }
        .joinToString("|", "(?<![\\p{L}\\p{M}])(?:", ")(?![\\p{L}\\p{M}])", transform = Regex::escape)
        .toRegex()
    private val order = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    fun compareNames(first: String, second: String): Int = order.compare(first, second)

    fun resolve(server: Server): ServerCountry? {
        fromMetadata(server.config)?.let { return it }
        val flags = flagsIn(server.name).toSet()
        if (flags.isNotEmpty()) return flags.singleOrNull()?.let(countries::get)
        return namesInLabel.findAll(normalize(server.name))
            .flatMap { names.getValue(it.value).asSequence() }
            .toSet().singleOrNull()?.let(countries::get)
    }

    private fun fromMetadata(config: String): ServerCountry? {
        val root = runCatching { JSONObject(config) }.getOrNull() ?: return null
        val containers = listOfNotNull(root.optJSONObject("meta"), root.optJSONObject("metadata"), root)
        for (container in containers) {
            for (field in countryFields) {
                val value = container.opt(field)
                val country = when (value) {
                    is String -> metadataCountry(value)
                    is JSONObject -> listOf("isoCode", "code", "name", "countryCode", "country", "countryName", "flag")
                        .firstNotNullOfOrNull { metadataCountry(value.optString(it)) }
                    else -> null
                }
                if (country != null) return country
            }
        }
        return null
    }

    private fun metadataCountry(value: String): ServerCountry? {
        val code = value.trim().uppercase(Locale.ROOT)
        return countries[code] ?: iso3[code]?.let(countries::get)
            ?: flagsIn(value).toSet().singleOrNull()?.let(countries::get)
            ?: names[normalize(value)]?.singleOrNull()?.let(countries::get)
    }

    private fun flagsIn(text: String): Sequence<String> = flagPattern.findAll(text).mapNotNull { match ->
        val code = match.value.codePoints().toArray().map { ('A' + (it - 0x1F1E6)) }.joinToString("")
        code.takeIf(countries::containsKey)
    }

    private companion object {
        val RUSSIAN: Locale = Locale.forLanguageTag("ru")
        val words = Regex("[\\p{L}\\p{M}]+")
        val flagPattern = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")
        val countryFields = listOf("countryCode", "country_code", "country-code", "country", "countryName",
            "country_name", "serverCountryCode", "serverCountry", "countryFlag", "location")

        fun normalize(value: String): String = words.findAll(value.lowercase(Locale.ROOT).replace('ё', 'е'))
            .joinToString(" ") { it.value }

        val countryAliases = mapOf(
            "AE" to listOf("ОАЭ", "UAE"),
            "BY" to listOf("Белоруссия"),
            "CD" to listOf("Congo", "Конго", "DR Congo", "ДР Конго"),
            "CG" to listOf("Congo", "Конго"),
            "CZ" to listOf("Чехия", "Czech Republic"),
            "GB" to listOf("Великобритания", "UK", "U.K.", "Great Britain"),
            "KR" to listOf("Korea", "Корея", "South Korea", "Южная Корея"),
            "KP" to listOf("Korea", "Корея", "North Korea", "Северная Корея", "КНДР"),
            "MD" to listOf("Молдавия", "Молдова"),
            "NL" to listOf("Голландия", "Holland"),
            "RU" to listOf("Россия", "РФ", "Russian Federation"),
            "TR" to listOf("Turkey", "Türkiye"),
            "US" to listOf("США", "Соединённые Штаты Америки", "USA", "U.S.A.", "United States of America"),
        )
    }
}

internal data class ServerGroup(
    val country: ServerCountry?,
    val servers: List<Server>,
) {
    val latency: Long? = servers.mapNotNull(Server::successfulLatency).minOrNull()
}

internal fun groupServers(servers: List<Server>, countryById: Map<String, ServerCountry?>): List<ServerGroup> =
    servers.distinctBy { it.id }.groupBy { countryById[it.id] }.map { (country, members) ->
        ServerGroup(country, members)
    }

internal fun orderServerGroups(
    groups: List<ServerGroup>,
    order: String,
    countries: ServerCountries,
    initialLatencies: Map<String, Long>?,
): List<ServerGroup> {
    fun latency(server: Server): Long? =
        if (initialLatencies == null) server.successfulLatency else initialLatencies[server.id]

    val known = groups.filter { it.country != null }
    val ordered = when (order) {
        "latency" -> known.sortedWith(compareBy(nullsLast(naturalOrder<Long>())) { group ->
            group.servers.mapNotNull(::latency).minOrNull()
        })
        "alphabet" -> known.sortedWith { first, second ->
            countries.compareNames(first.country!!.name, second.country!!.name)
        }
        else -> known
    }
    return (ordered + groups.filter { it.country == null }).map { group ->
        group.copy(servers = when (order) {
            "latency" -> group.servers.sortedWith(compareBy(nullsLast(naturalOrder<Long>()), ::latency))
            "alphabet" -> group.servers.sortedWith { first, second -> countries.compareNames(first.name, second.name) }
            else -> group.servers
        })
    }
}
