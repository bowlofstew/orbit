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

import org.junit.Test;

import java.util.concurrent.CompletableFuture;

import static com.ea.orbit.async.Await.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class HowItShouldWorkTest
{
    private static class WhatWillBeWritten
    {
        // @Async
        public CompletableFuture<Object> doSomething(CompletableFuture<String> blocker)
        {
            String res = await(blocker);
            return CompletableFuture.completedFuture(":" + res);
        }
    }

    private static class HowItShouldBehave
    {
        public CompletableFuture<Object> doSomething(CompletableFuture<String> blocker)
        {
            return blocker.thenApply(res -> ":" + res);
        }
    }

    private static class HowItShouldBeInstrumented
    {
        public CompletableFuture<Object> doSomething(CompletableFuture<String> blocker)
        {
            if (!blocker.isDone())
            {
                return blocker.exceptionally(t -> null)
                        .thenCompose(t -> this.async$doSomething(blocker, 1, t));
            }
            String res = (String) blocker.join();
            return CompletableFuture.completedFuture(":" + res);
        }

        public CompletableFuture<Object> async$doSomething(CompletableFuture<String> blocker, int async$state, Object async$result)
        {
            String res;
            switch (async$state)
            {
                case 1:
                    if (!blocker.isDone())
                    {
                        return blocker.exceptionally(t -> null)
                                .thenCompose(t -> this.async$doSomething(blocker, 1, t));
                    }
                    res = blocker.join();
                    return CompletableFuture.completedFuture(":" + res);
                default:
                    throw new IllegalArgumentException();
            }
        }
    }

    @Test
    public void testMockInstrumentation()
    {
        CompletableFuture<String> blocker = new CompletableFuture<>();
        final CompletableFuture<Object> res = new HowItShouldBeInstrumented().doSomething(blocker);
        assertFalse(blocker.isDone());
        assertFalse(res.isDone());
        blocker.complete("x");
        assertEquals(":x", res.join());
    }

    @Test
    public void testHowItShouldBehave()
    {
        CompletableFuture<String> blocker = new CompletableFuture<>();
        final CompletableFuture<Object> res = new HowItShouldBehave().doSomething(blocker);
        assertFalse(blocker.isDone());
        assertFalse(res.isDone());
        blocker.complete("x");
        assertEquals(":x", res.join());
    }
}
