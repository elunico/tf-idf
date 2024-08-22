import com.tom.caching.Cache
import com.tom.caching.FileIdentifier
import com.tom.caching.FileSystemCache
import javafx.application.Application
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

@JvmInline
value class StringFileIdentifier(override val id: String) : FileIdentifier

val String.fid: StringFileIdentifier get() = StringFileIdentifier(this)

interface ReadableSource {
    fun readText(): String
    val nameWithoutExtension: String
}

class FileReadableSource(val filename: String) : ReadableSource {
    val cache = FileSystemCache<FileIdentifier>()
    val file: File = File(filename)

    override fun readText(): String {
        return cache.getItem(file.nameWithoutExtension.fid) ?: file.readText()
            .also { cache.cacheItem(file.nameWithoutExtension.fid, it) }
    }

    override val nameWithoutExtension: String
        get() = file.nameWithoutExtension
}

class WikipediaReadableSource(val articleName: String) : ReadableSource {
    private val wikipedia = Wikipedia(articleName)
    val cache = FileSystemCache<FileIdentifier>()

    override fun readText(): String {
        return cache.getItem(wikipedia.nameWithoutExtension.fid) ?: wikipedia.readText()
            .also { cache.cacheItem(wikipedia.nameWithoutExtension.fid, it) }
    }

    override val nameWithoutExtension: String
        get() = wikipedia.nameWithoutExtension
}

private fun getReadableSource(method: String, content: String): ReadableSource {
    return if (method == "Wikipedia") {
        WikipediaReadableSource(content)
    } else if (method == "Text File") {
        FileReadableSource(content)
    } else {
        error("No such method $method")
    }
}

class UI : Application() {
    inner class UIMixedDataSource(cache: Cache<MixedData, String>) : MixedDataSource(cache) {
        override fun onDataFetched(item: String) {
            output.println("Fetched ${item.length} bytes from source")
        }

        override fun onCacheRetrieve(cachedValue: String) {
            output.println("Retrieved ${cachedValue.length} bytes from cache")
        }
    }

    val sourceLabel: Label = Label("Wikipedia")
    val output: TextArea = TextArea()
    val textLock = ReentrantLock()

    fun tfidfUIWrapper(
        corpus: Collection<Collection<String>>,
        words: Collection<String>,
        onProgress: (word: String, size: Int, total: Int) -> Unit,
        onResult: (word: String, rating: Double) -> Unit
    ) {
        val aliceTFIDF = tfidf(words, corpus + listOf(words), onProgress)
        val result = aliceTFIDF.toList().sortedBy { e -> -e.second }
        for ((word, rating) in result) {
            onResult(word, rating)
        }
    }


    fun TextArea.println(message: String) {
        textLock.withLock {
            text += "$message\n"
        }
    }

    fun TextArea.clearText() {
        textLock.withLock {
            text = ""
        }
    }

    override fun start(primaryStage: Stage?) {
        primaryStage ?: error("No primary stage")
        val sourceChoiceBox = ChoiceBox<String>()
        sourceChoiceBox.items.add("Wikipedia")
        sourceChoiceBox.items.add("Text File")
        sourceChoiceBox.onAction = EventHandler {
            sourceLabel.text = if (sourceChoiceBox.value == "Wikipedia") "Wikipedia Article Name" else "Text File Path"
        }

        val contentBox = TextField()

        val button = Button("Load")
        button.setOnMouseClicked {
            output.println("Program is loading corpus... this might take a while, please be patient!")
            thread(isDaemon = true) {
                val sourceFile = getReadableSource(sourceChoiceBox.value, contentBox.text)
                val text = sourceFile.readText()
                val documents = loadAllSourceFiles(dataSource = UIMixedDataSource(FileSystemCache()))
                val corpus = documents.map { it.toWords().map(String::lowercase) }
                val aliceWords = text.toWords().map(String::lowercase)

                tfidfUIWrapper(
                    corpus,
                    aliceWords,
                    { _, idx, size ->
                        Platform.runLater {
                            output.clearText()
                            output.println("Calculating IDF: %2.2f%% done...\r".format((100.0 * idx.toDouble() / size.toDouble())))
                        }
                    },
                    { word, rating ->
                        Platform.runLater {
                            output.println("%20s: % 2.5f".format(word, rating))
                        }
                    }
                )
            }

        }

        sourceChoiceBox.value = "Wikipedia"
        output.isEditable = false

        val vBox = VBox()
        vBox.padding = Insets(5.0)
        vBox.spacing = 5.0
        vBox.children.addAll(sourceLabel, sourceChoiceBox, button, contentBox, output)

        val scene = Scene(vBox)
        primaryStage.height = 480.0
        primaryStage.width = 680.0
        primaryStage.scene = scene
        primaryStage.show()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            launch(UI::class.java)
        }
    }
}