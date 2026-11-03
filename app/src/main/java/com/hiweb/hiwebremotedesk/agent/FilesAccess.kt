package com.hiweb.hiwebremotedesk.agent

/** Можно ли открыть файлы ПК с планшета прямо сейчас; по нему кнопка «Файлы» на карточке и её подсказка. */
enum class FilesAccess {
    /** Агент ПК не на связи (ПК выключен, спит или вне сети). */
    OFFLINE,

    /** Агент на связи, но старше 1.11 и файлов не умеет. */
    OLD_AGENT,

    /** Агент умеет, но на ПК доступ выключен или не открыто ни одной папки. */
    DISABLED,

    /** Открыта хотя бы одна папка: планшет видит её как диск. */
    READY,
    ;

    companion object {
        fun of(presence: AgentPresence?): FilesAccess {
            if (presence == null) return OFFLINE
            val files = presence.files ?: return OLD_AGENT
            return if (files.enabled && files.shares.isNotEmpty()) READY else DISABLED
        }
    }
}

/**
 * «Диски» ПК для системного файлового менеджера: по одному на каждую открытую папку каждого ПК, который сейчас на
 * связи. Чистая логика под юнит-тестами; [PcDocumentsProvider.queryRoots] только переводит её в курсор.
 */
object PcRoots {
    class Root(val doc: PcDocId, val title: String, val summary: String, val write: Boolean)

    /** [nameOf] даёт название ПК в приложении; ПК, которого в списке уже нет, пропускается. Порядок: по ПК, затем как у агента. */
    fun list(presence: Map<String, AgentPresence>, nameOf: (String) -> String?): List<Root> = buildList {
        for ((deviceId, p) in presence.entries.sortedBy { nameOf(it.key)?.lowercase() ?: it.key }) {
            val files = p.files ?: continue
            if (!files.enabled) continue
            val pcName = nameOf(deviceId) ?: continue
            for (share in files.shares) {
                add(Root(PcDocId.root(deviceId, share.id), share.name, if (share.write) pcName else "$pcName · только чтение", share.write))
            }
        }
    }
}
