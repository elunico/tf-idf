import kotlinx.coroutines.*
import tom.utils.requests.fetchBytes
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.file.Paths
import java.time.Instant
import kotlin.io.path.createDirectory
import kotlin.io.path.isDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit

fun String.escapedForFilesystemPath(): String {
    return URLEncoder.encode(this, "utf-8").replace("%", "+")
}

fun fileForEntry(type: String, url: String): File {
    val file = Paths.get(".cache", "$type-${url.escapedForFilesystemPath()}").toAbsolutePath().toFile()
    return file
}

suspend fun readCachedItem(type: String, url: String, maxAge: Duration): String? {
    return try {
        val file = fileForEntry(type, url)
        if (!file.exists()) {
            throw IOException("No such file")
        }
        val now = Instant.now().toEpochMilli()
        val lastRightMillis = file.lastModified()
        val maximum = maxAge.toLong(DurationUnit.MILLISECONDS)
        if ((now - lastRightMillis) >= maximum) {
            null
        } else {
            file.readText()
        }
    } catch (i: IOException) {
        null
    }
}

suspend fun fetchURLContent(type: String, url: String): String? {
    val gotten = when (type) {
        "text_file" -> trying { File(url).readText() }
        "plain_text" -> URL(url).fetchBytes()?.toString(Charset.defaultCharset())
        "json_array" -> trying { URL(url).readText().parseJSONArray() }
        else -> throw IllegalArgumentException("Must specify link as 'url' or 'file' not $type")
    }
    return gotten
}

suspend fun loadData(type: String, url: String, doCache: Boolean): String {
    val cachedItem = readCachedItem(type, url, 1.days)
    if (cachedItem != null) {
        println("${green}Read ${cachedItem.length} bytes from cache$black")
        return cachedItem + "\n"
    }

    val gotten = fetchURLContent(type, url)
    if (gotten != null) {
        println("${blue}Fetched ${gotten.length} bytes from $url$black")
        if (doCache) {
            fileForEntry(type, url).writeText(gotten)
        }
        return gotten + "\n"
    }

    println("${red}Could not fetch from url: $url$black")
    return ""
}

@JvmOverloads
fun loadAllSourceFiles(
    parentDirectory: File = Paths.get(".").toFile(), filename: String = "source_files.txt"
): List<String> {
    Paths.get(".cache").also { if (!it.isDirectory()) it.createDirectory() }
    val lines = File(parentDirectory, filename).readLines()
    val deferredText = mutableListOf<Deferred<String>>()
    val entries = lines.map { it.split(":\\s+".toRegex()) }
    val text = runBlocking {
        for (e in entries) {
            if (e.all(String::isEmpty)) continue
            require(e.size > 1) { "Must specify link as 'url:' or 'file:' before path. Do *not* add blank lines" }
            val (type, url) = e
            val fetchedData = loadData(type, url, true)
            deferredText.add(async(Dispatchers.IO) { (fetchedData ?: "") + "\n" })
        }
        deferredText.awaitAll()
    }
    println("Done loading text")
    return text
}