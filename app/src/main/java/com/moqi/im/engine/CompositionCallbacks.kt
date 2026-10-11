package com.moqi.im.engine

import java.util.concurrent.atomic.AtomicLong

/** 清空组合后，即使旧结果已排入 UI 队列，也不能写回候选或宿主输入框。 */
internal class CompositionCallbacks {
    private val generation = AtomicLong()

    fun invalidate() { generation.incrementAndGet() }

    fun <T> capture(callback: (T) -> Unit): (T) -> Unit {
        val expected = generation.get()
        return { value ->
            if (expected == generation.get()) callback(value)
        }
    }
}
