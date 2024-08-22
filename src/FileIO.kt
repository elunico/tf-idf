import com.google.gson.Gson
import com.tom.caching.Cache
import com.tom.caching.CachedDataSource
import com.tom.caching.DataSource
import com.tom.caching.FileIdentifier
import tom.utils.requests.fetchBytes
import java.io.File
import java.net.URL
import java.nio.charset.Charset
import java.nio.file.Paths

fun <T> trying(block: () -> T): T? {
    return try {
        block()
    } catch (e: Exception) {
        null
    }
}

fun String.parseJSONArray(): String {
    val g = Gson()
    val list = g.fromJson(this, List::class.java)
    return list.joinToString("\n")
}

data class MixedData(val type: String, val url: String) : FileIdentifier {
    override val id: String
        get() = "$type-$url"
}

open class MixedDataSource(override val cache: Cache<MixedData, String>) : CachedDataSource<MixedData, String> {
    override fun tryFromCache(source: MixedData): String? {
        val cachedItem = cache.getItem(source)
        if (cachedItem != null) {
            onCacheRetrieve(cachedItem)
            return cachedItem
        }

        return null
    }

    override fun fetchFreshData(source: MixedData): String? {
        val gotten = when (source.type) {
            "text_file" -> trying { File(source.url).readText() }
            "plain_text" -> URL(source.url).fetchBytes()?.toString(Charset.defaultCharset())
            "json_array" -> trying { URL(source.url).readText().parseJSONArray() }
            else -> throw IllegalArgumentException("Must specify link as 'url' or 'file' not ${source.type}")
        }
        if (gotten != null) onDataFetched(gotten)
        return gotten
    }

    override fun onCacheRetrieve(cachedValue: String) {
        println("${blue}Retrieved ${cachedValue.length} bytes from cache$black")
    }

    override fun onDataFetched(item: String) {
        println("${green}Fetched ${item.length} bytes from source$black")
    }
}

@JvmOverloads
fun loadAllSourceFiles(
    parentDirectory: File = Paths.get(".").toFile(),
    filename: String = "source_files.txt",
    dataSource: DataSource<MixedData, String>,
): List<String> {
    val lines = File(parentDirectory, filename).readLines()
    val entries = lines.map { it.split(":\\s+".toRegex()) }
    val text = mutableListOf<String>()
    for (e in entries) {
        if (e.all(String::isEmpty)) continue
        require(e.size > 1) { "Must specify link as 'url:' or 'file:' before path. Do *not* add blank lines" }
        val (type, url) = e
        val fetchedData = dataSource.getData(MixedData(type, url)) ?: ""
        text.add(fetchedData)
    }
    println("Done loading text")
    return text
}