import com.google.gson.Gson
import tom.utils.requests.fetchBytes
import java.io.BufferedWriter
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

fun String.toWords(delim: Regex = "\\W+".toRegex()): List<String> {
    return this.split(delim)
}

fun termFrequency(term: String, words: Collection<String>): Int {
    return words.count { it == term }
}

fun allTermFrequencies(words: Collection<String>): Map<String, Int> {
    val map = mutableMapOf<String, Int>()
    var idx = 0
    val size = words.size
    for (word in words) {
        idx++
        print("Calculating term frequencies: %2.2f%%...\r".format(((idx * 100.0) / size)))
        map.compute(word) { k, v -> (v ?: 0) + 1 }
    }
    println()
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

fun tfidf(document: Collection<String>, corpus: Collection<Collection<String>>): Map<String, Double> {
    val termFrequencies = mutableMapOf<String, Int>()
    for (word in document) {
        termFrequencies[word] = termFrequency(word, document)
    }

    val result = ConcurrentHashMap<String, Double>()
    val tfs = allTermFrequencies(document)

    println("calculated term frequencies.")
    var idx = 0
    val size = tfs.size
    tfs.toList().parallelStream()
        .forEach { (word, freq) ->
            idx += 1
            result[word] = freq * inverseDocumentFrequency(word, corpus)
            print("Calculating IDF: %2.2f%% done...\r".format((100.0 * idx.toDouble() / size.toDouble())))
        }
    println("Done")
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
            return url.fetchBytes()?.toString(Charset.defaultCharset())
        }

}

fun Wikipedia.readText(): String = content ?: error("Failed to fetch content from Wikipedia")
val Wikipedia.nameWithoutExtension: String get() = articleName

fun main() {
//    val sourceFile = File("Wizard101.txt")
    val sourceFile = Wikipedia("Kinematics")
    val text = sourceFile.readText()
    val documents = loadAllSourceFiles()
    val corpus = documents.map { it.toWords().map(String::lowercase) }

    val aliceWords = text.toWords().map(String::lowercase)
//    val aliceWords = getPlainTextURL(ALICE_URL)?.toWords()?.map { it.lowercase() } ?: error("oops")
    println("Text prepared!")
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