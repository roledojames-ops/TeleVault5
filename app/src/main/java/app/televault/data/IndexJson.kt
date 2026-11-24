package app.televault.data

import org.json.JSONArray
import org.json.JSONObject

/** (De)serialises the drive index. Uses the built-in org.json so there are no extra dependencies. */
object IndexJson {

    fun toJson(index: VaultIndex): String {
        val root = JSONObject()
        root.put("v", 1)
        val folders = JSONArray()
        for (f in index.folders.sorted()) folders.put(f)
        root.put("folders", folders)

        val files = JSONArray()
        for (f in index.files) {
            val o = JSONObject()
            o.put("id", f.id)
            o.put("name", f.name)
            o.put("size", f.size)
            o.put("mime", f.mime)
            o.put("folder", f.folder)
            o.put("createdAt", f.createdAt)
            val chunks = JSONArray()
            for (c in f.chunks) {
                val co = JSONObject()
                co.put("i", c.index)
                co.put("m", c.messageId)
                co.put("f", c.fileId)
                co.put("s", c.size)
                chunks.put(co)
            }
            o.put("chunks", chunks)
            files.put(o)
        }
        root.put("files", files)
        return root.toString()
    }

    fun fromJson(text: String): VaultIndex {
        val root = JSONObject(text)
        val folders = LinkedHashSet<String>()
        val fa = root.optJSONArray("folders")
        if (fa != null) {
            for (i in 0 until fa.length()) {
                val p = fa.optString(i, "")
                if (p.startsWith("/") && p != Paths.ROOT) folders.add(p)
            }
        }
        val files = ArrayList<VaultFile>()
        val arr = root.optJSONArray("files")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ca = o.optJSONArray("chunks") ?: continue
                val chunks = ArrayList<Chunk>()
                for (j in 0 until ca.length()) {
                    val co = ca.optJSONObject(j) ?: continue
                    chunks.add(
                        Chunk(
                            index = co.optInt("i"),
                            messageId = co.optLong("m"),
                            fileId = co.optString("f"),
                            size = co.optLong("s"),
                        )
                    )
                }
                if (chunks.isEmpty()) continue
                files.add(
                    VaultFile(
                        id = o.optString("id"),
                        name = o.optString("name", "file"),
                        size = o.optLong("size"),
                        mime = o.optString("mime", "application/octet-stream"),
                        folder = o.optString("folder", Paths.ROOT),
                        createdAt = o.optLong("createdAt"),
                        chunks = chunks,
                    )
                )
            }
        }
        return VaultIndex(folders, files)
    }
}
