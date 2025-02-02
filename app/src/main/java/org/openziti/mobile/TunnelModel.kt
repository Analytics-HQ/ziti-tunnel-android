/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.openziti.tunnel.APIEvent
import org.openziti.tunnel.ContextEvent
import org.openziti.tunnel.Dump
import org.openziti.tunnel.Enroll
import org.openziti.tunnel.Event
import org.openziti.tunnel.Keychain
import org.openziti.tunnel.LoadIdentity
import org.openziti.tunnel.OnOffCommand
import org.openziti.tunnel.RefreshIdentity
import org.openziti.tunnel.Service
import org.openziti.tunnel.ServiceEvent
import org.openziti.tunnel.SetUpstreamDNS
import org.openziti.tunnel.Tunnel
import org.openziti.tunnel.Upstream
import org.openziti.tunnel.ZitiConfig
import org.openziti.tunnel.ZitiID
import org.openziti.tunnel.toPEM
import java.net.URI
import java.security.KeyStore.PrivateKeyEntry
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture

class TunnelModel(
    val tunnel: Tunnel,
    val context: () -> Context
): ViewModel() {
    val Context.prefs: DataStore<Preferences> by preferencesDataStore("tunnel")

    val NAMESERVER = stringPreferencesKey("nameserver")
    val zitiDNS = context().prefs.data.map {
        it[NAMESERVER] ?: "100.64.0.2"
    }
    val RANGE = stringPreferencesKey("range")
    val zitiRange = context().prefs.data.map {
        it[RANGE] ?: "100.64.0.0/10"
    }

    fun setDNS(server: String?, range: String?) = runBlocking {
        context().prefs.edit { settings ->
            settings[NAMESERVER] = server ?: DEFAULT_DNS
            settings[RANGE] = range ?: DEFAULT_RANGE
        }
    }

    data class NetworkStats(val up: Double, val down: Double)
    fun stats(): LiveData<NetworkStats> = stats
    internal val stats = MutableLiveData(NetworkStats(0.0,0.0))

    fun identities(): LiveData<List<TunnelIdentity>> = identitiesData
    private val identitiesData = MutableLiveData<List<TunnelIdentity>>()
    private val identities = mutableMapOf<String, TunnelIdentity>()

    class Factory(private val identity: TunnelIdentity): ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return identity as T
        }
    }

    class TunnelIdentity(
        val id: String,
        private val tunnel: TunnelModel,
        enable: Boolean = true
    ): ViewModel() {

        val zitiID: String =
            with(URI(id)){
                userInfo ?: path?.removePrefix("/")
            } ?: id


        fun name(): LiveData<String> = name
        internal val name = MutableLiveData(id)

        fun status(): LiveData<String> = status
        internal val status = MutableLiveData("Loading")

        internal val controller = MutableLiveData("<controller")
        fun controller() = controller

        private val enabled = MutableLiveData(enable)
        fun enabled(): LiveData<Boolean> = enabled

        private val serviceMap = mutableMapOf<String, Service>()
        private val services = MutableLiveData<List<Service>>()
        fun services(): LiveData<List<Service>> = services

        fun refresh() {
            tunnel.refreshIdentity(id).handleAsync { _, ex ->
                ex?.let {
                    Log.w(TAG, "failed refresh", it)
                }
            }
        }

        fun setEnabled(on: Boolean) {
            if (on)
                Log.i(TAG, "enabling[${name.value}]")
            else
                Log.i(TAG, "disabling[${name.value}]")

            tunnel.enableIdentity(id, on).thenAccept {
                enabled.postValue(on)
                if (on)
                    status.postValue("Enabled")
                else
                    status.postValue("Disabled")
            }
        }

        fun delete() {
            setEnabled(false)
            tunnel.deleteIdentity(id)
        }

        internal fun processServiceUpdate(ev: ServiceEvent) {
            for (s in ev.removedServices) {
                serviceMap.remove(s.id)
            }

            for (s in ev.addedServices) {
                serviceMap[s.id] = s
            }

            services.postValue(serviceMap.values.toList())
        }
    }

    init {
        runBlocking {
            val dns = zitiDNS.first()
            val range = zitiRange.first()
            Log.i("tunnel", "setting dns[$dns] and range[$range]")
            tunnel.setupDNS(dns, range)
            tunnel.start()
        }
        viewModelScope.launch {
            tunnel.events().collect(this@TunnelModel::processEvent)
        }
        viewModelScope.launch {
            while(true) {
                delay(1_000)
                stats.postValue(NetworkStats(tunnel.getUpRate(), tunnel.getDownRate()))
            }
        }

        val aliases = Keychain.store.aliases().toList()

        aliases.filter { it.startsWith("ziti://") }
            .map { Pair(it, Keychain.store.getEntry(it, null)) }
            .filter { it.second is PrivateKeyEntry }
            .map {
                val uri = URI(it.first)
                val ctrl = "https://${uri.host}:${uri.port}"
                val id = uri.userInfo ?: uri.path.removePrefix("/")

                val idCerts = Keychain.store.getCertificateChain(it.first)
                val pem = idCerts.map { it as X509Certificate }
                    .joinToString(transform = X509Certificate::toPEM, separator = "")
                val caCerts = aliases.filter { it.startsWith("ziti:$id/") }
                    .map { Keychain.store.getCertificate(it) as X509Certificate}
                    .joinToString(transform = X509Certificate::toPEM, separator = "")
                it.first to ZitiConfig(
                    controller = ctrl,
                    controllers = listOf(ctrl),
                    id = ZitiID(cert = pem, key = "keychain:${it.first}", ca = caCerts)
                )
            }.forEach {
                loadConfig(it.first, it.second)
            }
    }

    private fun disabledKey(id: String) = booleanPreferencesKey("$id.disabled")

    private fun loadConfig(id: String, cfg: ZitiConfig) {
        val disabled = runBlocking {
            context().prefs.data.map {
                it[disabledKey(id)] ?: false
            }.first()
        }
        Log.i("model", "loading identity[$id] disabled[$disabled]")
        val cmd = LoadIdentity(id, cfg, disabled)
        tunnel.processCmd(cmd).handleAsync { json: JsonElement? , ex: Throwable? ->
            if (ex != null) {
                Log.w("model", "failed to execute", ex)
            } else  {
                identities[id] = TunnelIdentity(id, this, !disabled)
                identitiesData.postValue(identities.values.toList())
                Log.i("model", "load result[$id]: $json")
            }
        }
    }

    private fun processEvent(ev: Event) {
        val tunnelIdentity = identities[ev.identifier]
        when(ev) {
            is ContextEvent -> {
                tunnelIdentity?.apply {
                    name.postValue(ev.name)
                    controller.postValue(ev.controller)
                    if (ev.status == "OK") {
                        tunnelIdentity.status.postValue("Active")
                    } else {
                        tunnelIdentity.status.postValue(ev.status)
                    }
                }
            }
            is ServiceEvent -> {
                tunnelIdentity?.processServiceUpdate(ev)
            }
            is APIEvent -> {

            }
            else -> {
                Log.i("model", "received event[$ev]")
            }
        }
    }

    fun setUpstreamDNS(servers: List<String>): CompletableFuture<Unit> {
        val cmd = SetUpstreamDNS(servers.map { Upstream(it) })
        return tunnel.processCmd(cmd).thenApply {  }
    }

    fun enroll(jwt: String): CompletableFuture<ZitiConfig?>  {
        val cmd = Enroll(
            jwt = jwt,
            useKeychain = true)
        val future = tunnel.processCmd(cmd).thenApply {
            Json.decodeFromJsonElement<ZitiConfig>(it!!)
        }

        future.thenApply { cfg ->
            val keyAlias = cfg.id.key.removePrefix("keychain:")
            Keychain.updateKeyEntry(keyAlias, cfg.id.cert, cfg.id.ca)
            loadConfig(keyAlias, cfg)
        }.exceptionally {
            Log.e("model", "enrollment failed", it)
        }
        return future
    }

    fun dumpIdentity(id: String): CompletableFuture<String> =
        tunnel.processCmd(Dump(id)).thenApply { json ->
            if (json is JsonObject && json[id] is JsonPrimitive) {
                json[id]?.jsonPrimitive?.content
            } else {
                json?.toString() ?: "no data"
            }
        }

    private fun refreshIdentity(id: String) =
        tunnel.processCmd(RefreshIdentity(id)).thenAccept{}

    private fun enableIdentity(id: String, on: Boolean): CompletableFuture<Unit> {
        val disabledKey = disabledKey(id)
        runBlocking {
            context().prefs.edit {
                it[disabledKey] = !on
            }
        }
        return tunnel.processCmd(OnOffCommand(id, on)).thenApply {}
    }

    private fun deleteIdentity(identifier: String) {
        identities.remove(identifier)
        identitiesData.postValue(identities.values.toList())

        val uri = URI(identifier)
        val id = uri.userInfo ?: uri.path.removePrefix("/")

        runCatching {
            Keychain.store.deleteEntry(identifier)
        }.onFailure {
            Log.w(TAG, "failed to remove entry", it)
        }

        val caCerts = Keychain.store.aliases().toList().filter { it.startsWith("ziti:$id/") }
        caCerts.forEach {
            Keychain.store.runCatching {
                deleteEntry(it)
            }
        }

    }

    companion object {
        const val TAG = "tunnel-model"
        const val DEFAULT_DNS = "100.64.0.2"
        const val DEFAULT_RANGE = "100.64.0.0/10"
    }
}