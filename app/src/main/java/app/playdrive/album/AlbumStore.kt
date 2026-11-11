package app.playdrive.album

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 마음 앨범 저장소: 기기 안(filesDir/albums.json)에만 보관. 위치·개인정보는 저장하지 않는다. */
class AlbumStore(private val file: File) {

    @Synchronized fun all(): List<Album> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { arr.getJSONObject(it).toAlbum() }.sortedByDescending { it.summary.startEpochMs }
    }.getOrDefault(emptyList())

    @Synchronized fun add(a: Album) = save(all().filter { it.id != a.id } + a)

    @Synchronized fun updateReflection(id: Long, text: String) =
        save(all().map { if (it.id == id) it.copy(reflection = text.take(80)) else it })

    @Synchronized fun clear() { file.delete() }

    private fun save(list: List<Album>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(file.path + ".tmp"); tmp.writeText(arr.toString()); tmp.renameTo(file)
    }

    private fun Album.toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("reflection", reflection)
        put("start", summary.startEpochMs); put("end", summary.endEpochMs)
        put("smooth", summary.smoothness.toDouble()); put("rain", summary.rained); put("moving", summary.movingSeconds)
        put("layers", JSONArray().apply {
            layers.forEach { l -> put(JSONObject().apply {
                put("x", l.x.toDouble()); put("y", l.y.toDouble()); put("r", l.r.toDouble())
                put("c", l.color); put("rain", l.rain); put("scars", l.scars); put("seed", l.seed)
            }) }
        })
    }

    private fun JSONObject.toAlbum() = Album(
        id = getLong("id"), title = getString("title"), reflection = optString("reflection", ""),
        summary = DriveSummary(getLong("start"), getLong("end"), getDouble("smooth").toFloat(), getBoolean("rain"), optInt("moving", 0)),
        layers = optJSONArray("layers")?.let { arr ->
            (0 until arr.length()).map { k -> arr.getJSONObject(k).run {
                CoverLayer(getDouble("x").toFloat(), getDouble("y").toFloat(), getDouble("r").toFloat(), getInt("c"), getInt("rain"), getInt("scars"), getInt("seed"))
            } }
        } ?: emptyList(),
    )
}
