import com.tom.caching.FileSystemCache
import tom.utils.requests.fetchBytes
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

const val purple = "${27.toChar()}[95m"
const val blue = "${27.toChar()}[94m"
const val green = "${27.toChar()}[92m"
const val red = "${27.toChar()}[91m"
const val black = "${27.toChar()}[0m"
const val ALICE_URL = "https://gutenberg.org/files/11/11-0.txt"


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
    onProgress: (word: String, idx: Int, size: Int) -> Unit = ::printStatus
): Map<String, Double> {
    val termFrequencies = mutableMapOf<String, Int>()
    for (word in document) {
        termFrequencies[word] = termFrequency(word, document)
    }

    val result = ConcurrentHashMap<String, Double>()
    val tfs = allTermFrequencies(document)

    var idx = 0
    val size = tfs.size
    tfs.toList().parallelStream()
        .forEach { (word, freq) ->
            idx += 1
            result[word] = freq * inverseDocumentFrequency(word, corpus)
            onProgress(word, idx, size)
        }
    return result
}

class Wikipedia(val articleName: String) {
    val content: String?
        get() {
            val targetURL = "https://en.wikipedia.org/wiki/$articleName"
            val url = URL(
                "https://wikitext.eluni.co/api/extract?format=text&url=${
                    URLEncoder.encode(
                        targetURL,
                        Charset.defaultCharset()
                    )
                }"
            )
            val content = url.fetchBytes()?.toString(Charset.defaultCharset())
            // TODO: cache content and add URL to source files list
            return content
        }
}

fun Wikipedia.readText(): String = content ?: error("Failed to fetch content from Wikipedia")
val Wikipedia.nameWithoutExtension: String get() = articleName

val sourceFileDataSource = MixedDataSource(FileSystemCache())

fun main() {

    val sourceFile = File("Wizard101.txt")

//    val sourceFile = Wikipedia("Linus_Torvalds")

    val text = sourceFile.readText()
    val documents = loadAllSourceFiles(dataSource = sourceFileDataSource)
    val corpus = documents.map { it.toWords().map(String::lowercase) }

    val aliceWords = text.toWords().map(String::lowercase)
    val aliceTFIDF = tfidf(aliceWords, corpus + listOf(aliceWords))

    val result = aliceTFIDF.toList().sortedBy { e -> -e.second }
    BufferedWriter(FileWriter("${sourceFile.nameWithoutExtension}-results.txt")).use { file ->
        for ((word, rating) in result) {
            val message = "%20s: % 2.5f".format(word, rating)
            println(message)
            file.write("$message\n")
        }
    }

}