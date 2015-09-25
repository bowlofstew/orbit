package com.ea.orbit.actors.test.actors;

import com.ea.orbit.actors.Actor;
import com.ea.orbit.concurrent.Task;

public interface SomePlayer extends Actor
{
    Task<String> getName();

    Task<Void> joinMatch(SomeMatch someMatch);

    Task<Void> matchEvent(SomeMatch someMatch);

    Task<Integer> getMatchEventCount();

    Task<String> getNodeId();
}
