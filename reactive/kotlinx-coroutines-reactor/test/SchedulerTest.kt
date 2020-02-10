/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.reactor

import kotlinx.coroutines.*
import org.junit.Before
import org.junit.Test
import reactor.core.scheduler.Schedulers

class SchedulerTest : TestBase() {
    @Before
    fun setup() {
        ignoreLostThreads("single-")
    }

    @Test
    fun testSingleScheduler(): Unit = runBlocking {
        expect(1)
        val mainThread = Thread.currentThread()
        withContext(Schedulers.single().asCoroutineDispatcher()) {
            val t1 = Thread.currentThread()
            assert(t1 != mainThread)
            expect(2)
            delay(100)
            val t2 = Thread.currentThread()
            assert(t2 != mainThread)
            expect(3)
        }
        finish(4)
    }
}
