package com.hebowiz.speakerarray

import android.content.*
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hebowiz.speakerarray.core.EndpointKey
import java.io.Closeable

fun AudioDeviceInfo.key() = EndpointKey(type,productName.toString(),if(Build.VERSION.SDK_INT>=28) address else "")
fun AudioDeviceInfo.description() = "${productName} / type=$type / id=$id"

class DeviceRoutes(context: Context, private val changed: () -> Unit) : Closeable {
    val manager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val callback=object: AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()
    }
    init { manager.registerAudioDeviceCallback(callback,Handler(Looper.getMainLooper())) }
    fun devices(input: Boolean): List<AudioDeviceInfo> = manager.getDevices(if(input) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS).toList()
    fun resolve(key: EndpointKey?, input: Boolean): AudioDeviceInfo {
        val devices=devices(input)
        val candidates=if(key==null) devices.filter {it.type==if(input) AudioDeviceInfo.TYPE_BUILTIN_MIC else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}
            else devices.filter {it.key()==key}
        require(candidates.size==1) { if(candidates.isEmpty()) "指定デバイスが見つかりません" else "同名デバイスが複数あります。識別可能な端点を選んでください" }
        return candidates.single()
    }
    override fun close() = manager.unregisterAudioDeviceCallback(callback)
}

/** Owns exactly one communication request. Never changes the selected media output. */
@Suppress("DEPRECATION")
class CommunicationLease(private val context: Context, val input: AudioDeviceInfo) : Closeable {
    private val manager=context.getSystemService(AudioManager::class.java)
    private val previousMode=manager.mode
    private val previousDevice=if(Build.VERSION.SDK_INT>=31) manager.communicationDevice else null
    private var requested=false
    private var receiver: BroadcastReceiver?=null
    @Volatile private var scoConnected=false
    val hfp=input.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    private var target: AudioDeviceInfo?=null
    fun request() {
        if(!hfp) return
        require(previousMode==AudioManager.MODE_NORMAL) { "通話・他アプリの通信モード中は測定できません" }
        requested=true;manager.mode=AudioManager.MODE_IN_COMMUNICATION
        if(Build.VERSION.SDK_INT>=31) {
            val candidates=manager.availableCommunicationDevices.filter {it.type==input.type &&
                (if(input.key().address.isNotEmpty()) it.key().address==input.key().address else it.productName.toString()==input.productName.toString())}
            require(candidates.size==1) { "HFP通信端点を一意に特定できません" }
            target=candidates.single()
            check(manager.setCommunicationDevice(target!!)) { "HFP通信デバイスの要求が拒否されました" }
        } else {
            val r=object: BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    scoConnected=intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,-1)==AudioManager.SCO_AUDIO_STATE_CONNECTED
                }
            }
            receiver=r
            val sticky=context.registerReceiver(r,IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            r.onReceive(context,sticky);manager.startBluetoothSco()
        }
    }
    fun ready() = !hfp || if(Build.VERSION.SDK_INT>=31) manager.communicationDevice?.id==target?.id else scoConnected
    override fun close() {
        try {
            if(requested) {
                requested=false
                try {
                    if(Build.VERSION.SDK_INT>=31) { if(previousDevice!=null) manager.setCommunicationDevice(previousDevice) else manager.clearCommunicationDevice() }
                    else manager.stopBluetoothSco()
                } finally {manager.mode=previousMode}
            }
        } finally {receiver?.let {runCatching {context.unregisterReceiver(it)}};receiver=null}
    }
}
