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

package com.ea.orbit.actors.test;

import com.ea.orbit.actors.Addressable;
import com.ea.orbit.actors.Stage;
import com.ea.orbit.actors.extensions.InvocationContext;
import com.ea.orbit.actors.extensions.InvokeHookExtension;
import com.ea.orbit.actors.extensions.LifetimeExtension;
import com.ea.orbit.actors.runtime.AbstractActor;
import com.ea.orbit.actors.runtime.Execution;
import com.ea.orbit.actors.runtime.ExecutionSerializer;
import com.ea.orbit.actors.runtime.cloner.ExecutionObjectCloner;
import com.ea.orbit.actors.runtime.cloner.KryoCloner;
import com.ea.orbit.concurrent.ExecutorUtils;
import com.ea.orbit.concurrent.Task;
import com.ea.orbit.exception.UncheckedException;
import com.ea.orbit.injection.DependencyRegistry;

import org.apache.commons.logging.impl.SimpleLog;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import com.google.common.util.concurrent.ForwardingExecutorService;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.Assert.fail;

@SuppressWarnings("VisibilityModifierCheck")
public class ActorBaseTest
{
    protected String clusterName = "cluster." + Math.random() + "." + getClass().getSimpleName();
    protected FakeClock clock = new FakeClock();
    protected ConcurrentHashMap<Object, Object> fakeDatabase = new ConcurrentHashMap<>();
    protected static final ExecutorService commonPool = new ForwardingExecutorService()
    {
        ExecutorService delegate = ExecutorUtils.newScalingThreadPool(200);

        @Override
        protected ExecutorService delegate()
        {
            return delegate;
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException
        {
            // return immediately.
            return true;
        }

        @Override
        public void shutdown()
        {
            try
            {
                // Attention: intentionally not calling delegate.shutdown() to keep reusing it for other tests.
                delegate.awaitTermination(0, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                throw new UncheckedException(e);
            }
        }
    };
    protected FakeSync fakeSync = new FakeSync();

    protected final StringBuilder hiddenLogData = new StringBuilder();
    protected final SimpleLog hiddenLog = new SimpleLog("orbit")
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected synchronized void write(final StringBuffer buffer)
        {
            // truncating the log
            if (hiddenLogData.length() > 250e6)
            {
                hiddenLogData.setLength(64000);
                hiddenLogData.append("The log was truncated!").append("\r\n");
            }
            hiddenLogData.append(buffer).append("\r\n");
        }
    };
    @Rule
    public TestRule dumpLogs = new TestWatcher()
    {
        @Override
        protected void failed(final Throwable e, final Description description)
        {
            final PrintStream out = System.out;
            out.println(">>>>>>>>> Start");
            out.println(">>>>>>>>> Test Dump for " + description);
            out.println(">>>>>>>>> Error: " + e);
            out.println(hiddenLogData.toString());
            out.println(">>>>>>>>> Test Dump for " + description);
            out.print(">>>>>>>>> Error: ");
            e.printStackTrace(out);
            out.println(">>>>>>>>> End");
        }
    };

    public Stage createClient() throws ExecutionException, InterruptedException
    {
        Stage client = new Stage();
        DependencyRegistry dr = new DependencyRegistry();
        dr.addSingleton(FakeSync.class, fakeSync);
        addLogging(client);
        client.addExtension(new LifetimeExtension()
        {
            @Override
            public Task<?> preActivation(final AbstractActor<?> actor)
            {
                dr.inject(actor);
                return Task.done();
            }
        });
        client.setMode(Stage.StageMode.FRONT_END);
        client.setExecutionPool(commonPool);
        client.setMessagingPool(commonPool);
        client.setClock(clock);
        client.setClusterName(clusterName);
        client.setClusterPeer(new FakeClusterPeer());
        client.start().join();
        client.bind();
        return client;
    }

    public Stage createStage() throws ExecutionException, InterruptedException
    {
        Stage stage = new Stage();
        DependencyRegistry dr = initDependencyRegistry();
        dr.addSingleton(Stage.class, stage);
        addLogging(stage);
        stage.addExtension(new LifetimeExtension()
        {
            @Override
            public Task<?> preActivation(final AbstractActor<?> actor)
            {
                dr.inject(actor);
                return Task.done();
            }
        });
        stage.setMode(Stage.StageMode.HOST);
        stage.setExecutionPool(commonPool);
        stage.setMessagingPool(commonPool);
        stage.setObjectCloner(getExecutionObjectCloner());
        stage.addExtension(new FakeStorageExtension(fakeDatabase));
        stage.setClock(clock);
        stage.setClusterName(clusterName);
        stage.setClusterPeer(new FakeClusterPeer());
        stage.start().join();
        stage.bind();
        return stage;
    }

    protected DependencyRegistry initDependencyRegistry()
    {
        DependencyRegistry dr = new DependencyRegistry();
        dr.addSingleton(FakeSync.class, fakeSync);
        return dr;
    }

    private AtomicLong invocationId = new AtomicLong();

    private void addLogging(final Stage stage)
    {
        stage.addExtension(new InvokeHookExtension()
        {
            @Override
            public Task<?> invoke(final InvocationContext context, final Addressable toReference, final Method method, final int methodId, final Object[] params)
            {
                long id = invocationId.incrementAndGet();
                hiddenLog.info(id + " invoking: " + method.getDeclaringClass().getSimpleName() + "." + method.getName());
                return context.invokeNext(toReference, method, methodId, params).whenComplete(
                        (r, e) -> hiddenLog.info(id + " done invoking: " + method.getDeclaringClass().getSimpleName() + "." + method.getName())
                );
            }
        });
    }

    protected ExecutionObjectCloner getExecutionObjectCloner()
    {
        return new KryoCloner();
    }

    @FunctionalInterface
    public interface Exceptional
    {
        @SuppressWarnings("Checkstyle:IllegalThrowsCheck")
        Object call() throws Throwable;
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public Throwable expectException(Exceptional callable)
    {
        try
        {
            Object r = callable.call();
            if (r instanceof Future)
            {
                ((Future<?>) r).get(60, TimeUnit.SECONDS);
            }
        }
        catch (Throwable ex)
        {
            // ok
            return ex;
        }
        fail("Was expecting some exception");
        return null;
    }

    private Object getField(Object target, Class<?> clazz, String name) throws IllegalAccessException, NoSuchFieldException
    {
        final Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /**
     * Spins waiting for a given condition to be true.
     *
     * @param condition a function that must eventually return true
     */
    protected void awaitFor(Supplier<Boolean> condition)
    {
        try
        {
            while (!condition.get())
            {
                Thread.sleep(5);
            }
        }
        catch (Exception e)
        {

            throw new UncheckedException(e);
        }
    }

    /**
     * Checks if the that stages' execution is done running tasks
     *
     * @return boolean if there are no task running.
     */
    protected boolean isIdle(Stage stage)
    {
        try
        {
            // this is very ad hoc, but should work for our tests, until execution changes.
            // for starters access to this map should be synchronized.
            Map running = (Map) getField(getField(getField(stage, Stage.class, "execution"), Execution.class,
                    "executionSerializer"), ExecutionSerializer.class, "running");

            return running.size() == 0;
        }
        catch (Exception e)
        {

            throw new UncheckedException(e);
        }
    }

}
