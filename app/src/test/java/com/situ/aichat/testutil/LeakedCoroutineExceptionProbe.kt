package com.situ.aichat.testutil

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * 【测试专用·跨测试协程异常泄漏的观测点】给全量单测装一个**只看不管**的协程全局异常处理器：
 * 凡「协程根作用域里没有 CoroutineExceptionHandler、异常无人接」的泄漏，都在这里带
 * `###COROUTINE-LEAK###` 标记打出栈——泄漏源当场点名，不必靠二分法碰运气。
 *
 * **为什么值得常驻**：这类泄漏是**潜伏**的。异常从泄漏源逃逸后，会被 kotlinx-coroutines-test 的
 * `ExceptionCollector` 攒着，**记到下一条 `runTest` 头上**（报 `UncaughtExceptionsBeforeTest`，
 * 那条测试纯属躺枪）。本模块单 JVM 顺序跑（未设 `maxParallelForks`），测试类一增删执行序就变，
 * 泄漏源与受害者的配对随之漂移——甚至泄漏发生在最后一条测试之后就无人受害、全量假绿。
 * 装了这个观测点，泄漏无论当下有没有害死人都会现形，且经 Gradle 的 per-class stderr 落进
 * `app/build/test-results/testDebugUnitTest/TEST-<泄漏源类>.xml`，天然带类名归属。
 * 详见 docs/playbook/PITFALLS.md §1e。
 *
 * **原理（别改成「注册单个 handler」）**：kotlinx.coroutines 的 `handleUncaughtCoroutineException`
 * 会**遍历** ServiceLoader 装的所有处理器（`platformExceptionHandlers` 是个 Collection），逐个调用；
 * `ExceptionCollector` 自己正是这样挂进去的。所以本类是**叠加**的，不会顶掉/干扰它的既有行为。
 * 注册表 = `app/src/test/resources/META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler`
 * （测试资源·绝不进 App）。
 *
 * 用法：全量跑完 `grep -rl COROUTINE-LEAK app/build/test-results/testDebugUnitTest/` → 命中的 XML
 * 文件名就是泄漏源测试类。
 */
class LeakedCoroutineExceptionProbe :
    AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        System.err.println("###COROUTINE-LEAK### thread=${Thread.currentThread().name} ctx=$context")
        exception.printStackTrace()
        System.err.println("###COROUTINE-LEAK-END###")
    }
}
