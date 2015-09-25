/*
 Copyright (C) 2015 Electronic Arts Inc.  All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:

 1.  Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.
 2.  Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.
 3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
     its contributors may be used to endorse or promote products derived
     from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
 EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
 DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.ea.orbit.async.test;

import com.ea.orbit.concurrent.Task;

import org.junit.Ignore;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.stream.Stream;

import static com.ea.orbit.async.Await.await;
import static com.ea.orbit.concurrent.Task.fromValue;
import static junit.framework.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SynchronizedTest extends BaseTest
{
    @Test
    public void outOfTheWay()
    {
        class Experiment
        {
            int x;

            Task doIt(Object mutex, int a)
            {
                synchronized (mutex)
                {
                    x = 1;
                    mutex.notify();
                }
                await(getBlockedTask());
                return fromValue(x + a);
            }
        }
        final Task res = new Experiment().doIt(new Object(), 1);
        completeFutures();
        assertEquals(2, res.join());
    }

    @Test
    public void inThePath()
    {
        class Experiment
        {
            int x;

            Task doIt(Object mutex, int a)
            {
                synchronized (mutex)
                {
                    mutex.notify();
                    x = 1;
                    await(getBlockedTask());
                    mutex.notify();
                }
                return fromValue(x + a);
            }
        }
        final Task res = new Experiment().doIt(new Object(), 1);
        completeFutures();
        assertEquals(2, res.join());
    }

    @Test
    public void twoMutexes()
    {
        class Experiment
        {
            int x;

            Task doIt(Object mutex1, Object mutex2, int a)
            {
                synchronized (mutex1)
                {
                    mutex1.notify();
                    synchronized (mutex2)
                    {
                        x = 1;
                        mutex1.notify();
                        mutex2.notify();
                        await(getBlockedTask());
                        mutex1.notify();
                        mutex2.notify();
                    }
                    mutex1.notify();
                }
                return fromValue(x + a);
            }
        }
        final Task res = new Experiment().doIt("a", "b", 1);
        completeFutures();
        assertEquals(2, res.join());
    }

    @Test
    public void usingThis()
    {
        class Experiment
        {
            int x;

            Task doIt(int a)
            {
                synchronized (this)
                {
                    x = 1;
                    this.notify();
                    await(getBlockedTask());
                    this.notify();
                }
                return fromValue(x + a);
            }
        }
        final Task res = new Experiment().doIt(0);
        completeFutures();
        assertEquals(1, res.join());
    }

    @Test
    public void synchronizedMethod()
    {
        class SynchronizedMethodExperiment
        {
            int x;

            synchronized Task doIt(int a)
            {
                x = 1;
                this.notify();
                await(getBlockedTask());
                this.notify();
                return fromValue(x);
            }
        }
        final Task res = new SynchronizedMethodExperiment().doIt(0);
        Method asyncMethod = Stream.of(SynchronizedMethodExperiment.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("async$"))
                .findFirst().orElse(null);
        completeFutures();
        assertEquals(1, res.join());

        // it must be false since the async method is static
        assertFalse(Modifier.isSynchronized(asyncMethod.getModifiers()));
        assertTrue(Modifier.isStatic(asyncMethod.getModifiers()));
    }

    @Test
    public void synchronizedMethodWithExtraSync()
    {
        class SynchronizedMethodWithExtraSyncExperiment
        {
            int x;

            synchronized Task doIt(int a)
            {
                x = 1;
                this.notify();
                await(getBlockedTask());
                synchronized (this)
                {
                    await(getBlockedTask());
                    this.notify();
                }
                this.notify();
                await(getBlockedTask());
                this.notify();
                return fromValue(x);
            }
        }
        final Task res = new SynchronizedMethodWithExtraSyncExperiment().doIt(0);
        completeFutures();
        assertEquals(1, res.join());
    }


    @Test
    public void staticSynchronizedMethod()
    {

        final Task res = StaticSynchronizedMethod_Experiment.doIt(getBlockedTask(), 1);
        Method asyncMethod = Stream.of(StaticSynchronizedMethod_Experiment.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("async$"))
                .findFirst().orElse(null);
        completeFutures();
        assertEquals(1, res.join());
        // this is not strictly necessary
        assertTrue(Modifier.isSynchronized(asyncMethod.getModifiers()));
    }

    static class StaticSynchronizedMethod_Experiment
    {
        static synchronized Task doIt(Task blocker, int a)
        {
            StaticSynchronizedMethod_Experiment.class.notify();
            await(blocker);
            StaticSynchronizedMethod_Experiment.class.notify();
            return fromValue(a);
        }
    }

    @Test
    public void mixed()
    {
        class MixedExperiment
        {
            int x;

            synchronized Task doIt(String mutex1, String mutex2, int a)
            {
                String mutex3 = "c";
                x = 1;
                this.notify();
                await(getBlockedTask());
                this.notify();
                synchronized (mutex1)
                {
                    await(getBlockedTask());
                    this.notify();
                    mutex1.notify();
                }
                synchronized (mutex2)
                {
                    await(getBlockedTask());
                    this.notify();
                    mutex2.notify();
                }
                synchronized (mutex1)
                {
                    synchronized (mutex2)
                    {
                        synchronized (mutex3)
                        {
                            await(getBlockedTask());
                            this.notify();
                            mutex1.notify();
                            mutex2.notify();
                            mutex3.notify();
                        }
                    }
                }
                this.notify();
                await(getBlockedTask());
                this.notify();
                return fromValue(x);
            }
        }
        final Task res = new MixedExperiment().doIt("a", "b", 0);
        completeFutures();
        assertEquals(1, res.join());
    }

}
