package com.armsone.nasfinder.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 프라이버시 모드: 잠금이 아닌 추가형 가시성. 일반 모드는 공개 기록만, 프라이버시 모드는 공개 + 비공개를 함께 보여 준다.
 * 모드는 메모리에만 있다(저장하지 않음 → 앱을 새로 시작하면 항상 일반 모드). 자동 해제 타이머·PIN·생체·암호화는 없다.
 * 숨김은 이 앱 화면의 표시 단계뿐이며 NAS·클라우드·기기 원본이나 OS 캐시를 보호하지 않는다.
 * 필터는 읽기 투영에서만 쓰고, 저장·삭제·이동·정리는 항상 전체(raw) 기록을 대상으로 한다.
 */
object PrivacyMode {
    const val BRAND_NAME = "Privacy"
    const val HONEST_NOTE =
        "이 앱의 목록에서만 숨깁니다. 잠금이나 암호화가 아니며, NAS·클라우드·기기 원본과 다른 앱·OS 캐시는 바뀌지 않습니다."
    const val PHONE_HARD_CONNECTION_ID = "phonehard"

    private val activeFlow = MutableStateFlow(false)
    private val epochFlow = MutableStateFlow(0)

    /** 화면 갱신용. 값이 바뀌면 화면 트리의 열린 시트·선택·탐색 상태를 비운다. */
    val active: StateFlow<Boolean> = activeFlow.asStateFlow()
    val epoch: StateFlow<Int> = epochFlow.asStateFlow()
    val isActive: Boolean get() = activeFlow.value

    @Volatile private var privateConnectionIds: Set<String> = emptySet()
    @Volatile private var phoneHardFiles: Set<String> = emptySet()
    @Volatile private var phoneHardFolders: List<String> = emptyList()
    @Volatile private var phoneHardIds: Set<String> = emptySet()
    @Volatile private var phoneHardRootPath: String = ""
    // 파일 가져오기·폴더 만들기가 진행되는 동안(분류가 디스크에 확정되기 전) 비공개로 취급할 경로. 작업 중에만 있는 다리이며
    // 최종 분류 결과가 아니다(이동·이름 변경·복사는 파일 시스템을 바꾸기 전에 디스크에 먼저 확정한다). 빈 문자열은 루트 전체.
    private val pendingPrivate = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // 비공개 폴더 표시 파일을 읽을 수 없을 때는 모든 폰하드 경로를 비공개로 본다(fail-closed).
    @Volatile private var phoneHardScopeUnreadable = false

    fun setPhoneHardScopeUnreadable(value: Boolean) { phoneHardScopeUnreadable = value }

    /** [block] 동안 [paths](와 그 하위)를 비공개로 취급한다. 분류가 확정되기 전 잠깐의 공개 노출을 막는다. */
    fun <T> withPendingPrivate(paths: Collection<String>, block: () -> T): T {
        val normalized = paths.map { it.trim('/') }
        pendingPrivate.addAll(normalized)
        try {
            return block()
        } finally {
            pendingPrivate.removeAll(normalized.toSet())
        }
    }

    private fun matchesAny(set: Set<String>, path: String): Boolean =
        set.any { it.isEmpty() || path == it || path.startsWith("$it/") }

    fun setPhoneHardRootPath(absolutePath: String) { phoneHardRootPath = absolutePath.trimEnd('/') }

    /** 폰하드 안의 실제 파일 경로(절대 경로)가 비공개인지. 폰하드 밖의 경로는 false. */
    fun isPhoneHardAbsolutePathPrivate(absolutePath: String?): Boolean {
        val root = phoneHardRootPath
        if (absolutePath == null || root.isEmpty() || !absolutePath.startsWith("$root/")) return false
        return isPhoneHardPathPrivate(absolutePath.removePrefix("$root/"))
    }

    @Synchronized
    fun setActive(value: Boolean) {
        if (activeFlow.value == value) return
        activeFlow.value = value
        epochFlow.value = epochFlow.value + 1
    }

    fun toggle() = setActive(!isActive)

    fun isVisible(isPrivate: Boolean): Boolean = isActive || !isPrivate

    fun setPrivateConnectionIds(ids: Set<String>) { privateConnectionIds = ids }

    /** 폰하드에서 비공개인 저장 경로(파일 자기 플래그)와 비공개 폴더(하위 전체)를 알려 준다. */
    fun setPhoneHardScope(files: Set<String>, folders: Collection<String>, ids: Set<String> = emptySet()) {
        phoneHardFiles = files
        phoneHardFolders = folders.toList()
        phoneHardIds = ids
    }

    /**
     * 작업이 쓰는 출처 키("remote:<연결id>:<경로>", "phonehard:<기록id>")가 비공개 출처인지.
     * 모르는 형식은 공개로 본다(조용히 숨기지 않는다).
     */
    fun isPrivateOriginKey(key: String?): Boolean {
        if (key == null) return false
        if (key.startsWith("phonehard:")) {
            val rest = key.removePrefix("phonehard:").removePrefix("folder:")
            return rest in phoneHardIds || isPhoneHardPathPrivate(rest)
        }
        if (key.startsWith("phonehard-folder:")) return isPhoneHardPathPrivate(key.removePrefix("phonehard-folder:"))
        if (key.startsWith("remote:")) {
            val parts = key.removePrefix("remote:").split(":", limit = 2)
            return parts.isNotEmpty() && isPrivateConnection(parts[0])
        }
        return false
    }

    fun isPrivateConnection(connectionId: String?): Boolean = connectionId != null && connectionId in privateConnectionIds

    /** 폰하드 저장 경로(루트 기준 상대 경로)가 비공개인지. 앱 모드와 무관하다. */
    fun isPhoneHardPathPrivate(storedPath: String): Boolean {
        val path = storedPath.trim('/')
        if (path.isEmpty()) return false
        if (phoneHardScopeUnreadable) return true
        if (path in phoneHardFiles) return true
        if (phoneHardFolders.any { path == it || path.startsWith("$it/") }) return true
        return matchesAny(pendingPrivate, path)
    }

    /** [storedPath] 폴더 아래에 자기 플래그나 비공개 폴더 표시를 가진 하위 항목이 있는지. 재귀 삭제·내보내기 가드에 쓴다. */
    fun hasPhoneHardPrivateDescendant(storedPath: String): Boolean {
        val path = storedPath.trim('/')
        if (phoneHardScopeUnreadable) return true
        val prefix = if (path.isEmpty()) "" else "$path/"
        return phoneHardFiles.any { it.startsWith(prefix) } ||
            phoneHardFolders.any { it.startsWith(prefix) } ||
            pendingPrivate.any { it.startsWith(prefix) }
    }

    /** 이 연결·경로가 비공개 출처인지(모드와 무관). 산출물 origin 고정에 쓴다. */
    fun isPrivateOrigin(connectionId: String?, path: String? = null): Boolean {
        if (connectionId == null) return false
        if (connectionId == PHONE_HARD_CONNECTION_ID) return path != null && isPhoneHardPathPrivate(path)
        return isPrivateConnection(connectionId)
    }

    /** 이 연결·경로를 지금 목록에서 숨겨야 하는지. 프라이버시 모드에서는 숨기지 않는다. */
    fun hides(connectionId: String?, path: String? = null): Boolean = !isActive && isPrivateOrigin(connectionId, path)

    /**
     * 보이는 목록만 바꾼 호출자가 전체 저장 목록을 지우지 않도록, 숨겨진 기록은 원래 자리에 그대로 이어 붙인다.
     * [visibleNew]에 같은 키가 이미 있으면 그쪽이 우선한다(숨겨진 기록을 중복해서 되살리지 않는다).
     */
    fun <T> preservingHidden(raw: List<T>, visibleNew: List<T>, isHidden: (T) -> Boolean, key: (T) -> String): List<T> {
        val merged = visibleNew.toMutableList()
        val present = merged.mapTo(hashSetOf(), key)
        raw.forEachIndexed { index, value ->
            if (isHidden(value) && present.add(key(value))) merged.add(index.coerceAtMost(merged.size), value)
        }
        return merged
    }

    /** 새 작업을 시작하는 순간 한 번 잡아 Job에 저장한다(이후 모드가 바뀌어도 쓰기 대상이 바뀌지 않는다). */
    fun capturedOrigin(inputPrivate: Boolean = false): Boolean = isActive || inputPrivate
}
