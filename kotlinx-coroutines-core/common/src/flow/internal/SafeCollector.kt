/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.flow.internal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.internal.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

internal class SafeCollector<T>(
    private val collector: FlowCollector<T>,
    private val collectContext: CoroutineContext
) : FlowCollector<T>, Continuation<Unit> {

    // Note, it is non-capturing lambda, so no extra allocation during init of SafeCollector
    private val collectContextSize = collectContext.fold(0) { count, _ -> count + 1 }
    private var lastEmissionContext: CoroutineContext? = null
    private val emitFun = run {
        val suspendFun: suspend (T) -> Unit = { collector.emit(it) }
        suspendFun as Function2<T, Continuation<Unit>, Any?>
    }

    private var caller: Continuation<Unit>? = null // lateinit

    override val context: CoroutineContext
        get() = caller?.context ?: EmptyCoroutineContext

    override fun resumeWith(result: Result<Unit>) {
        val completion = caller!!
        completion.resumeWith(result)
    }
//
    override suspend fun emit(value: T): Unit = suspendCoroutineUninterceptedOrReturn<Unit> sc@{
        caller = it
        emitFun(value, this)
    }


//    override suspend fun emit(value: T): Unit {
//        collector.emit(value)
////        emitFun(value, this)
//    }

    private fun checkContext(currentContext: CoroutineContext) {
        val result = currentContext.fold(0) fold@{ count, element ->
            val key = element.key
            val collectElement = collectContext[key]
            if (key !== Job) {
                return@fold if (element !== collectElement) Int.MIN_VALUE
                else count + 1
            }

            val collectJob = collectElement as Job?
            val emissionParentJob = (element as Job).transitiveCoroutineParent(collectJob)
            /*
             * Things like
             * ```
             * coroutineScope {
             *     launch {
             *         emit(1)
             *     }
             *
             *     launch {
             *         emit(2)
             *     }
             * }
             * ```
             * are prohibited because 'emit' is not thread-safe by default. Use channelFlow instead if you need concurrent emission
             * or want to switch context dynamically (e.g. with `withContext`).
             *
             * Note that collecting from another coroutine is allowed, e.g.:
             * ```
             * coroutineScope {
             *     val channel = produce {
             *         collect { value ->
             *             send(value)
             *         }
             *     }
             *     channel.consumeEach { value ->
             *         emit(value)
             *     }
             * }
             * ```
             * is a completely valid.
             */
            if (emissionParentJob !== collectJob) {
                error(
                    "Flow invariant is violated:\n" +
                        "\t\tEmission from another coroutine is detected.\n" +
                        "\t\tChild of $emissionParentJob, expected child of $collectJob.\n" +
                        "\t\tFlowCollector is not thread-safe and concurrent emissions are prohibited.\n" +
                        "\t\tTo mitigate this restriction please use 'channelFlow' builder instead of 'flow'"
                )
            }

            /*
             * If collect job is null (-> EmptyCoroutineContext, probably run from `suspend fun main`), then invariant is maintained
             * (common transitive parent is "null"), but count check will fail, so just do not count job context element when
             * flow is collected from EmptyCoroutineContext
             */
            if (collectJob == null) count else count + 1
        }
        if (result != collectContextSize) {
            error(
                "Flow invariant is violated:\n" +
                    "\t\tFlow was collected in $collectContext,\n" +
                    "\t\tbut emission happened in $currentContext.\n" +
                    "\t\tPlease refer to 'flow' documentation or use 'flowOn' instead"
            )
        }
    }

    private tailrec fun Job?.transitiveCoroutineParent(collectJob: Job?): Job? {
        if (this === null) return null
        if (this === collectJob) return this
        if (this !is ScopeCoroutine<*>) return this
        return parent.transitiveCoroutineParent(collectJob)
    }
}

/**
 * An analogue of the [flow] builder that does not check the context of execution of the resulting flow.
 * Used in our own operators where we trust the context of invocations.
 */
@PublishedApi
internal inline fun <T> unsafeFlow(@BuilderInference crossinline block: suspend FlowCollector<T>.() -> Unit): Flow<T> {
    return object : Flow<T> {
        override suspend fun collect(collector: FlowCollector<T>) {
            collector.block()
        }
    }
}
