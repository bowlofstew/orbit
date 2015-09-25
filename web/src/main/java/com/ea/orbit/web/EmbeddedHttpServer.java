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

package com.ea.orbit.web;

import com.ea.orbit.annotation.Config;
import com.ea.orbit.annotation.Wired;
import com.ea.orbit.concurrent.Task;
import com.ea.orbit.container.Container;
import com.ea.orbit.container.Startable;
import com.ea.orbit.exception.UncheckedException;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.glassfish.hk2.api.DynamicConfiguration;
import org.glassfish.hk2.api.DynamicConfigurationService;
import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.internal.inject.Injections;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.glassfish.jersey.servlet.ServletProperties;

import javax.inject.Singleton;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Embedded http server providing jax-rs and web-socket support.
 */
@Singleton
public class EmbeddedHttpServer implements Startable
{
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EmbeddedHttpServer.class);
    private Server server;

    @Config("orbit.http.port")
    private int port = 9090;

    @Wired
    private Container container;

    @Config("orbit.http.providers")
    private List<Class<?>> providers = new ArrayList<>();

    public void registerProviders(Collection<Class<?>> classes)
    {
        providers.addAll(classes);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public Task<Void> start()
    {

        // Ensuring that jersey will use singletons from the orbit container.
        ServiceLocator locator = Injections.createLocator();
        DynamicConfigurationService dcs = locator.getService(DynamicConfigurationService.class);
        DynamicConfiguration dc = dcs.createDynamicConfiguration();

        final List<Class<?>> classes = new ArrayList<>(providers);
        if (container != null)
        {
            classes.addAll(container.getClasses());
            for (final Class<?> c : container.getClasses())
            {
                if (c.isAnnotationPresent(Singleton.class))
                {
                    Injections.addBinding(
                            Injections.newFactoryBinder(new Factory()
                            {
                                @Override
                                public Object provide()
                                {
                                    return container.get(c);
                                }

                                @Override
                                public void dispose(final Object instance)
                                {

                                }
                            }).to(c),
                            dc);
                }
            }
        }
        dc.commit();

        final ResourceConfig resourceConfig = new ResourceConfig();

        // installing jax-rs classes known by the orbit container.
        for (final Class c : classes)
        {
            if (c.isAnnotationPresent(javax.ws.rs.Path.class) || c.isAnnotationPresent(javax.ws.rs.ext.Provider.class))
            {
                resourceConfig.register(c);
            }
        }

        final WebAppContext webAppContext = new WebAppContext();
        final ProtectionDomain protectionDomain = EmbeddedHttpServer.class.getProtectionDomain();
        final URL location = protectionDomain.getCodeSource().getLocation();
        logger.info(location.toExternalForm());
        webAppContext.setInitParameter("useFileMappedBuffer", "false");
        webAppContext.setWar(location.toExternalForm());
        // this sets the default service locator to one that bridges to the orbit container.
        webAppContext.getServletContext().setAttribute(ServletProperties.SERVICE_LOCATOR, locator);
        webAppContext.setContextPath("/*");
        webAppContext.addServlet(new ServletHolder(new ServletContainer(resourceConfig)), "/*");

        final ContextHandler resourceContext = new ContextHandler();
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setDirectoriesListed(true);
        resourceHandler.setWelcomeFiles(new String[]{ "index.html" });
        resourceHandler.setBaseResource(Resource.newClassPathResource("/web"));

        resourceContext.setHandler(resourceHandler);
        resourceContext.setInitParameter("useFileMappedBuffer", "false");
        final ContextHandlerCollection contexts = new ContextHandlerCollection();

        contexts.setHandlers(new Handler[]{ wrapHandlerWithMetrics(resourceContext, "resourceContext"), wrapHandlerWithMetrics(webAppContext, "webAppContext") });

        server = new Server(port);
        server.setHandler(contexts);
        try
        {
            ///Initialize javax.websocket layer
            final ServerContainer serverContainer = WebSocketServerContainerInitializer.configureContext(webAppContext);

            for (Class c : classes)
            {
                if (c.isAnnotationPresent(ServerEndpoint.class))
                {
                    final ServerEndpoint annotation = (ServerEndpoint) c.getAnnotation(ServerEndpoint.class);

                    final ServerEndpointConfig serverEndpointConfig = ServerEndpointConfig.Builder.create(c, annotation.value()).configurator(new ServerEndpointConfig.Configurator()
                    {
                        @Override
                        public <T> T getEndpointInstance(final Class<T> endpointClass) throws InstantiationException
                        {
                            return container.get(endpointClass);
                        }
                    }).build();

                    serverContainer.addEndpoint(serverEndpointConfig);
                }
            }
        }
        catch (Exception e)
        {
            logger.error("Error starting jetty", e);
            throw new UncheckedException(e);
        }

        try
        {
            server.start();
        }
        catch (Exception e)
        {
            logger.error("Error starting jetty", e);
            throw new UncheckedException(e);
        }
        return Task.done();
    }

    /**
     * Gets the actual tcp port for the first server connector
     *
     * @return the actual port available only after start.
     */
    public int getLocalPort()
    {
        return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    public Task<Void> stop()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            logger.error("Error stopping jetty", e);
            throw new UncheckedException(e);
        }
        return Task.done();
    }

    public int getPort()
    {
        return port;
    }

    public void setPort(final int port)
    {
        this.port = port;
    }

    private Handler wrapHandlerWithMetrics(Handler handlerToWrap, String handlerName)
    {
        try
        {
            Class metricsWrapperClass = Class.forName("com.ea.orbit.metrics.JettyMetricsHandlerWrapper");
            Method wrapperMethod = metricsWrapperClass.getDeclaredMethod("wrapHandler", Handler.class, String.class);
            Handler wrappedHandler = (Handler) wrapperMethod.invoke(null, handlerToWrap, handlerName);

            return wrappedHandler;
        }
        catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException ex)
        {
            logger.info("Skipping Orbit Web Metrics integration because Orbit Metrics is not available.");
            //OK. Orbit Metrics not being used.
        }

        //If we get here, Orbit Metrics isn't being used so just return the handler as-is.
        return handlerToWrap;
    }
}
