package com.hiweb.hiwebremotedesk.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.DeviceRepository
import com.hiweb.hiwebremotedesk.data.FoundHost
import com.hiweb.hiwebremotedesk.data.LinuxSsh
import com.hiweb.hiwebremotedesk.data.NetworkScanner
import com.hiweb.hiwebremotedesk.data.OsType
import com.hiweb.hiwebremotedesk.data.PcStateStore
import com.hiweb.hiwebremotedesk.data.PcStatus
import com.hiweb.hiwebremotedesk.data.PcStatusRules
import com.hiweb.hiwebremotedesk.data.ReachabilityMonitor
import com.hiweb.hiwebremotedesk.data.SshPowerResult
import com.hiweb.hiwebremotedesk.data.ThumbnailStore
import com.hiweb.hiwebremotedesk.rdp.PowerAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface DeviceFilter {
    data object All : DeviceFilter
    data object Online : DeviceFilter
    data object Offline : DeviceFilter
    data class Group(val os: OsType) : DeviceFilter
    data class Single(val id: String) : DeviceFilter
}

enum class SortOrder(val title: String) { NAME("По имени"), STATUS("Сначала онлайн") }

sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val done: Int, val total: Int) : ScanState
    data class Done(val hosts: List<FoundHost>, val error: String? = null) : ScanState
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    val repo = DeviceRepository.get(app)
    val thumbnails = ThumbnailStore.get(app)
    private val monitor = ReachabilityMonitor(viewModelScope)
    private val sshPrefs = app.getSharedPreferences("ssh_hosts", Context.MODE_PRIVATE)
    private val ssh = LinuxSsh(
        pinnedKey = { sshPrefs.getString(it, null) },
        pinKey = { host, fp -> sshPrefs.edit().putString(host, fp).apply() },
    )

    val devices = repo.devices
    private val pcStates = PcStateStore.get(app)

    private val _filter = MutableStateFlow<DeviceFilter>(DeviceFilter.All)
    val filter = _filter.asStateFlow()
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()
    private val _sort = MutableStateFlow(SortOrder.NAME)
    val sort = _sort.asStateFlow()
    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan = _scan.asStateFlow()
    private var scanJob: Job? = null

    /** ПК, агент которых сейчас на связи по локальной сети, и доступные на них действия питания. */
    val agents = com.hiweb.hiwebremotedesk.agent.AgentHub.presence

    /** Журнал подключений и угрозы по каждому ПК (присылает HiWeb Agent). */
    val guard = com.hiweb.hiwebremotedesk.data.GuardStore.get(app).data

    // Правила состояния зависят от времени (свежесть сообщения агента, долгая перезагрузка): пересчёт раз в 5 с.
    private val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(5_000) } }

    /** Состояние каждого ПК для карточек: проверка порта RDP, агент на связи и его последнее сообщение. */
    val status: StateFlow<Map<String, PcStatus>> =
        combine(repo.devices, monitor.status, agents, pcStates.reports, ticker) { list, ports, linked, reports, _ ->
            val now = System.currentTimeMillis()
            list.associate { d -> d.id to PcStatusRules.derive(ports[d.id], d.id in linked, reports[d.id], now) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        monitor.start { repo.devices.value }
    }

    fun setFilter(f: DeviceFilter) { _filter.value = f }
    fun setQuery(q: String) { _query.value = q }
    fun setSort(s: SortOrder) { _sort.value = s }

    fun refreshStatus() {
        viewModelScope.launch { monitor.refresh(repo.devices.value) }
    }

    fun save(device: Device) {
        repo.upsert(device)
        refreshStatus()
    }

    fun delete(id: String) {
        repo.delete(id)
        thumbnails.delete(id)
        if ((_filter.value as? DeviceFilter.Single)?.id == id) _filter.value = DeviceFilter.All
    }

    /** Команда питания или выхода по SSH (Linux); результат приходит в потоке интерфейса. */
    fun powerViaSsh(device: Device, action: PowerAction, onResult: (SshPowerResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ssh.run(device.host, device.username, device.password, action) }
                    .getOrElse { SshPowerResult.Failed(it.message ?: "Не удалось выполнить команду") }
            }
            onResult(result)
        }
    }

    /** Выключение, перезагрузка, сон или гибернация через HiWeb Agent; [onResult] получает null при успехе. */
    fun powerViaAgent(device: Device, action: com.hiweb.hiwebremotedesk.agent.AgentPower, onResult: (String?) -> Unit) {
        com.hiweb.hiwebremotedesk.agent.AgentHub.requestPower(device.id, action) { error ->
            // Агент принял команду: карточка сразу показывает, куда уходит ПК, не дожидаясь его сообщения.
            if (error == null) pcStates.record(device.id, action.wire)
            viewModelScope.launch { onResult(error) }
        }
    }

    fun startScan() {
        scanJob?.cancel()
        _scan.value = ScanState.Running(0, 254)
        scanJob = viewModelScope.launch {
            try {
                val known = repo.devices.value.map { it.host }.toSet()
                val found = NetworkScanner.scan(getApplication()) { done, total -> _scan.value = ScanState.Running(done, total) }
                _scan.value = ScanState.Done(found.filter { it.ip !in known })
            } catch (e: Exception) {
                _scan.value = ScanState.Done(emptyList(), e.message ?: "Не удалось выполнить поиск")
            }
        }
    }

    fun finishScan() {
        scanJob?.cancel()
        _scan.value = ScanState.Idle
    }

    fun addFound(hosts: List<FoundHost>) {
        hosts.forEach {
            val p = it.profile
            repo.upsert(
                Device(
                    id = repo.newId(), name = it.name, host = it.ip, port = p.port,
                    os = p.os,
                ),
            )
        }
        refreshStatus()
    }
}
