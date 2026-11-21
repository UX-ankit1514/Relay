package app.relay.demo

import app.relay.data.Db
import app.relay.data.DocSnapshot
import app.relay.data.KeyValue
import app.relay.data.QuerySpec
import app.relay.data.RelayError
import app.relay.data.WriteOp
import app.relay.data.str
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Demo version: an in-browser stand-in for Cloud Firestore. Documents live in memory and are
 * saved to localStorage, so a page refresh keeps the presenter's progress. Never talks to a server.
 *
 * Read access mirrors the important Firestore rule: a project and everything under it can only be
 * read by its members — so Maya still can't open the project, only her handoff.
 */
class DemoDb(
    private val kv: KeyValue,
    private val currentUid: () -> String?,
    private val newToken: () -> String,
    seed: () -> Map<String, JsonObject>,
) : Db {
    private val docs = MutableStateFlow(load() ?: seed().also { save(it) })

    // ── Raw access for the demo "server" (no access rules, like the Admin SDK) ──
    fun doc(path: String): JsonObject? = docs.value[path]
    fun children(collection: String): List<Pair<String, JsonObject>> = rows(docs.value, collection)

    override fun newId(): String = newToken()

    override suspend fun get(path: String): JsonObject? {
        checkRead(path, docs.value)
        return docs.value[path]
    }

    override fun observe(path: String): Flow<JsonObject?> =
        docs.map { checkRead(path, it); it[path] }.distinctUntilChanged()

    override suspend fun query(q: QuerySpec): List<DocSnapshot> {
        checkRead(q.collection, docs.value)
        return run(docs.value, q).map { DocSnapshot(it.first, it.second) }
    }

    override fun observeQuery(q: QuerySpec): Flow<List<DocSnapshot>> =
        docs.map { checkRead(q.collection, it); run(it, q) }
            .distinctUntilChanged()
            .map { list -> list.map { DocSnapshot(it.first, it.second) } }

    override suspend fun write(ops: List<WriteOp>) {
        val next = docs.value.toMutableMap()
        for (op in ops) when (op) {
            is WriteOp.Set -> next[op.path] = if (op.merge) deepMerge(next[op.path] ?: JsonObject(emptyMap()), op.data) else op.data
            is WriteOp.Update -> {
                val existing = next[op.path] ?: throw RelayError("That item no longer exists.")
                next[op.path] = applyUpdate(existing, op.data)
            }
            is WriteOp.Delete -> next.remove(op.path)
        }
        docs.value = next
        save(next)
    }

    // ── Access rule (projects are members-only) ─────────────────────────────
    private fun checkRead(path: String, all: Map<String, JsonObject>) {
        val parts = path.split('/')
        if (parts.size < 2 || parts[0] != "projects") return
        val project = all["projects/${parts[1]}"] ?: return
        val uid = currentUid()
        val members = project["memberIds"] as? JsonArray
        if (uid == null || members?.none { it.str() == uid } != false) throw RelayError("PERMISSION_DENIED: Access not permitted.")
    }

    // ── Queries ─────────────────────────────────────────────────────────────
    private fun rows(all: Map<String, JsonObject>, collection: String): List<Pair<String, JsonObject>> {
        val prefix = "$collection/"
        return all.entries
            .filter { it.key.startsWith(prefix) && it.key.indexOf('/', prefix.length) < 0 }
            .map { it.key.substring(prefix.length) to it.value }
    }

    private fun run(all: Map<String, JsonObject>, q: QuerySpec): List<Pair<String, JsonObject>> {
        var out = rows(all, q.collection)
        for (w in q.where) out = out.filter { matches(field(it.second, w.field), w.op, w.value) }
        q.orderBy?.let { f ->
            out = out.sortedWith { a, b -> compareValues(field(a.second, f), field(b.second, f)) }
            if (q.descending) out = out.reversed()
        }
        q.limit?.let { out = out.take(it) }
        return out
    }

    private fun field(o: JsonObject, path: String): JsonElement? {
        var cur: JsonElement? = o
        for (k in path.split('.')) cur = (cur as? JsonObject)?.get(k)
        return cur
    }

    private fun matches(v: JsonElement?, op: String, expected: JsonElement): Boolean = when (op) {
        "==" -> v != null && same(v, expected)
        "!=" -> v == null || !same(v, expected)
        "array-contains" -> (v as? JsonArray)?.any { same(it, expected) } == true
        "in" -> (expected as? JsonArray)?.any { v != null && same(v, it) } == true
        else -> true
    }

    private fun same(a: JsonElement, b: JsonElement): Boolean =
        if (a is JsonPrimitive && b is JsonPrimitive) a.content == b.content && a.isString == b.isString else a == b

    private fun compareValues(a: JsonElement?, b: JsonElement?): Int {
        val x = (a as? JsonPrimitive); val y = (b as? JsonPrimitive)
        val dx = x?.takeIf { !it.isString }?.doubleOrNull; val dy = y?.takeIf { !it.isString }?.doubleOrNull
        return if (dx != null && dy != null) dx.compareTo(dy) else (x?.content ?: "").compareTo(y?.content ?: "")
    }

    // ── Writes ──────────────────────────────────────────────────────────────
    /** Firestore update semantics: each key (dotted keys reach into maps) replaces that field. */
    private fun applyUpdate(doc: JsonObject, data: JsonObject): JsonObject {
        var out = doc
        for ((k, v) in data) out = setPath(out, k.split('.'), v)
        return out
    }

    private fun setPath(o: JsonObject, keys: List<String>, v: JsonElement): JsonObject {
        val m = o.toMutableMap()
        if (keys.size == 1) m[keys[0]] = v
        else m[keys[0]] = setPath((m[keys[0]] as? JsonObject) ?: JsonObject(emptyMap()), keys.drop(1), v)
        return JsonObject(m)
    }

    private fun deepMerge(base: JsonObject, add: JsonObject): JsonObject {
        val m = base.toMutableMap()
        for ((k, v) in add) {
            val old = m[k]
            m[k] = if (old is JsonObject && v is JsonObject) deepMerge(old, v) else v
        }
        return JsonObject(m)
    }

    // ── Persistence ─────────────────────────────────────────────────────────
    private fun load(): Map<String, JsonObject>? = runCatching {
        val raw = kv.get(STORAGE_KEY) ?: return null
        Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonObject }
    }.getOrNull()

    private fun save(all: Map<String, JsonObject>) {
        runCatching { kv.put(STORAGE_KEY, JsonObject(all).toString()) }
    }

    companion object {
        /** Bump the suffix when the seed story changes, so browsers pick up the new story. */
        const val STORAGE_KEY = "relay.demo.db.v1"
    }
}
