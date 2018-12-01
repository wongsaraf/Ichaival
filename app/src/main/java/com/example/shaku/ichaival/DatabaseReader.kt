package com.example.shaku.ichaival

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.preference.Preference
import androidx.annotation.UiThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.nio.charset.Charset
import java.util.*

object DatabaseReader : Preference.OnPreferenceChangeListener {
    private const val jsonLocation: String = "archives.json"
    private const val apiPath: String = "/api"
    private const val archiveListPath = "$apiPath/archivelist"
    private const val thumbPath = "$apiPath/thumbnail"
    private const val extractPath = "$apiPath/extract"

    private lateinit var archiveList: List<Archive>
    private var serverLocation: String = ""
    private var isDirty = false
    private var apiKey: String = ""
    var errorListener: DatabaseErrorListener? = null

    @UiThread
    suspend fun readArchiveList(cacheDir: File, forceUpdate: Boolean = false): List<Archive> {
        if (!this::archiveList.isInitialized || forceUpdate) {
            val jsonFile = File(cacheDir, jsonLocation)
            archiveList = if (!forceUpdate && !checkDirty(cacheDir))
                readArchiveList(jsonFile.readText())
            else {
                val archiveJson = GlobalScope.async { downloadArchiveList() }.await()
                if (archiveJson == "")
                    if (this::archiveList.isInitialized) archiveList else listOf()
                else {
                    jsonFile.writeText(archiveJson)
                    readArchiveList(archiveJson)
                }
            }
            archiveList = archiveList.sortedBy { archive -> archive.title }
            isDirty = false
        }
        return archiveList
    }

    fun updateServerLocation(location: String) {
        serverLocation = location
    }

    fun updateApiKey(key: String) {
        apiKey = key
    }

    private fun getApiKey(multiParam: Boolean) : String {
        if (apiKey.isBlank())
            return ""
        else {
           var string = "key=$apiKey"
            if (multiParam)
                string = "&$string"
            return string
        }
    }

    private fun checkDirty(fileDir: File) : Boolean {
        val jsonCache = File(fileDir, jsonLocation)
        val dayInMill = 1000 * 60 * 60 * 60 * 24L
        return isDirty || !jsonCache.exists() || Calendar.getInstance().timeInMillis - jsonCache.lastModified() >  dayInMill
    }

    override fun onPreferenceChange(pref: Preference?, newValue: Any?): Boolean {
        return try { //TODO use something better for validation
            val url = URL(newValue as String)
            if (serverLocation != newValue) {
                serverLocation = newValue
                isDirty = true
                true
            } else
                false
        } catch (e: Exception) {
            //TODO show a toast telling the user to enter a valid url.
            false
        }
    }

    suspend fun getArchive(id: String, fileDir: File) : Archive? {
        readArchiveList(fileDir)

        for (archive: Archive in archiveList) {
            if (archive.id == id)
                return archive
        }
        return null
    }

    fun getRawImageUrl(path: String) : String {
        return serverLocation + path
    }

    fun extractArchive(id: String) : JSONObject? {
        val url = URL("$serverLocation$extractPath?id=$id${getApiKey(true)}")

        try {
            with(url.openConnection() as HttpURLConnection) {
                if (responseCode != 200)
                    return null

                BufferedReader(InputStreamReader(inputStream)).use {
                    val response = StringBuffer()

                    var inputLine = it.readLine()
                    while (inputLine != null) {
                        response.append(inputLine)
                        inputLine = it.readLine()
                    }
                    val json = JSONObject(response.toString())
                    if (!json.has("error"))
                        return json
                    else{
                        notifyError(json.getString("error"))
                        return null
                    }
                }
            }
        } catch (e: SocketException) {
            notifyError("extracting archive!")
            return null
        }
    }

    private fun notifyError(error: String) {
        GlobalScope.launch(Dispatchers.Main) { errorListener?.onError(error) }
    }

    private fun downloadArchiveList() : String {
        try {
            val url = URL("$serverLocation$archiveListPath?${getApiKey(false)}")

            with(url.openConnection() as HttpURLConnection) {
                if (responseCode != 200) {
                    notifyError("downloading archive list!")
                    return ""
                }

                val decoder = Charset.forName("utf-8").newDecoder()
                BufferedReader(InputStreamReader(inputStream, decoder)).use {
                    val response = StringBuffer()

                    var inputLine = it.readLine()
                    while (inputLine != null) {
                        response.append(inputLine)
                        inputLine = it.readLine()
                    }
                    return response.toString()
                }
            }
        }
        catch (e: Exception) {
            notifyError("downloading archive list!")
            return ""
        }
    }

    private fun readArchiveList(jsonString: String) : List<Archive> {
        val json = JSONArray(jsonString)

        val archiveList = mutableListOf<Archive>()
        for (i in 0..(json.length() - 1)) {
            archiveList.add(Archive(json.getJSONObject(i)))
        }

        return archiveList
    }

    private fun downloadThumb(id: String, thumbDir: File) : File? {
        val url = URL("$serverLocation$thumbPath?id=$id${getApiKey(true)}")

        with(url.openConnection() as HttpURLConnection) {
            if (responseCode != 200) {
                notifyError("downloading thumbnail!")
                return null
            }

            BufferedReader(InputStreamReader(inputStream)).use {
                val bytes = inputStream.readBytes()

                val thumbFile = File(thumbDir, "$id.jpg")
                thumbFile.writeBytes(bytes)
                return thumbFile
            }
        }
    }

    private fun getThumbDir(cacheDir: File) : File {
        val thumbDir = File(cacheDir, "thumbs")
        if (!thumbDir.exists())
            thumbDir.mkdir()
        return thumbDir
    }

    suspend fun getArchiveImage(archive: Archive, filesDir: File) : Bitmap? {
        val id = archive.id
        val thumbDir = getThumbDir(filesDir)

        var image: File? = File(thumbDir, "$id.jpg")
        if (image != null && !image.exists())
            image = GlobalScope.async { downloadThumb(id, thumbDir) }.await()

        return if (image != null) BitmapFactory.decodeFile(image.path) else null
    }
}