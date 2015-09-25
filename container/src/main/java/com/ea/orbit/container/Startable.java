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

package com.ea.orbit.container;

import com.ea.orbit.concurrent.Task;

/**
 * Optional interface that orbit components may implement.
 * <p>A startable singleton component will have it's start and stop method called by the orbit container.
 * </p>
 * <p>The non singletons will not be part of the container lifecycle.</p>
 */
public interface Startable
{
    /**
     * It must be possible to call start twice without errors.
     * <br>
     * Convention: If started is called more than once before stop, it must return the same future instead of running the start procedure again.
     *
     * @return a Task that will be completed once the startup is done.
     */
    default Task<?> start() //throws Throwable
    {
        return Task.done();
    }

    /**
     * It must be possible to call stop twice.
     * <br>
     * Convention: If stop is called more than once it must return the same future.
     *
     * @return a Task that will be completed once the startup is done.
     */
    default Task<?> stop() //throws Throwable
    {
        return Task.done();
    }
}
