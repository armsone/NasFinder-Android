package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.armsone.nasfinder.data.PrivacyMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class MaterialSourceType {
    LOCAL_LIBRARY,
    PHONE_HARD,
    REMOTE_FILE
}

enum class MaterialPreparationState {
    READY,
    DOWNLOADING,
    MISSING,
    EXCLUDED
}

/** What a workbench reference points at. Legacy records without the field are media. */
enum class MaterialEntryKind {
    MEDIA,
    FOLDER,
    FILE
}

/** Pocket that holds every record written before pockets existed (read-only bridge: nothing is copied or moved). */
const val DEFAULT_POCKET_ID = "legacy-materials"
const val DEFAULT_POCKET_NAME = "기존 재료"

/**
 * One workbench entry: a LOCATION / IDENTITY / PERMISSION reference plus metadata. Adding never copies the original.
 * [localFilePath] is only a pre-existing app copy (legacy) that may be used in place; new references leave it null.
 */
data class MaterialItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceIdentity: String,
    val name: String,
    val sourceType: MaterialSourceType,
    val isVideo: Boolean,
    val uriString: String,
    val localFilePath: String? = null,
    val durationMs: Long? = null,
    val state: MaterialPreparationState = MaterialPreparationState.READY,
    val downloadProgress: Float? = null,
    val captureTimeMillis: Long = 0L,
    val addedTimeMillis: Long = System.currentTimeMillis(),
    val connectionId: String? = null,
    val remotePath: String? = null,
    val fileSize: Long? = null,
    val errorMessage: String? = null,
    val pocketId: String = DEFAULT_POCKET_ID,
    val entryKind: MaterialEntryKind = MaterialEntryKind.MEDIA,
    // Which picker/browser the reference came from (display only): MY_MEDIA, GOOGLE_PHOTOS, FILES, PHONE_HARD, NAS, FAVORITE, ...
    val origin: String? = null,
    // Result of takePersistableUriPermission at add time; null = not applicable / not asked (legacy, file, remote).
    val grantPersisted: Boolean? = null,
    // Fields written by another build that this build does not know: written back verbatim.
    val extras: String? = null,
    val mimeType: String? = null,
    // 항목 자신의 비공개 플래그(기본 false). 공개 포켓에 프라이버시 모드에서 담은 항목이 조용히 공개되지 않게 하고,
    // 포켓 전체를 숨기지도 않는다. 실제 비공개 여부는 자기 플래그 OR 포켓 OR 출처(비공개 연결·폰하드 경로)다.
    val isPrivate: Boolean = false,
) {
    val displayLabel: String get() = name.ifBlank { "미디어" }
    val isFolder: Boolean get() = entryKind == MaterialEntryKind.FOLDER
}

data class MaterialPocket(
    val id: String,
    val name: String,
    val createdMillis: Long = System.currentTimeMillis(),
    val extras: String? = null,
    // 비공개 포켓(기본 false). 포켓의 공개 범위가 안의 항목 전체에 적용된다.
    val isPrivate: Boolean = false,
)

object DurableMaterialStore {
    private const val STORE_FILE_NAME = "materials_store.json"
    private const val POCKETS_FILE_NAME = "workbench_pockets.json"
    private val items = mutableListOf<MaterialItem>()
    private val pocketList = mutableListOf<MaterialPocket>()
    private var storeDir: File? = null
    private var isInitialized = false
    // Unreadable store file: kept aside, and nothing overwrites it while persistence is blocked.
    private var loadProblem: String? = null
    private var persistBlocked = false
    // The pocket list file could not be read: pocket changes are blocked so it is never overwritten.
    private var pocketsBlocked = false
    // Store entries this build could not parse: written back verbatim so they are never lost.
    private val preservedRawRecords = mutableListOf<Any>()

    private val knownItemKeys = setOf(
        "id", "sourceIdentity", "name", "sourceType", "isVideo", "uriString", "localFilePath", "durationMs", "state",
        "captureTimeMillis", "addedTimeMillis", "connectionId", "remotePath", "fileSize", "errorMessage",
        "pocketId", "entryKind", "origin", "grantPersisted", "mimeType", "isPrivate",
    )
    private val knownPocketKeys = setOf("id", "name", "createdMillis", "isPrivate")

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        val dir = File(context.filesDir, "materials").also { it.mkdirs() }
        storeDir = dir
        loadFromDisk()
        loadPocketsFromDisk()
        isInitialized = true
    }

    /** Non-null when the saved basket could not be (fully) read; the basket is then NOT an intentionally empty one. */
    @Synchronized
    fun loadProblem(): String? = loadProblem

    /** True when saving is stopped so an unreadable store file is never overwritten. */
    @Synchronized
    fun isPersistBlocked(): Boolean = persistBlocked

    @Synchronized
    fun isPocketListBlocked(): Boolean = pocketsBlocked

    /** 비공개 출처·포켓·항목 플래그를 일반 모드에서 숨긴다. 정리·소유권 계산은 [getAllItems]를 쓴다. */
    private fun isPrivateItem(item: MaterialItem): Boolean =
        item.isPrivate ||
            pocketList.any { it.id == effectivePocketId(item) && it.isPrivate } ||
            PrivacyMode.isPrivateOrigin(item.connectionId, item.remotePath) ||
            (item.sourceType == MaterialSourceType.PHONE_HARD &&
                (PrivacyMode.isPhoneHardAbsolutePathPrivate(item.localFilePath) ||
                    PrivacyMode.isPhoneHardAbsolutePathPrivate(item.uriString.removePrefix("file://"))))

    private fun isVisibleItem(item: MaterialItem): Boolean = PrivacyMode.isActive || !isPrivateItem(item)

    /** 항목 자신·포켓·연결·폰하드 경로 중 하나라도 비공개인지(모드와 무관). 작업을 시작할 때 출처를 고정하는 데 쓴다. */
    @Synchronized
    fun isEffectivelyPrivate(item: MaterialItem): Boolean = isPrivateItem(item)

    @Synchronized
    fun getItems(): List<MaterialItem> {
        return items.filter(::isVisibleItem)
    }

    /** 비공개를 포함한 전체 항목(참조 정리·소유권 계산용). 화면 목록에는 쓰지 않는다. */
    @Synchronized
    fun getAllItems(): List<MaterialItem> = items.toList()

    @Synchronized
    fun count(): Int = items.count(::isVisibleItem)

    @Synchronized
    fun contains(id: String): Boolean = items.any { it.id == id }

    // ---- pockets -------------------------------------------------------------------------------------------------

    /** All pockets in saved order. The default (기존 재료) pocket always exists and is first unless the user moved it. */
    @Synchronized
    fun pockets(): List<MaterialPocket> =
        pocketsWithDefault().filter { it.id == DEFAULT_POCKET_ID || PrivacyMode.isVisible(it.isPrivate) }

    /** 비공개를 포함한 전체 포켓(정리용). */
    @Synchronized
    fun allPockets(): List<MaterialPocket> = pocketsWithDefault()

    private fun pocketsWithDefault(): List<MaterialPocket> =
        if (pocketList.any { it.id == DEFAULT_POCKET_ID }) pocketList.toList()
        else listOf(MaterialPocket(DEFAULT_POCKET_ID, DEFAULT_POCKET_NAME, 0L)) + pocketList

    /** A record whose pocket is unknown (e.g. its pocket entry could not be read) stays visible in the default pocket. */
    private fun effectivePocketId(item: MaterialItem): String =
        if (item.pocketId == DEFAULT_POCKET_ID || pocketList.any { it.id == item.pocketId }) item.pocketId else DEFAULT_POCKET_ID

    @Synchronized
    fun itemsIn(pocketId: String): List<MaterialItem> =
        items.filter { effectivePocketId(it) == pocketId && isVisibleItem(it) }

    @Synchronized
    fun countIn(pocketId: String): Int = items.count { effectivePocketId(it) == pocketId && isVisibleItem(it) }

    /** 명시적인 공개/비공개 전환(포켓). 포켓의 id·이름·항목·순서는 그대로다. */
    @Synchronized
    fun setPocketPrivate(id: String, isPrivate: Boolean): Boolean {
        // 기본 포켓(기존 재료)은 항상 보여야 하므로 비공개로 바꿀 수 없다(그 안의 항목은 개별로 비공개 지정한다).
        if (pocketsBlocked || id == DEFAULT_POCKET_ID) return false
        val backup = pocketList.toList()
        val index = pocketList.indexOfFirst { it.id == id }
        if (index >= 0) pocketList[index] = pocketList[index].copy(isPrivate = isPrivate)
        else if (id == DEFAULT_POCKET_ID) pocketList.add(0, MaterialPocket(DEFAULT_POCKET_ID, DEFAULT_POCKET_NAME, 0L, isPrivate = isPrivate))
        else return false
        if (!persistPockets()) {
            pocketList.clear(); pocketList.addAll(backup)
            return false
        }
        return true
    }

    /** 명시적인 공개/비공개 전환(항목). */
    @Synchronized
    fun setItemPrivate(id: String, isPrivate: Boolean): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val previous = items[index]
        if (previous.isPrivate == isPrivate) return true
        items[index] = previous.copy(isPrivate = isPrivate)
        if (!persistToDisk()) { items[index] = previous; return false }
        return true
    }

    /** Creates a pocket; null when the name is blank or the pocket list could not be saved (nothing changed then). */
    @Synchronized
    fun createPocket(name: String): MaterialPocket? {
        val clean = name.trim().take(60)
        if (clean.isEmpty() || pocketsBlocked) return null
        val pocket = MaterialPocket(UUID.randomUUID().toString(), clean, isPrivate = PrivacyMode.isActive)
        val backup = pocketList.toList()
        pocketList.add(pocket)
        if (!persistPockets()) {
            pocketList.clear(); pocketList.addAll(backup)
            return null
        }
        return pocket
    }

    @Synchronized
    fun renamePocket(id: String, name: String): Boolean {
        val clean = name.trim().take(60)
        if (clean.isEmpty() || pocketsBlocked) return false
        val backup = pocketList.toList()
        val index = pocketList.indexOfFirst { it.id == id }
        if (index >= 0) pocketList[index] = pocketList[index].copy(name = clean)
        else if (id == DEFAULT_POCKET_ID) pocketList.add(0, MaterialPocket(DEFAULT_POCKET_ID, clean, 0L))
        else return false
        if (!persistPockets()) {
            pocketList.clear(); pocketList.addAll(backup)
            return false
        }
        return true
    }

    /**
     * Deletes a pocket and ONLY its references (originals and any app copy a reference points at are never touched).
     * The default pocket cannot be deleted.
     *
     * Order is chosen so a crash or a failed write can never lose a reference: the POCKET entry is removed first. If the
     * process dies (or the next write fails) right after that, the pocket's references are still in the item file; their
     * pocket id is unknown, so they stay visible under the default pocket (see [effectivePocketId]) and nothing is lost.
     * Only after the pocket entry is durably gone are the references removed. When that second write fails the pocket
     * entry is written back, so the prior state is restored; if even that fails the references are still there (visible
     * under 기존 재료) and false is returned: the failure is never reported as "nothing changed" while references vanish.
     */
    @Synchronized
    fun deletePocket(id: String): Boolean {
        if (id == DEFAULT_POCKET_ID || pocketsBlocked || persistBlocked) return false
        if (pocketList.none { it.id == id && PrivacyMode.isVisible(it.isPrivate) }) return false
        // 일반 모드에서 보이지 않는 비공개 출처·항목을 조용히 함께 지우지 않는다.
        if (items.any { it.pocketId == id && !isVisibleItem(it) }) return false
        val itemBackup = items.toList()
        val pocketBackup = pocketList.toList()
        pocketList.removeAll { it.id == id }
        if (!persistPockets()) {
            // The atomic write failed, so the file on disk still has the pocket: nothing changed.
            pocketList.clear(); pocketList.addAll(pocketBackup)
            return false
        }
        items.removeAll { it.pocketId == id }
        if (!persistToDisk()) {
            items.clear(); items.addAll(itemBackup)
            // Put the pocket back; if this write fails too, memory keeps matching the disk (pocket gone, references visible under the default pocket).
            pocketList.clear(); pocketList.addAll(pocketBackup)
            if (!persistPockets()) pocketList.removeAll { it.id == id }
            return false
        }
        return true
    }

    // ---- items ---------------------------------------------------------------------------------------------------

    /**
     * Returns the number of newly added items, or null when they could not be saved (nothing is added then).
     * Dedupe is per pocket and only for the same provider/source identity; the same reference may live in other pockets.
     */
    @Synchronized
    fun addItems(newItems: List<MaterialItem>): Int? {
        val backup = items.toList()
        var addedCount = 0
        newItems.forEach { candidate ->
            val pocket = if (candidate.pocketId == DEFAULT_POCKET_ID || pocketList.any { it.id == candidate.pocketId }) candidate.pocketId else DEFAULT_POCKET_ID
            val exists = items.any { effectivePocketId(it) == pocket && it.sourceIdentity == candidate.sourceIdentity }
            if (!exists) {
                // 프라이버시 모드에서 공개 포켓에 새로 담는 항목은 항목 자신이 비공개다(포켓을 숨기지도, 항목을 공개하지도 않는다).
                val pocketIsPrivate = pocketList.any { it.id == pocket && it.isPrivate }
                items.add(candidate.copy(pocketId = pocket, isPrivate = candidate.isPrivate || (PrivacyMode.isActive && !pocketIsPrivate)))
                addedCount++
            }
        }
        if (addedCount > 0) {
            val success = persistToDisk()
            if (!success) {
                items.clear()
                items.addAll(backup)
                return null
            }
        }
        return addedCount
    }

    @Synchronized
    fun removeItem(id: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val removedItem = items.removeAt(index)
            val success = persistToDisk()
            if (!success) {
                items.add(index, removedItem)
                return false
            }
            return true
        }
        return false
    }

    @Synchronized
    fun reorder(fromIndex: Int, toIndex: Int): Boolean {
        // 인덱스는 보이는 항목 기준이다. 숨겨진 항목은 제자리에 둔다.
        val visible = items.filter(::isVisibleItem)
        if (fromIndex in visible.indices && toIndex in visible.indices && fromIndex != toIndex) {
            val backup = items.toList()
            val moving = visible[fromIndex]
            val target = visible[toIndex]
            items.remove(moving)
            val targetIndex = items.indexOf(target)
            items.add(if (toIndex > fromIndex) targetIndex + 1 else targetIndex, moving)
            if (!persistToDisk()) {
                items.clear(); items.addAll(backup)
                return false
            }
        }
        return true
    }

    /** Moves one item inside its own pocket (indices are positions within that pocket); other pockets keep their order. */
    @Synchronized
    fun reorderInPocket(pocketId: String, fromIndex: Int, toIndex: Int): Boolean {
        val inPocket = items.filter { effectivePocketId(it) == pocketId && isVisibleItem(it) }
        if (fromIndex !in inPocket.indices || toIndex !in inPocket.indices || fromIndex == toIndex) return true
        val backup = items.toList()
        val moving = inPocket[fromIndex]
        val target = inPocket[toIndex]
        items.remove(moving)
        val targetIndex = items.indexOf(target)
        items.add(if (toIndex > fromIndex) targetIndex + 1 else targetIndex, moving)
        if (!persistToDisk()) {
            items.clear(); items.addAll(backup)
            return false
        }
        return true
    }

    @Synchronized
    fun updateState(
        id: String,
        state: MaterialPreparationState,
        progress: Float? = null,
        localPath: String? = null,
        errorMessage: String? = null
    ): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            val curr = items[index]
            val updated = curr.copy(
                state = state,
                downloadProgress = progress,
                localFilePath = localPath ?: curr.localFilePath,
                errorMessage = errorMessage
            )
            items[index] = updated
            val success = persistToDisk()
            if (!success) {
                items[index] = curr
                return false
            }
            return true
        }
        return false
    }

    /**
     * 다시 연결: points the SAME reference (same pocket, same position, same id) at a user-chosen replacement.
     * Returns false when the replacement identity already exists in that pocket or saving failed (nothing changed).
     */
    @Synchronized
    fun replaceReference(id: String, replacement: MaterialItem): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val curr = items[index]
        val pocket = effectivePocketId(curr)
        if (items.any { it.id != id && effectivePocketId(it) == pocket && it.sourceIdentity == replacement.sourceIdentity }) return false
        items[index] = replacement.copy(
            id = curr.id,
            pocketId = curr.pocketId,
            addedTimeMillis = curr.addedTimeMillis,
            extras = curr.extras,
            isPrivate = curr.isPrivate,
            state = MaterialPreparationState.READY,
            errorMessage = null,
            downloadProgress = null,
        )
        if (!persistToDisk()) {
            items[index] = curr
            return false
        }
        return true
    }

    @Synchronized
    fun updateBatchStates(updates: Map<String, MaterialPreparationState>): Boolean {
        val backup = items.toList()
        var changed = false
        updates.forEach { (id, state) ->
            val index = items.indexOfFirst { it.id == id }
            if (index >= 0 && items[index].state != state) {
                items[index] = items[index].copy(state = state)
                changed = true
            }
        }
        if (changed) {
            val success = persistToDisk()
            if (!success) {
                items.clear()
                items.addAll(backup)
                return false
            }
        }
        return true
    }

    @Synchronized
    fun clear(): Boolean {
        val backup = items.toList()
        // 보이는 항목만 비운다. 이 모드에서 숨겨진 항목은 그대로 둔다.
        items.removeAll(items.filter(::isVisibleItem).toSet())
        val success = persistToDisk()
        if (!success) {
            items.addAll(backup)
        }
        return success
    }

    // ---- persistence ---------------------------------------------------------------------------------------------

    private fun writeAtomically(fileName: String, text: String): Boolean {
        val dir = storeDir ?: return false
        val target = File(dir, fileName)
        val temp = File(dir, "$fileName.tmp")
        val backup = File(dir, "$fileName.bak")
        return runCatching {
            FileOutputStream(temp).use { fos ->
                fos.write(text.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!temp.exists() || temp.length() == 0L) {
                temp.delete()
                return false
            }
            // Safe atomic replacement with rollback; the previous file stays as .bak until the new one is in place.
            if (target.exists()) {
                if (backup.exists()) backup.delete()
                if (!target.renameTo(backup)) {
                    temp.delete()
                    return false
                }
            }
            if (!temp.renameTo(target)) {
                // Rollback from backup if rename failed
                if (backup.exists() && !target.exists()) {
                    backup.renameTo(target)
                }
                temp.delete()
                return false
            }
            // Success: remove backup
            if (backup.exists()) backup.delete()
            true
        }.getOrDefault(false)
    }

    private fun persistToDisk(): Boolean {
        if (persistBlocked) return false
        return runCatching {
            val array = JSONArray()
            items.forEach { item ->
                val obj = JSONObject()
                // Unknown fields written by another build go back first; known fields below always win.
                item.extras?.let { raw -> runCatching { JSONObject(raw) }.getOrNull()?.let { extra -> extra.keys().forEach { key -> if (key !in knownItemKeys) obj.put(key, extra.get(key)) } } }
                obj.apply {
                    put("id", item.id)
                    put("sourceIdentity", item.sourceIdentity)
                    put("name", item.name)
                    put("sourceType", item.sourceType.name)
                    put("isVideo", item.isVideo)
                    put("uriString", item.uriString)
                    put("localFilePath", item.localFilePath ?: "")
                    put("durationMs", item.durationMs ?: -1L)
                    put("state", item.state.name)
                    put("captureTimeMillis", item.captureTimeMillis)
                    put("addedTimeMillis", item.addedTimeMillis)
                    item.connectionId?.let { put("connectionId", it) }
                    item.remotePath?.let { put("remotePath", it) }
                    item.fileSize?.let { put("fileSize", it) }
                    item.errorMessage?.let { put("errorMessage", it) }
                    put("pocketId", item.pocketId)
                    if (item.entryKind != MaterialEntryKind.MEDIA) put("entryKind", item.entryKind.name)
                    item.origin?.let { put("origin", it) }
                    item.grantPersisted?.let { put("grantPersisted", it) }
                    item.mimeType?.let { put("mimeType", it) }
                    if (item.isPrivate) put("isPrivate", true)
                }
                array.put(obj)
            }
            preservedRawRecords.forEach { array.put(it) }
            writeAtomically(STORE_FILE_NAME, array.toString(2))
        }.getOrDefault(false)
    }

    private fun persistPockets(): Boolean {
        if (pocketsBlocked) return false
        return runCatching {
            val array = JSONArray()
            pocketList.forEach { pocket ->
                val obj = JSONObject()
                pocket.extras?.let { raw -> runCatching { JSONObject(raw) }.getOrNull()?.let { extra -> extra.keys().forEach { key -> if (key !in knownPocketKeys) obj.put(key, extra.get(key)) } } }
                obj.put("id", pocket.id)
                obj.put("name", pocket.name)
                obj.put("createdMillis", pocket.createdMillis)
                if (pocket.isPrivate) obj.put("isPrivate", true)
                array.put(obj)
            }
            writeAtomically(POCKETS_FILE_NAME, array.toString(2))
        }.getOrDefault(false)
    }

    private fun readStoreArray(file: File): JSONArray? =
        if (!file.isFile || file.length() <= 0L) null else runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull()

    private fun loadPocketsFromDisk() {
        val dir = storeDir ?: return
        val file = File(dir, POCKETS_FILE_NAME)
        val backup = File(dir, "$POCKETS_FILE_NAME.bak")
        val array = readStoreArray(file) ?: readStoreArray(backup)
        if (array == null) {
            // A file that exists but cannot be read is NOT "no pockets": keep it untouched and stop pocket changes.
            if ((file.isFile && file.length() > 0L) || (backup.isFile && backup.length() > 0L)) {
                pocketsBlocked = true
                val note = "작업대 포켓 목록 파일을 읽지 못해 덮어쓰지 않도록 포켓 변경을 멈췄습니다. 기존 파일은 그대로 보존됩니다. 앱을 다시 열어 주세요."
                loadProblem = listOfNotNull(loadProblem, note).joinToString(" ")
            }
            return
        }
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            val id = obj?.optString("id").orEmpty()
            if (obj == null || id.isBlank() || !seen.add(id)) {
                // Unreadable pocket entry: its items stay visible in the default pocket; block writes so the entry is not lost.
                pocketsBlocked = true
                loadProblem = listOfNotNull(loadProblem, "포켓 일부를 이 버전에서 읽지 못했습니다. 해당 항목은 ‘$DEFAULT_POCKET_NAME’에 표시되며 원래 포켓 정보는 지우지 않고 보관합니다.").joinToString(" ")
                continue
            }
            val extra = JSONObject()
            obj.keys().forEach { key -> if (key !in knownPocketKeys) extra.put(key, obj.get(key)) }
            pocketList += MaterialPocket(
                id = id,
                name = obj.optString("name").ifBlank { if (id == DEFAULT_POCKET_ID) DEFAULT_POCKET_NAME else "포켓" },
                createdMillis = obj.optLong("createdMillis", 0L),
                extras = if (extra.length() > 0) extra.toString() else null,
                isPrivate = obj.optBoolean("isPrivate", false),
            )
        }
    }

    private fun loadFromDisk() {
        val dir = storeDir ?: return
        val file = File(dir, STORE_FILE_NAME)
        val backup = File(dir, "$STORE_FILE_NAME.bak")
        var array = readStoreArray(file)
        val mainUnreadable = array == null && file.isFile && file.length() > 0L
        if (array == null) {
            val fromBackup = readStoreArray(backup)
            if (fromBackup != null) {
                if (mainUnreadable) {
                    val kept = File(dir, "$STORE_FILE_NAME.unreadable-${System.currentTimeMillis()}")
                    if (!file.renameTo(kept)) {
                        persistBlocked = true
                        loadProblem = "작업대 목록 파일을 읽지 못해 덮어쓰지 않도록 저장을 멈췄습니다. 저장 공간을 확인한 뒤 앱을 다시 열어 주세요."
                        return
                    }
                    loadProblem = "작업대 목록 파일이 손상되어 직전 백업으로 복원했습니다. 손상된 파일은 ${kept.name} 이름으로 보존했습니다."
                }
                if (!backup.renameTo(file)) runCatching { backup.copyTo(file, overwrite = false) }
                array = fromBackup
            } else if (mainUnreadable || (backup.isFile && backup.length() > 0L)) {
                // Nothing readable: keep the files aside, never treat the basket as intentionally empty.
                val keptNames = listOf(file, backup).filter { it.isFile && it.length() > 0L }.map { source ->
                    val kept = File(dir, "${source.name}.unreadable-${System.currentTimeMillis()}")
                    if (source.renameTo(kept)) kept.name else null
                }
                if (keptNames.any { it == null }) {
                    persistBlocked = true
                    loadProblem = "작업대 목록 파일을 읽지 못해 덮어쓰지 않도록 저장을 멈췄습니다. 저장 공간을 확인한 뒤 앱을 다시 열어 주세요."
                } else {
                    loadProblem = "작업대 목록 파일을 읽지 못했습니다. 기존 파일(${keptNames.filterNotNull().joinToString()})은 지우지 않고 보존했으며, 항목을 다시 담을 수 있습니다."
                }
                return
            } else {
                return
            }
        }
        val loadedArray = array ?: return
        val loaded = mutableListOf<MaterialItem>()
        for (i in 0 until loadedArray.length()) {
            val raw = loadedArray.opt(i) ?: continue
            val parsed = (raw as? JSONObject)?.let { obj -> runCatching { parseItem(obj) }.getOrNull() }
            if (parsed == null) preservedRawRecords += raw else loaded += parsed
        }
        items.clear()
        items.addAll(loaded)
        if (preservedRawRecords.isNotEmpty() && loadProblem == null) {
            loadProblem = "재료 ${preservedRawRecords.size}개의 정보를 이 버전에서 읽지 못했습니다. 해당 정보는 지우지 않고 원래 내용 그대로 보관합니다."
        }
    }

    /** Parses one stored entry; returns null for an entry this build cannot use (it is then preserved verbatim). */
    private fun parseItem(obj: JSONObject): MaterialItem? {
        val id = obj.optString("id", UUID.randomUUID().toString())
        val sourceIdentity = obj.optString("sourceIdentity", "")
        if (sourceIdentity.isBlank()) return null
        val name = obj.optString("name", "미디어")
        val rawSourceType = obj.optString("sourceType")
        // An unknown (newer) source type is kept verbatim instead of being rewritten as a local item.
        val sourceType = if (rawSourceType.isBlank()) MaterialSourceType.LOCAL_LIBRARY
            else runCatching { MaterialSourceType.valueOf(rawSourceType) }.getOrNull() ?: return null
        val rawKind = obj.optString("entryKind")
        val entryKind = if (rawKind.isBlank()) MaterialEntryKind.MEDIA
            else runCatching { MaterialEntryKind.valueOf(rawKind) }.getOrNull() ?: return null
        val isVideo = obj.optBoolean("isVideo", false)
        val uriString = obj.optString("uriString", "")
        val localFilePath = obj.optString("localFilePath").takeIf { it.isNotBlank() }
        val durationMs = obj.optLong("durationMs", -1L).takeIf { it >= 0 }
        var state = runCatching { MaterialPreparationState.valueOf(obj.optString("state")) }.getOrDefault(MaterialPreparationState.READY)
        // Interrupted downloading resets to retry-needed (MISSING) on launch
        if (state == MaterialPreparationState.DOWNLOADING) {
            state = MaterialPreparationState.MISSING
        }
        val captureTimeMillis = obj.optLong("captureTimeMillis", 0L)
        val addedTimeMillis = obj.optLong("addedTimeMillis", 0L)
        var connectionId = obj.optString("connectionId").takeIf { it.isNotBlank() }
        var remotePath = obj.optString("remotePath").takeIf { it.isNotBlank() }
        val fileSize = obj.optLong("fileSize", -1L).takeIf { it >= 0 }
        val errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() }

        // Legacy remote recovery from sourceIdentity ("remote:<connectionId>:<remotePath>")
        if (sourceType == MaterialSourceType.REMOTE_FILE) {
            if (connectionId == null || remotePath == null) {
                if (sourceIdentity.startsWith("remote:")) {
                    val parts = sourceIdentity.removePrefix("remote:").split(":", limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                        if (connectionId == null) connectionId = parts[0]
                        if (remotePath == null) remotePath = parts[1]
                    }
                }
            }
        }

        val extra = JSONObject()
        obj.keys().forEach { key -> if (key !in knownItemKeys) extra.put(key, obj.get(key)) }

        return MaterialItem(
            id = id,
            sourceIdentity = sourceIdentity,
            name = name,
            sourceType = sourceType,
            isVideo = isVideo,
            uriString = uriString,
            localFilePath = localFilePath,
            durationMs = durationMs,
            state = state,
            captureTimeMillis = captureTimeMillis,
            addedTimeMillis = addedTimeMillis,
            connectionId = connectionId,
            remotePath = remotePath,
            fileSize = fileSize,
            errorMessage = errorMessage,
            // Records written before pockets existed carry no pocketId: they belong to the default pocket (in memory only).
            pocketId = obj.optString("pocketId").takeIf { it.isNotBlank() } ?: DEFAULT_POCKET_ID,
            entryKind = entryKind,
            origin = obj.optString("origin").takeIf { it.isNotBlank() },
            grantPersisted = if (obj.has("grantPersisted")) obj.optBoolean("grantPersisted") else null,
            extras = if (extra.length() > 0) extra.toString() else null,
            mimeType = obj.optString("mimeType").takeIf { it.isNotBlank() },
            isPrivate = obj.optBoolean("isPrivate", false),
        )
    }
}
