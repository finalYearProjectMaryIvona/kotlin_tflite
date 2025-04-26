package com.example.trafficobjectdetection
import android.content.Context
import org.tensorflow.lite.support.metadata.MetadataExtractor
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.MappedByteBuffer

/**
 * This is an object for extracting the mdoel metadata and label information
 * for the TFLite model
 */
object MetaData {

    /**
     * Extracts class names from the models metadata
     * returns a list of class names
     */
    fun extractNamesFromMetadata(model: MappedByteBuffer): List<String> {
        try {
            //create the extractor
            val metadataExtractor = MetadataExtractor(model)
            //try and get the meta data file
            val inputStream = metadataExtractor.getAssociatedFile("temp_meta.txt")
            //read the metadata text or return empty list of null
            val metadata = inputStream?.bufferedReader()?.use { it.readText() } ?: return emptyList()

            //regular expression to extract names
            val regex = Regex("'names': \\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)

            val match = regex.find(metadata)
            val namesContent = match?.groups?.get(1)?.value ?: return emptyList()

            //2nd regex to get actual class names
            val regex2 = Regex("\"([^\"]*)\"|'([^']*)'")
            val match2 = regex2.findAll(namesContent)
            //convert matches to a list
            val list = match2.map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

            return list
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /**
     * Extracts class names from a lebl file in the assets folder
     * each line is a class name
     * we read in them all but retrict what is actually detected to vehicles
     */
    fun extractNamesFromLabelFile(context: Context, labelPath: String): List<String> {
        val labels = mutableListOf<String>()
        try {
            //open the label file form assets
            val inputStream: InputStream = context.assets.open(labelPath)
            val reader = BufferedReader(InputStreamReader(inputStream))
            // read each line and add to labels list
            var line: String? = reader.readLine()
            while (line != null && line != "") {
                labels.add(line)
                line = reader.readLine()
            }
            //close resources
            reader.close()
            inputStream.close()
            return labels
        } catch (e: IOException) {
            return emptyList()
        }
    }

    /**
     * Fallback class to use when there is no metadata or label file
     */
    val TEMP_CLASSES = List(1000) { "class${it + 1}" }
}
