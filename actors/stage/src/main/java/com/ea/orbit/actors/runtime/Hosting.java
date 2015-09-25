/*
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

package com.ea.orbit.actors.runtime;

import com.ea.orbit.actors.Addressable;
import com.ea.orbit.actors.annotation.StatelessWorker;
import com.ea.orbit.actors.cluster.ClusterPeer;
import com.ea.orbit.actors.cluster.NodeAddress;
import com.ea.orbit.annotation.Config;
import com.ea.orbit.concurrent.Task;
import com.ea.orbit.container.Startable;
import com.ea.orbit.exception.UncheckedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public class Hosting implements NodeCapabilities, Startable
{
    private static final Logger logger = LoggerFactory.getLogger(Hosting.class);
    private NodeTypeEnum nodeType;
    private ClusterPeer clusterPeer;

    private volatile Map<NodeAddress, NodeInfo> activeNodes = new HashMap<>(0);
    private volatile List<NodeInfo> serverNodes = new ArrayList<>(0);
    private final Object serverNodesUpdateMutex = new Object();
    private Execution execution;
    private ConcurrentMap<ActorKey, NodeAddress> localAddressCache = new ConcurrentHashMap<>();
    private volatile ConcurrentMap<ActorKey, NodeAddress> distributedDirectory;
    @Config("orbit.actors.timeToWaitForServersMillis")
    private long timeToWaitForServersMillis = 30000;
    private Random random = new Random();

    public Hosting()
    {
        //
    }

    public long getTimeToWaitForServersMillis()
    {
        return timeToWaitForServersMillis;
    }

    public void setTimeToWaitForServersMillis(final long timeToWaitForServersMillis)
    {
        this.timeToWaitForServersMillis = timeToWaitForServersMillis;
    }

    public void setExecution(final Execution execution)
    {
        this.execution = execution;
    }

    public void setNodeType(final NodeTypeEnum nodeType)
    {
        this.nodeType = nodeType;
    }

    public List<NodeAddress> getAllNodes()
    {
        return Collections.unmodifiableList(new ArrayList<>(activeNodes.keySet()));
    }

    public List<NodeAddress> getServerNodes()
    {
        final ArrayList<NodeAddress> set = new ArrayList<>(serverNodes.size());
        for (NodeInfo s : serverNodes)
        {
            set.add(s.address);
        }
        return Collections.unmodifiableList(set);
    }

    public Task<?> notifyStateChange()
    {
        return Task.allOf(activeNodes.values().stream().map(info ->
                info.nodeCapabilities.nodeModeChanged(clusterPeer.localAddress(), execution.getState())));
    }

    private static class NodeInfo
    {
        boolean active;
        NodeAddress address;
        NodeState state = NodeState.RUNNING;
        NodeCapabilities nodeCapabilities;
        boolean cannotHostActors;
        final ConcurrentHashMap<String, Integer> canActivate = new ConcurrentHashMap<>();

        public NodeInfo(final NodeAddress address)
        {
            this.address = address;
        }
    }

    @Override
    public Task<Integer> canActivate(String interfaceName)
    {
        if (nodeType == NodeTypeEnum.CLIENT || execution.getState() != NodeState.RUNNING)
        {
            return Task.fromValue(actorSupported_noneSupported);
        }
        return Task.fromValue(execution.canActivateActor(interfaceName) ? actorSupported_yes
                : actorSupported_no);
    }

    @Override
    public Task<Void> nodeModeChanged(final NodeAddress nodeAddress, final NodeState newState)
    {
        final NodeInfo node = activeNodes.get(nodeAddress);
        if (node != null)
        {
            node.state = newState;
            if (node.state != NodeState.RUNNING)
            {
                // clear list of actors this node can activate
                node.canActivate.clear();
            }
        }
        return Task.done();
    }

    public void setClusterPeer(final ClusterPeer clusterPeer)
    {
        this.clusterPeer = clusterPeer;
    }

    public Task<Void> start()
    {
        clusterPeer.registerViewListener(v -> onClusterViewChanged(v));
        return Task.done();
    }

    private void onClusterViewChanged(final Collection<NodeAddress> nodes)
    {
        HashMap<NodeAddress, NodeInfo> oldNodes = new HashMap<>(activeNodes);
        HashMap<NodeAddress, NodeInfo> newNodes = new HashMap<>(nodes.size());
        List<NodeInfo> justAddedNodes = new ArrayList<>(Math.max(1, nodes.size() - oldNodes.size()));
        for (final NodeAddress a : nodes)
        {
            NodeInfo nodeInfo = oldNodes.remove(a);
            if (nodeInfo == null)
            {
                nodeInfo = new NodeInfo(a);
                nodeInfo.nodeCapabilities = execution.createReference(a, NodeCapabilities.class, "");
                nodeInfo.active = true;
                activeNodes.put(a, nodeInfo);
                justAddedNodes.add(nodeInfo);
            }
            newNodes.put(a, nodeInfo);
        }
        // nodes that were removed
        for (NodeInfo oldNodeInfo : oldNodes.values())
        {
            oldNodeInfo.active = false;
        }
        activeNodes = newNodes;
        updateServerNodes();
        // TODO notify someone? (NodeInfo oldNodeInfo : oldNodes.values()) { ... }
    }

    private void updateServerNodes()
    {
        synchronized (serverNodesUpdateMutex)
        {
            this.serverNodes = activeNodes.values().stream().filter(
                    nodeInfo -> nodeInfo.active && !nodeInfo.cannotHostActors).collect(Collectors.toList());
            if (serverNodes.size() > 0)
            {
                serverNodesUpdateMutex.notifyAll();
            }
        }
    }

    public Task<NodeAddress> locateActor(final Addressable reference, final boolean forceActivation)
    {
        return (forceActivation) ? locateAndActivateActor(reference) : locateActiveActor(reference);
    }


    private Task<NodeAddress> locateActiveActor(final Addressable actorReference)
    {
        ActorKey addressable = new ActorKey(((ActorReference) actorReference)._interfaceClass().getName(),
                String.valueOf(((ActorReference) actorReference).id));
        NodeAddress address = localAddressCache.get(addressable);
        if (address != null && activeNodes.containsKey(address))
        {
            return Task.fromValue(address);
        }
        return Task.fromValue(null);
    }

    private Task<NodeAddress> locateAndActivateActor(final Addressable actorReference)
    {
        ActorKey addressable = new ActorKey(((ActorReference) actorReference)._interfaceClass().getName(),
                String.valueOf(((ActorReference) actorReference).id));

        NodeAddress address = localAddressCache.get(addressable);
        if (address != null && activeNodes.containsKey(address))
        {
            return Task.fromValue(address);
        }
        final Class<?> interfaceClass = ((ActorReference<?>) actorReference)._interfaceClass();
        final String interfaceClassName = interfaceClass.getName();
        if (interfaceClass.isAnnotationPresent(StatelessWorker.class))
        {
            if (nodeType == NodeTypeEnum.SERVER && execution.canActivateActor(interfaceClassName))
            {
                // TODO: consider always using local instance if this node is a server
                // ~90% chance of making a local call
                if (random.nextInt(100) < 90)
                {
                    return Task.fromValue(clusterPeer.localAddress());
                }
                // randomly chooses one server node to process this actor
                final NodeAddress nodeAddress = selectNode(interfaceClassName, false);
                if (nodeAddress != null)
                {
                    return Task.fromValue(nodeAddress);
                }
            }
        }

        final CompletableFuture<NodeAddress> async = CompletableFuture.supplyAsync(() -> {
            NodeAddress nodeAddress = null;

            if (interfaceClass.isAnnotationPresent(StatelessWorker.class))
            {
                // randomly chooses one server node to process this actor
                return selectNode(interfaceClassName, true);
            }
            if (distributedDirectory == null)
            {
                synchronized (this)
                {
                    if (distributedDirectory == null)
                    {
                        distributedDirectory = clusterPeer.getCache("distributedDirectory");
                    }
                }
            }
            nodeAddress = distributedDirectory.get(addressable);

            if (nodeAddress != null && activeNodes.containsKey(nodeAddress))
            {
                localAddressCache.put(addressable, nodeAddress);
                return nodeAddress;
            }
            if (nodeAddress != null)
            {
                distributedDirectory.remove(addressable, nodeAddress);
            }
            nodeAddress = selectNode(interfaceClassName, true);
            NodeAddress otherNodeAddress = distributedDirectory.putIfAbsent(addressable, nodeAddress);
            // someone got there first.
            if (otherNodeAddress != null)
            {
                localAddressCache.put(addressable, otherNodeAddress);
                return otherNodeAddress;
            }
            localAddressCache.put(addressable, nodeAddress);
            return nodeAddress;
        }, execution.getExecutor());
        return Task.from(async);
    }

    private NodeAddress selectNode(final String interfaceClassName, boolean allowToBlock)
    {
        List<NodeInfo> potentialNodes;
        long start = System.currentTimeMillis();

        while (true)
        {
            if (System.currentTimeMillis() - start > timeToWaitForServersMillis)
            {
                String err = "Timeout waiting for a server capable of handling: " + interfaceClassName;
                logger.error(err);
                throw new UncheckedException(err);
            }

            List<NodeInfo> currentServerNodes = serverNodes;

            potentialNodes = currentServerNodes.stream()
                    .filter(n -> (!n.cannotHostActors && n.state == NodeState.RUNNING)
                            && NodeCapabilities.actorSupported_no != n.canActivate.getOrDefault(interfaceClassName, NodeCapabilities.actorSupported_yes))
                    .collect(Collectors.toList());

            if (potentialNodes.size() == 0)
            {
                if (!allowToBlock)
                {
                    return null;
                }
                // waits for servers
                synchronized (serverNodesUpdateMutex)
                {
                    if (serverNodes.size() == 0)
                    {
                        try
                        {
                            serverNodesUpdateMutex.wait(5);
                        }
                        catch (InterruptedException e)
                        {
                            throw new UncheckedException(e);
                        }
                    }
                }
            }
            else
            {
                NodeInfo nodeInfo = potentialNodes.get(random.nextInt(potentialNodes.size()));

                Integer canActivate = nodeInfo.canActivate.get(interfaceClassName);
                if (canActivate == null)
                {
                    // ask if the node can activate this type of actor.
                    try
                    {
                        canActivate = nodeInfo.nodeCapabilities.canActivate(interfaceClassName).join();
                        if (canActivate == actorSupported_noneSupported)
                        {
                            nodeInfo.cannotHostActors = true;
                            // jic
                            nodeInfo.canActivate.put(interfaceClassName, actorSupported_no);
                        }
                        else
                        {
                            nodeInfo.canActivate.put(interfaceClassName, canActivate);
                        }
                    }
                    catch (Exception ex)
                    {
                        logger.error("Error locating server for " + interfaceClassName, ex);
                        continue;
                    }
                }
                if (canActivate == actorSupported_yes)
                {
                    return nodeInfo.address;
                }
            }
        }
    }

}
