package com.hebowiz.speakerarray.core

/** Android AudioDeviceInfo TYPE_* values; no nullable endpoint may bypass validation. */
object RoutePolicy {
    fun bluetooth(type: Int) = type in setOf(7,8,23,26,27,30)
    fun mediaBluetooth(type: Int) = type in setOf(8,23,26,27,30)
    fun localMicrophone(type: Int) = type in setOf(15,3,11,12,22)
    fun external(type: Int?) = type!=null && type !in setOf(1,2,24)
    fun label(type: Int) = when(type) {
        1,2,15,24 -> "内蔵"
        3,4 -> "有線"
        11,12,22 -> "USB"
        7 -> "Bluetooth通話"
        8,23,26,27,30 -> "Bluetoothメディア"
        else -> "その他"
    }
    fun reason(output: Int, input: Int): String? = when {
        output==7 -> "Bluetooth通話出力は2ch自動設計の対象外です"
        mediaBluetooth(output) && !localMicrophone(input) -> "BT出力時は本体MICまたは有線MICを選択してください（BT入力・未知の入力は使用不可）"
        else -> null
    }
    fun requireAllowed(output: Int,input: Int) {require(reason(output,input)==null) {reason(output,input)!!}}
}
