package org.jboss.ejb.client.test.common;

import org.jboss.ejb.protocol.remote.RemoteEJBService;
import org.jboss.ejb.server.Association;
import org.jboss.ejb.server.ClusterTopologyListener.ClusterInfo;
import org.jboss.ejb.server.ClusterTopologyListener.NodeInfo;
import org.jboss.ejb.server.ClusterTopologyListener.MappingInfo;
import org.jboss.ejb.server.ClusterTopologyListener.ClusterRemovalInfo;
import org.jboss.ejb.server.ModuleAvailabilityListener.ModuleIdentifier;
import org.jboss.logging.Logger;
import org.jboss.remoting3.Endpoint;
import org.jboss.remoting3.EndpointBuilder;
import org.jboss.remoting3.OpenListener;
import org.jboss.remoting3.Registration;
import org.jboss.remoting3.ServiceRegistrationException;
import org.jboss.remoting3.spi.NetworkServerProvider;
import org.wildfly.security.auth.realm.SimpleMapBackedSecurityRealm;
import org.wildfly.security.auth.server.MechanismConfiguration;
import org.wildfly.security.auth.server.SaslAuthenticationFactory;
import org.wildfly.security.auth.server.SecurityDomain;
import org.wildfly.security.password.interfaces.ClearPassword;
import org.wildfly.security.permission.PermissionVerifier;
import org.wildfly.security.sasl.util.SaslFactories;
import org.wildfly.transaction.client.LocalTransactionContext;
import org.wildfly.transaction.client.provider.remoting.RemotingTransactionService;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Sequence;
import org.xnio.channels.AcceptingChannel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:cdewolf@redhat.com">Carlo de Wolf</a>
 * @author <a href="mailto:rachmato@redhat.com">Richard Achmatowicz</a>
 */
public class DummyServer {

    private static final Logger logger = Logger.getLogger(DummyServer.class);

    private Endpoint endpoint;
    private final int port;
    private final String host;
    private final String endpointName;

    private Registration registration;
    private AcceptingChannel<org.xnio.StreamConnection> server;
    private EJBDeploymentRepository deploymentRepository = new EJBDeploymentRepository();
    private EJBClusterRegistry clusterRegistry = new EJBClusterRegistry();

    public DummyServer(final String host, final int port) {
        this(host, port, "default-dummy-server-endpoint");
    }

    public DummyServer(final String host, final int port, final String endpointName) {
        this.host = host;
        this.port = port;
        this.endpointName = endpointName;
    }

    public void start() throws Exception {
        logger.info("Starting " + this);

        // create a Remoting endpoint
        final OptionMap options = OptionMap.EMPTY;
        EndpointBuilder endpointBuilder = Endpoint.builder().setEndpointName(this.endpointName).setXnioWorkerOptions(options);
        endpoint = endpointBuilder.build();

        // add a connection provider factory for the URI scheme "remote"
        // endpoint.addConnectionProvider("remote", new RemoteConnectionProviderFactory(), OptionMap.create(Options.SSL_ENABLED, Boolean.FALSE));
        final NetworkServerProvider serverProvider = endpoint.getConnectionProviderInterface("remote", NetworkServerProvider.class);

        // set up a security realm called default with a user called test
        final SimpleMapBackedSecurityRealm realm = new SimpleMapBackedSecurityRealm();
        realm.setPasswordMap("test", ClearPassword.createRaw(ClearPassword.ALGORITHM_CLEAR, "test".toCharArray()));
        // set up a security domain which has realm "default"
        final SecurityDomain.Builder domainBuilder = SecurityDomain.builder();
        domainBuilder.addRealm("default", realm).build();                                  // add the security realm called "default" to the security domain
        domainBuilder.setDefaultRealmName("default");
        domainBuilder.setPermissionMapper((permissionMappable, roles) -> PermissionVerifier.ALL);
        SecurityDomain testDomain = domainBuilder.build();

        // set up a SaslAuthenticationFactory
        SaslAuthenticationFactory saslAuthenticationFactory = SaslAuthenticationFactory.builder()
                .setSecurityDomain(testDomain)
                .setMechanismConfigurationSelector(mechanismInformation -> {
                    switch (mechanismInformation.getMechanismName()) {
                        case "ANONYMOUS":
                        case "PLAIN": {
                            return MechanismConfiguration.EMPTY;
                        }
                        default: return null;
                    }
                })
                .setFactory(SaslFactories.getElytronSaslServerFactory())
                .build();

        final OptionMap serverOptions = OptionMap.create(Options.SASL_MECHANISMS, Sequence.of("ANONYMOUS"), Options.SASL_POLICY_NOANONYMOUS, Boolean.FALSE);
        final SocketAddress bindAddress = new InetSocketAddress(InetAddress.getByName(host), port);
        this.server = serverProvider.createServer(bindAddress, serverOptions, saslAuthenticationFactory, null);

        // set up an association to handle invocations, session creations and module/toopology listensrs
        // the association makes use of a module deployment repository as well a sa  cluster registry
        Association dummyAssociation = new DummyAssociationImpl(deploymentRepository, clusterRegistry);

        // set up a remoting transaction service
        RemotingTransactionService.Builder txnServiceBuilder = RemotingTransactionService.builder();
        txnServiceBuilder.setEndpoint(endpoint);
        txnServiceBuilder.setTransactionContext(LocalTransactionContext.getCurrent());
        RemotingTransactionService transactionService = txnServiceBuilder.build();

        // setup remote EJB service
        RemoteEJBService remoteEJBService = RemoteEJBService.create(dummyAssociation,transactionService);
        remoteEJBService.serverUp();
        System.out.println("Started RemoteEJBService...");

        // Register an EJB channel open listener
        OpenListener channelOpenListener = remoteEJBService.getOpenListener();
        try {
            registration = endpoint.registerService("jboss.ejb", channelOpenListener, OptionMap.EMPTY);
        } catch (ServiceRegistrationException e) {
            throw new Exception(e);
        }
    }

    public void stop() throws Exception {
        this.server.close();
        this.server = null;
        IoUtils.safeClose(this.endpoint);
    }

    // module deployment interface
    public void register(final String appName, final String moduleName, final String distinctName, final String beanName, final Object instance) {
        deploymentRepository.register(appName, moduleName, distinctName, beanName, instance);
    }

    public void unregister(final String appName, final String moduleName, final String distinctName, final String beanName) {
        deploymentRepository.unregister(appName, moduleName, distinctName, beanName);
    }

    // clustering registry interface
    public void addCluster(ClusterInfo clusterInfo) {
        clusterRegistry.addCluster(clusterInfo);
    }

    void removeCluster(String clusterName) {
        clusterRegistry.removeCluster(clusterName);
    }

    void addClusterNodes(ClusterInfo newClusterInfo) {
        clusterRegistry.addClusterNodes(newClusterInfo);
    }

    void removeClusterNodes(ClusterRemovalInfo clusterRemovalInfo) {
        clusterRegistry.removeClusterNodes(clusterRemovalInfo);
    }

    public interface EJBDeploymentRepositoryListener {
        void moduleAvailable(List<ModuleIdentifier> modules);
        void moduleUnavailable(List<ModuleIdentifier> modules);
    }

    /**
     * Allows keeping track of which modules are deployed on this server.
     */
    public class EJBDeploymentRepository {
        Map<ModuleIdentifier, Map<String,Object>> registeredEJBs = new HashMap<ModuleIdentifier, Map<String,Object>>();
        List<EJBDeploymentRepositoryListener> listeners = new ArrayList<EJBDeploymentRepositoryListener>();

        void register(final String appName, final String moduleName, final String distinctName, final String beanName, final Object instance) {
            final ModuleIdentifier moduleID = new ModuleIdentifier(appName, moduleName, distinctName);
            Map<String, Object> ejbs = this.registeredEJBs.get(moduleID);
            if (ejbs == null) {
                ejbs = new HashMap<String, Object>();
                this.registeredEJBs.put(moduleID, ejbs);
            }
            ejbs.put(beanName, instance);

            // notify listeners
            List<ModuleIdentifier> availableModules = new ArrayList<>();
            availableModules.add(moduleID);
            for (EJBDeploymentRepositoryListener listener: listeners) {
                listener.moduleAvailable(availableModules);
            }
        }

        void unregister(final String appName, final String moduleName, final String distinctName, final String beanName) {
            final ModuleIdentifier moduleID = new ModuleIdentifier(appName, moduleName, distinctName);
            Map<String, Object> ejbs = this.registeredEJBs.get(moduleID);
            if (ejbs != null) {
                ejbs.remove(beanName);
            }
            // notify listeners
            List<ModuleIdentifier> unavailableModules = new ArrayList<>();
            unavailableModules.add(moduleID);
            for (EJBDeploymentRepositoryListener listener: listeners) {
                listener.moduleUnavailable(unavailableModules);
            }
        }

        Object findEJB(ModuleIdentifier module, String beanName) {
            System.out.println("DummyServer: looking for module: " + module.getAppName() + "/" + module.getModuleName() + "/" + module.getDistinctName() + " and bean " + beanName);
            final Map<String, Object> ejbs = this.registeredEJBs.get(module);
            final Object beanInstance = ejbs.get(beanName);
            if (beanInstance == null) {
                // any exception will be handled by the caller on seeing null
                return null;
            }
            return beanInstance ;
        }

        void dumpContents() {
            System.out.println("DummyServer: deployed modules:");
            for (ModuleIdentifier module : registeredEJBs.keySet()) {
                final Map<String, Object> ejbs = this.registeredEJBs.get(module);
                for (String beanName : ejbs.keySet()) {
                    final Object beanInstance = ejbs.get(beanName);
                    final String moduleName = module.getAppName() + "/" + module.getModuleName() + "/" + module.getDistinctName() ;
                    System.out.println("module: " + moduleName + ", beanName: " + beanName + ", beanType: " + beanInstance.getClass().getName());
                }
            }
        }

        void addListener(EJBDeploymentRepositoryListener listener) {
            System.out.println("DummyServer: deploymentRepository - adding listener");
            listeners.add(listener);

            // EJBClientChannel depends on an initial module availability report to be sent out
            List<ModuleIdentifier> availableModules = new ArrayList<ModuleIdentifier>();
            availableModules.addAll(this.registeredEJBs.keySet());

            listener.moduleAvailable(availableModules);
        }

        void removeListener(EJBDeploymentRepositoryListener listener) {
            listeners.remove(listener);
        }
    }


    public interface EJBClusterRegistryListener {
        void clusterTopology(List<ClusterInfo> clusterList);
        void clusterRemoval(List<String> clusterNames);
        void clusterNewNodesAdded(ClusterInfo cluster);
        void clusterNodesRemoved(List<ClusterRemovalInfo> clusterRemovals);
    }

    /**
     * Allows keeping track of which clusters this server has joined and their membership.
     *
     * To keep things simple, this is a direct mapping to the server-side cluster information used.
     * The server does not need to store the current state of the clusters.
     */
    public class EJBClusterRegistry {
        Map<String, ClusterInfo> joinedClusters = new HashMap<String, ClusterInfo>();
        List<EJBClusterRegistryListener> listeners = new ArrayList<EJBClusterRegistryListener>();

        EJBClusterRegistry() {
        }

        void addCluster(ClusterInfo cluster) {
            // add the cluster if they are not already present
            if (joinedClusters.keySet().contains(cluster.getClusterName())) {
                logger.warn("Cluster " + cluster.getClusterName() + " already exists; skipping add operation");
                return;
            }
            ClusterInfo absent = joinedClusters.put(cluster.getClusterName(), cluster);

            // notify listeners
            List<ClusterInfo> additions = new ArrayList<ClusterInfo>();
            additions.add(cluster);
            for (EJBClusterRegistryListener listener: listeners) {
                listener.clusterTopology(additions);
            }
        }

        void removeCluster(String clusterName) {
            // update the registry
            ClusterInfo removed = joinedClusters.remove(clusterName);
            if (removed == null) {
                logger.warn("Could not remove non-existent cluster");
            }
            // notify listeners
            List<String> removals = new ArrayList<String>();
            removals.add(clusterName);
            for (EJBClusterRegistryListener listener: listeners) {
                listener.clusterRemoval(removals);
            }
        }

        void addClusterNodes(ClusterInfo newClusterInfo) {
            // update the registry
            ClusterInfo oldClusterInfo = joinedClusters.get(newClusterInfo.getClusterName());
            if (oldClusterInfo == null) {
                joinedClusters.put(newClusterInfo.getClusterName(), newClusterInfo);
                logger.warn("new nodes cannot be added to existing cluster; creating new cluster entry");
            } else {
                List<NodeInfo> additions = new ArrayList<NodeInfo>();
                for (NodeInfo newNodeInfo : newClusterInfo.getNodeInfoList()) {
                    for (NodeInfo oldNodeInfo : oldClusterInfo.getNodeInfoList()) {
                        if (oldNodeInfo.getNodeName().equals(newNodeInfo.getNodeName())) {
                            additions.add(newNodeInfo);
                            logger.info("Added node " + newNodeInfo.getNodeName() + " to cluster " + oldClusterInfo.getClusterName());
                        }
                    }
                }
                oldClusterInfo.getNodeInfoList().addAll(additions);
            }
            // notify listeners
            for (EJBClusterRegistryListener listener: listeners) {
                listener.clusterNewNodesAdded(newClusterInfo);
            }
        }

        /**
         * Update the registry to remove all nodes from the named cluster
         *
         * @param clusterRemovalInfo nodes to remove
         */
        void removeClusterNodes(ClusterRemovalInfo clusterRemovalInfo) {
            // check to see if the cluster is in the registry
            if (!joinedClusters.keySet().contains(clusterRemovalInfo.getClusterName())) {
                logger.warn("Cluster " + clusterRemovalInfo.getClusterName() + " not present in registry; skipping removal");
                return;
            } else {
                // its in the registry, now remove any listed nodes
                ClusterInfo oldClusterInfo = joinedClusters.get(clusterRemovalInfo.getClusterName());
                List<NodeInfo> removals = new ArrayList<NodeInfo>();
                for (String nodeToRemove : clusterRemovalInfo.getNodeNames()) {
                    for (NodeInfo oldNodeInfo : oldClusterInfo.getNodeInfoList()) {
                        if (oldNodeInfo.getNodeName().equals(nodeToRemove)) {
                            logger.warn("Removing node " + nodeToRemove + " from cluster " + oldClusterInfo.getClusterName());
                            removals.add(oldNodeInfo);
                        }
                    }
                }
                // just do the update in place
                oldClusterInfo.getNodeInfoList().removeAll(removals);
            }
            // notify listeners
            List<ClusterRemovalInfo> removals = new ArrayList<ClusterRemovalInfo>();
            removals.add(clusterRemovalInfo);
            for (EJBClusterRegistryListener listener: listeners) {
                listener.clusterNodesRemoved(removals);
            }
        }

        void addListener(EJBClusterRegistryListener listener) {
            System.out.println("DummyServer: clusterRegistry - adding listener");
            listeners.add(listener);

            // EJBClientChannel depends on an initial module availability report to be sent out
            List<ClusterInfo> availableClusters = new ArrayList<ClusterInfo>();
            availableClusters.addAll(this.joinedClusters.values());

            listener.clusterTopology(availableClusters);
        }

        void removeListener(EJBClusterRegistryListener listener) {
            listeners.remove(listener);
        }
    }

    public static final ClusterInfo getClusterInfo(String name, NodeInfo... nodes) {
        List<NodeInfo> nodeList = new ArrayList<NodeInfo>();
        for (NodeInfo node : nodes) {
            nodeList.add(node);
        }
        return new ClusterInfo(name, nodeList);
    }

    public static NodeInfo getNodeInfo(String name, String destHost, int destPort, String sourceIp, int bytes) {
        InetAddress srcIpAddress = null;
        try {
            srcIpAddress = InetAddress.getByName(sourceIp);
        }
        catch(UnknownHostException e) {
        }
        List<MappingInfo> mappingList = new ArrayList<MappingInfo>();
        mappingList.add(new MappingInfo(destHost, destPort, srcIpAddress, bytes));
        return new NodeInfo(name, mappingList);
    }
}
