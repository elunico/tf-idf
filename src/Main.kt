import com.tom.caching.DataSource
import com.tom.caching.FileCachedDataSource
import com.tom.caching.FileIdentifier
import com.tom.caching.FileSystemCache
import tom.utils.requests.fetchBytes
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

private val String.fid: FileIdentifier
    get() = object : FileIdentifier {
        override val id: String = this@fid
    }

const val purple = "${27.toChar()}[95m"
const val blue = "${27.toChar()}[94m"
const val green = "${27.toChar()}[92m"
const val red = "${27.toChar()}[91m"
const val black = "${27.toChar()}[0m"

fun <T> List<T>.tryAsMutable(): MutableList<T> {
    if (this is MutableList<T>) return this as MutableList<T>
    return ArrayList(this)
}

val sourcedWikipedias = mutableSetOf<String>() // article names

fun String.toWords(delim: Regex = "\\W+".toRegex()): List<String> {
    return this.split(delim)
}

fun termFrequency(term: String, words: Collection<String>): Int {
    return words.count { it == term }
}

fun allTermFrequencies(words: Collection<String>): Map<String, Int> {
    val map = mutableMapOf<String, Int>()
    val size = words.size
    for ((idx, word) in words.withIndex()) {
        map.compute(word) { k, v -> (v ?: 0) + 1 }
        printStatus("TF", idx, size)
    }
    return map
}

fun documentFrequencies(word: String, documents: Collection<Collection<String>>): MutableMap<Int, Int> {
    val map = mutableMapOf<Int, Int>()
    for (document in documents) {
        val frequency = termFrequency(word, document)
        map[document.hashCode()] = frequency
    }
    return map
}

fun inverseDocumentFrequency(word: String, documents: Collection<Collection<String>>): Double {
    val freq = documentFrequencies(word, documents)
    val count = documents.size

    val value = ln(count.toDouble() / freq.values.sum().toDouble())
    return value
}

private fun printStatus(kind: String, idx: Int, size: Int) {
    print("Calculating $kind: %2.2f%% done...\r".format((100.0 * idx.toDouble() / size.toDouble())))
}

fun tfidf(
    document: Collection<String>,
    corpus: Collection<Collection<String>>,
    onProgress: (idx: Int, size: Int) -> Unit = { idx, size -> printStatus("IDF", idx, size) }
): Map<String, Double> {
    val termFrequencies = mutableMapOf<String, Int>()
    for (word in document) {
        termFrequencies[word] = termFrequency(word, document)
    }

    val result = ConcurrentHashMap<String, Double>()
    val tfs = allTermFrequencies(document)

    var idx = 0
    val size = tfs.size
    tfs.toList().parallelStream().forEach { (word, freq) ->
        idx += 1
        result[word] = freq * inverseDocumentFrequency(word, corpus)
        onProgress(idx, size)
    }
    return result
}

class Wikipedia(val articleName: String) {
    val content: String?
        get() {
            val url = getWikitextURL(articleName)
            val content = url.fetchBytes()?.toString(Charset.defaultCharset())
            if (articleName !in sourcedWikipedias) {
                sourcedWikipedias.add(articleName)
            }
            return content
        }

    companion object {
        @JvmStatic
        fun getWikitextURL(articleName: String): URL {
            val targetURL = "https://en.wikipedia.org/wiki/$articleName"
            val encoded = URLEncoder.encode(targetURL, Charset.defaultCharset())
            val url = URL("https://wikitext.eluni.co/api/extract?format=text&url=$encoded")
            return url
        }
    }
}

fun Wikipedia.readText(): String = checkNotNull(content) { "Failed to fetch content from Wikipedia" }

val String.nameWithoutExtension: String
    get() {
        val dotIndex = lastIndexOf('.')
        if (dotIndex == -1) return this
        return substring(0, dotIndex)
    }

val sourceFileDataSource = MixedDataSource(FileSystemCache())

class FileDataSource : FileCachedDataSource(FileSystemCache())
class WikipediaDataSource : FileCachedDataSource(FileSystemCache()) {
    override fun fetchSupplier(identifier: FileIdentifier): String {
        return Wikipedia(identifier.id).content ?: error("Unable to fetch wikipedia")
    }
}

fun chooseSource(): Pair<DataSource<FileIdentifier, String>, String> {
    val scanner = Scanner(System.`in`)
    print("Analyze [f]ile or [w]ikipedia? ")
    val choice = scanner.nextLine().lowercase().first()
    print("Enter the name of the thing you want to analyze: ")
    return when (choice) {
        'f' -> FileDataSource() to (scanner.nextLine().trim())
        'w' -> WikipediaDataSource() to (scanner.nextLine().trim())
        else -> error("No such type $choice")
    }
}

fun setupWikipediaSourcesFile(
    parentDirectory: File = Paths.get(".").toFile(),
    filename: String = "wikipedia_source.txt"
): File {
    val f = Paths.get(parentDirectory.absolutePath, filename).toFile()
    if (!f.exists()) f.createNewFile()
    val lines = f.readLines()
    println("Loaded ${lines.size} Wikipedia articles from disk")
    sourcedWikipedias.addAll(lines)
    val tempFilename = "temp-url-list${filename.hashCode()}${filename.nameWithoutExtension}.txt.tmp"
    val tempFile = Paths.get(parentDirectory.absolutePath, tempFilename).toFile()
    tempFile.writeText(lines.joinToString("\n") { "plain_text: ${Wikipedia.getWikitextURL(it)}" })
    return tempFile
}

fun saveSourcedWikipedias(
    parentDirectory: File = Paths.get(".").toFile(),
    filename: String = "wikipedia_source.txt"
) {
    val f = Paths.get(parentDirectory.absolutePath, filename).toFile()
    f.writeText(sourcedWikipedias.joinToString("\n"))
    println("Saved ${sourcedWikipedias.size} wikipedias to disk")
}

fun main() {
    val wikipediaSource = setupWikipediaSourcesFile()

    val (source, itemName) = chooseSource()
    val text = checkNotNull(source.getData(itemName.fid)) { "Could not fetch data from $itemName" }
    val documents = loadAllSourceFiles(dataSource = sourceFileDataSource).tryAsMutable()
    documents.addAll(loadAllSourceFiles(filename = wikipediaSource.name, dataSource = sourceFileDataSource))
    wikipediaSource.deleteOnExit()
    val corpus = documents.map { it.toWords().map(String::lowercase) }

    val aliceWords = text.toWords().map(String::lowercase)
    val aliceTFIDF = tfidf(aliceWords, corpus + listOf(aliceWords))

    val result = aliceTFIDF.toList().sortedBy { e -> -e.second }
    BufferedWriter(FileWriter("${itemName.nameWithoutExtension}-results.txt")).use { file ->
        file.write("Wikipedia count: ${sourcedWikipedias.size}\n")
        file.write("Document analysis: ${nowString()}\n")
        file.write("--- BEGIN RESULTS ---\n")
        for ((word, rating) in result) {
            val message = "%20s: % 2.5f".format(word, rating)
            println(message)
            file.write("$message\n")
        }
    }
    saveSourcedWikipedias()
}

fun nowString(): String = Date().toLocaleString().replace(" ", " ")