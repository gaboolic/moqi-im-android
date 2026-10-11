package com.moqi.im.keyboard

/** 普通快打按落指顺序结算，已结算指针的抬起不会再次产生按键。 */
internal class PointerRollover {
    var activeId: Int? = null
        private set

    fun down(id: Int, exclusive: Boolean, finish: () -> Unit, begin: () -> Unit) {
        if (activeId == id || (activeId != null && exclusive)) return
        if (activeId != null) finish()
        activeId = id
        begin()
    }

    fun up(id: Int, finish: () -> Unit) {
        if (activeId != id) return
        activeId = null
        finish()
    }

    fun cancel(cancelGesture: () -> Unit) {
        activeId = null
        cancelGesture()
    }
}
