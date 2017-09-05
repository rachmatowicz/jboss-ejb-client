/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.ejb.client.test.common;

import org.jboss.ejb.client.Affinity;
import org.jboss.ejb.client.ClusterAffinity;
import org.jboss.ejb.client.EJBIdentifier;
import org.jboss.ejb.client.EJBLocator;
import org.jboss.ejb.client.EJBMethodLocator;
import org.jboss.ejb.client.EJBModuleIdentifier;
import org.jboss.ejb.client.NodeAffinity;
import org.jboss.ejb.client.StatelessEJBLocator;
import org.jboss.ejb.client.UUIDSessionID;
import org.jboss.ejb.server.Association;
import org.jboss.ejb.server.CancelHandle;
import org.jboss.ejb.server.ClusterTopologyListener;
import org.jboss.ejb.server.InvocationRequest;
import org.jboss.ejb.server.ListenerHandle;
import org.jboss.ejb.server.ModuleAvailabilityListener;
import org.jboss.ejb.server.Request;
import org.jboss.ejb.server.SessionOpenRequest;
import org.jboss.logging.Logger;
import org.wildfly.common.annotation.NotNull;

import org.jboss.ejb.client.test.common.DummyServer.EJBDeploymentRepository;
import org.jboss.ejb.client.test.common.DummyServer.EJBInstanceIdentifier;
import org.jboss.ejb.client.test.common.DummyServer.EJBDeploymentRepositoryListener;
import org.jboss.ejb.client.test.common.DummyServer.EJBClusterRegistry;
import org.jboss.ejb.client.test.common.DummyServer.EJBClusterRegistryListener;

import javax.ejb.EJBException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dummy version of an Association
 *
 * @author <a href="mailto:rachmato@redhat.com">Richard Achmatowicz</a>
 */
public class DummyAssociationImpl implements Association {

    private static final Logger logger = Logger.getLogger(DummyAssociationImpl.class);

    DummyServer server;
    EJBDeploymentRepository deploymentRepository ;
    EJBClusterRegistry clusterRegistry;

    public DummyAssociationImpl(DummyServer server, EJBDeploymentRepository repository, EJBClusterRegistry clusterRegistry) {
        this.server = server;
        this.deploymentRepository = repository;
        this.clusterRegistry = clusterRegistry;
    }

    /**
     * Handles an invocation request received from the client
     *
     * @param invocationRequest the invocation request (not {@code null})
     * @param <T>
     * @return
     */
    @Override
    public <T> CancelHandle receiveInvocationRequest(@NotNull InvocationRequest invocationRequest) {
        final EJBIdentifier ejbIdentifier = invocationRequest.getEJBIdentifier();
        final EJBModuleIdentifier module = ejbIdentifier.getModuleIdentifier();
        final String beanName = ejbIdentifier.getBeanName();

        // search the repository for the bean
        EJBInstanceIdentifier instanceId = deploymentRepository.findEJB(module, beanName);

        Object bean = instanceId.getInstance();
        boolean isStateful = instanceId.isStateful();

        if (bean == null) {
            invocationRequest.writeNoSuchEJB();;
            return CancelHandle.NULL;
        }

        // get the resolved content of the request (attachments, affinity, parameters, etc)
        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        final InvocationRequest.Resolved requestContent;
        try {
            requestContent = invocationRequest.getRequestContent(classLoader);
        } catch(IOException |ClassNotFoundException e) {
            invocationRequest.writeException(new EJBException(e));
            return CancelHandle.NULL;
        }

        final Map<String, Object> attachments = requestContent.getAttachments();
        final EJBLocator<?> ejbLocator = requestContent.getEJBLocator();

        // check if we need to handle lazy session creation
        if (ejbLocator instanceof StatelessEJBLocator && isStateful) {
            // create the session ID
            final UUID uuid = UUID.randomUUID();
            UUIDSessionID sessionID = new UUIDSessionID(uuid);

            // set the strong affinity for this SFSB based on cluster membership
            Affinity strongAffinity ;
            if (clusterRegistry.isClusterMember("ejb")) {
                strongAffinity = new ClusterAffinity("ejb");
            } else {
                strongAffinity = new NodeAffinity(server.getEndpointName());
            }

            invocationRequest.convertToStateful(sessionID, strongAffinity);
        }

        // now invoke the bean, get the result, return the result
        // NOTE: we don't model ComponentViews of the bean  as is done in Wildfly; we just look for the invoked methods on the bean itself

        // locate the method to be invoked
        final Method invokedMethod = findMethod(bean.getClass(), invocationRequest.getMethodLocator());
        if (invokedMethod == null) {
            invocationRequest.writeNoSuchMethod();
            return CancelHandle.NULL;
        }

        // checking for async method might need looking at
        final boolean isAsync = isAsyncMethod(invokedMethod);
        final boolean oneWay = isAsync && invokedMethod.getReturnType() == void.class;

        if (oneWay) {
            // send immediate response
            requestContent.writeInvocationResult(null);
        }

        // this is a simpler cancellaction flag than required (see CancellationFlag)
        final AtomicBoolean cancelled = new AtomicBoolean();
        Runnable runnable = () -> {
            // for handling cancellationof the invocation
            if (cancelled.get()) {
                if (!oneWay) invocationRequest.writeCancelResponse();
                return;
            }

            // invoke the method here
            Object retVal = null;
            try {
                retVal = invokedMethod.invoke(bean, requestContent.getParameters());
            } catch (IllegalAccessException iae) {
                // handle IllegalAccess Exception
                logger.errorf("Exception occurred when invoking method %s: %s", invokedMethod.getName(), iae.getMessage());
                Exception exceptionToWrite = new EJBException(iae.getLocalizedMessage());
                invocationRequest.writeException(exceptionToWrite);
                return ;
            } catch (InvocationTargetException ite) {
                // handle InvocationtargetException
                logger.errorf("Exception occurred when invoking method %s: %s", invokedMethod.getName(), ite.getMessage());
                Exception exceptionToWrite = new EJBException(ite.getLocalizedMessage());
                invocationRequest.writeException(exceptionToWrite);
                return ;
            }

            // invocation was successful - prepare the result
            if (! oneWay) {
                // attach any weak affinity if available
                // TODO
                requestContent.writeInvocationResult(retVal);
            }
        };
        execute(invocationRequest, runnable, false);
        return ignored -> cancelled.set(true);

    }

    /**
     * Given a bean class and a descriptor of the method to be invoked, returns the Method instance
     *
     * @param klass the bean instance we are invoking on
     * @param ejbMethodLocator  the method locator of the method we want to invoke
     * @return the method instance (or null if no such method exists)
     */
    private Method findMethod(final Class<?> klass, final EJBMethodLocator ejbMethodLocator) {
        // use Java 8
        final int numParams = ejbMethodLocator.getParameterCount();

        final Class<?>[] types = new Class<?>[numParams];
        for (int i = 0; i < numParams; i++) {
            try {
                types[i]= Class.forName(ejbMethodLocator.getParameterTypeName(i), false, klass.getClassLoader());
            } catch(ClassNotFoundException e) {
                return null;
            }
        }
        Method retVal = null;
        try {
            retVal = klass.getMethod(ejbMethodLocator.getMethodName(), types);
        } catch (NoSuchMethodException e) {
            return null;
        }
        return retVal;
    }

    private boolean isAsyncMethod(final Method method) {
        // just check for return type and assume it is async if returns a Future
        return method.getReturnType().equals(Future.class);
    }

    /**
     * Handles a session open request received from the client.
     *
     * @param sessionOpenRequest the session open request (not {@code null})
     * @return
     */
    @Override
    public CancelHandle receiveSessionOpenRequest(@NotNull SessionOpenRequest sessionOpenRequest) {
        final EJBIdentifier ejbIdentifier = sessionOpenRequest.getEJBIdentifier();
        final EJBModuleIdentifier module = ejbIdentifier.getModuleIdentifier();
        final String beanName = ejbIdentifier.getBeanName();

        // search the repository for the bean
        Object bean = deploymentRepository.findEJB(module, beanName);

        if (bean == null) {
            sessionOpenRequest.writeNoSuchEJB();;
            return CancelHandle.NULL;
        }


        // now invoke the bean, get the result, return the result
        final AtomicBoolean cancelled = new AtomicBoolean();
        Runnable runnable = () -> {
            if (cancelled.get()) {
                sessionOpenRequest.writeCancelResponse();
                return;
            }
            final UUID uuid = UUID.randomUUID();
            UUIDSessionID sessionID = new UUIDSessionID(uuid);

            // set the strong affinity for this SFSB based on cluster membership
            Affinity strongAffinity ;
            if (clusterRegistry.isClusterMember("ejb")) {
                strongAffinity = new ClusterAffinity("ejb");
            } else {
                strongAffinity = new NodeAffinity(server.getEndpointName());
            }
            sessionOpenRequest.convertToStateful(sessionID, strongAffinity);
        };
        execute(sessionOpenRequest, runnable, false);
        return ignored -> cancelled.set(true);
    }

    /*
     * We can decidie to run tasks in-line or on an executor
     */
    private void execute(Request request, Runnable task, final boolean isAsync) {
        if (request.getProtocol().equals("local") && !isAsync) {
            task.run();
        } else {
            request.getRequestExecutor().execute(task);
        }
    }

    /**
     * Registers a listener that responds to callbacks of the form:
     *     void clusterTopology(List<ClusterInfo> clusterInfoList);
     *     void clusterRemoval(List<String> clusterNames);
     *     void clusterNewNodesAdded(ClusterInfo newClusterInfo);
     *     void clusterNodesRemoved(List<ClusterRemovalInfo> clusterRemovalInfoList);
     *
     *     The listense created just passes through callbacks without modification.
     *
     * @param clusterTopologyListener the cluster topology listener (not {@code null})
     * @return
     */
    @Override
    public ListenerHandle registerClusterTopologyListener(@NotNull ClusterTopologyListener clusterTopologyListener) {
        final EJBClusterRegistryListener listener = new EJBClusterRegistryListener() {

            @Override
            public void clusterTopology(List<ClusterTopologyListener.ClusterInfo> clusterList) {
                clusterTopologyListener.clusterTopology(clusterList);
            }

            @Override
            public void clusterRemoval(List<String> clusterNames) {
                clusterTopologyListener.clusterRemoval(clusterNames);
            }

            @Override
            public void clusterNewNodesAdded(ClusterTopologyListener.ClusterInfo cluster) {
                clusterTopologyListener.clusterNewNodesAdded(cluster);
            }

            @Override
            public void clusterNodesRemoved(List<ClusterTopologyListener.ClusterRemovalInfo> clusterRemovals) {
                clusterTopologyListener.clusterNodesRemoved(clusterRemovals);
            }
        };

        // register the listener and return a handle that can be used to later remove it
        clusterRegistry.addListener(listener);
        return () -> clusterRegistry.removeListener(listener);
    }

    /**
     * Registers a listener that responds to callbacks of the form:
     *   void moduleAvailable(List<ModuleIdentifier> modules);
     *   void moduleUnavailable(List<ModuleIdentifier> modules);
     *
     * @param moduleAvailabilityListener the module availability listener (not {@code null})
     * @return
     */
    @Override
    public ListenerHandle registerModuleAvailabilityListener(@NotNull ModuleAvailabilityListener moduleAvailabilityListener) {
        final EJBDeploymentRepositoryListener listener = new EJBDeploymentRepositoryListener() {
            /**
             * Make use of the callback
             *   void moduleAvailable(List<ModuleIdentifier> modules)
             * to trigger module availability updates to the client
             * @param modules the list of available modules
             */
            @Override
            public void moduleAvailable(List<EJBModuleIdentifier> modules) {
                moduleAvailabilityListener.moduleAvailable(modules);
            }

            /**
             * Make use of the callback
             *   void moduleUnavailable(List<ModuleIdentifier> modules)
             * to trigger module availability updates to the client
             * @param modules the list of unavailable modules
             */
            @Override
            public void moduleUnavailable(List<EJBModuleIdentifier> modules) {
                moduleAvailabilityListener.moduleUnavailable(modules);
            }
        };

        // register the listener and return a handle that can be used to later remove it
        this.deploymentRepository.addListener(listener);
        return () -> deploymentRepository.removeListener(listener);
    }
}
