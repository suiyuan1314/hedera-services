/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.gossip;

import static com.swirlds.platform.consensus.ConsensusConstants.ROUND_UNDEFINED;

import com.swirlds.base.state.Startable;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.threading.framework.QueueThread;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.config.QueueThreadConfiguration;
import com.swirlds.common.threading.framework.config.QueueThreadMetricsConfiguration;
import com.swirlds.common.threading.framework.config.StoppableThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.common.threading.pool.ParallelExecutor;
import com.swirlds.common.wiring.model.WiringModel;
import com.swirlds.common.wiring.wires.input.BindableInputWire;
import com.swirlds.common.wiring.wires.output.StandardOutputWire;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.config.ThreadConfig;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.event.GossipEvent;
import com.swirlds.platform.event.linking.GossipLinker;
import com.swirlds.platform.event.linking.InOrderLinker;
import com.swirlds.platform.eventhandling.EventConfig;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.gossip.sync.SyncManagerImpl;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.ConnectionTracker;
import com.swirlds.platform.network.NetworkMetrics;
import com.swirlds.platform.network.NetworkPeerIdentifier;
import com.swirlds.platform.network.NetworkUtils;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.network.communication.NegotiationProtocols;
import com.swirlds.platform.network.communication.ProtocolNegotiatorThread;
import com.swirlds.platform.network.communication.handshake.VersionCompareHandshake;
import com.swirlds.platform.network.connectivity.ConnectionServer;
import com.swirlds.platform.network.connectivity.InboundConnectionHandler;
import com.swirlds.platform.network.connectivity.OutboundConnectionCreator;
import com.swirlds.platform.network.connectivity.SocketFactory;
import com.swirlds.platform.network.protocol.HeartbeatProtocolFactory;
import com.swirlds.platform.network.protocol.ProtocolFactory;
import com.swirlds.platform.network.protocol.ProtocolRunnable;
import com.swirlds.platform.network.protocol.ReconnectProtocolFactory;
import com.swirlds.platform.network.protocol.SyncProtocolFactory;
import com.swirlds.platform.network.topology.NetworkTopology;
import com.swirlds.platform.network.topology.StaticConnectionManagers;
import com.swirlds.platform.network.topology.StaticTopology;
import com.swirlds.platform.reconnect.DefaultSignedStateValidator;
import com.swirlds.platform.reconnect.ReconnectController;
import com.swirlds.platform.reconnect.ReconnectHelper;
import com.swirlds.platform.reconnect.ReconnectLearnerFactory;
import com.swirlds.platform.reconnect.ReconnectLearnerThrottle;
import com.swirlds.platform.reconnect.ReconnectThrottle;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.status.PlatformStatus;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.wiring.NoInput;
import com.swirlds.platform.wiring.components.Gossip;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Boilerplate code for gossip.
 */
public class SyncGossip implements ConnectionTracker, Gossip {
    public static final String PLATFORM_THREAD_POOL_NAME = "platform-core";

    private boolean started = false;

    private final PlatformContext platformContext;
    private final ReconnectController reconnectController;

    private final AtomicBoolean gossipHalted = new AtomicBoolean(false);
    private final SyncPermitProvider syncPermitProvider;
    private final SyncConfig syncConfig;
    private final InOrderLinker inOrderLinker;
    private final Shadowgraph shadowgraph;
    private final ShadowgraphSynchronizer syncShadowgraphSynchronizer;

    /**
     * Keeps track of the number of events in the intake pipeline from each peer
     */
    private final IntakeEventCounter intakeEventCounter;

    /**
     * A list of threads that execute the sync protocol using bidirectional connections
     */
    private final List<StoppableThread> syncProtocolThreads = new ArrayList<>();

    private final NetworkTopology topology;
    private final NetworkMetrics networkMetrics;
    private final ReconnectHelper reconnectHelper;
    private final StaticConnectionManagers connectionManagers;
    private final FallenBehindManagerImpl fallenBehindManager;
    private final SyncManagerImpl syncManager;
    private final ReconnectThrottle reconnectThrottle;
    private final ReconnectMetrics reconnectMetrics;

    protected final StatusActionSubmitter statusActionSubmitter;
    protected final AtomicReference<PlatformStatus> currentPlatformStatus =
            new AtomicReference<>(PlatformStatus.STARTING_UP);

    private final List<Startable> thingsToStart = new ArrayList<>();

    private Consumer<GossipEvent> receivedEventHandler;

    /**
     * The old style intake queue (if enabled), null if not enabled.
     */
    private QueueThread<GossipEvent> oldStyleIntakeQueue;

    private final ThreadManager threadManager;

    /**
     * Builds the gossip engine, depending on which flavor is requested in the configuration.
     *
     * @param platformContext               the platform context
     * @param threadManager                 the thread manager
     * @param keysAndCerts                  private keys and public certificates
     * @param addressBook                   the current address book
     * @param selfId                        this node's ID
     * @param appVersion                    the version of the app
     * @param intakeQueueSizeSupplier       a supplier for the size of the event intake queue
     * @param swirldStateManager            manages the mutable state
     * @param latestCompleteState           holds the latest signed state that has enough signatures to be verifiable
     * @param statusActionSubmitter         submits status actions
     * @param loadReconnectState            a method that should be called when a state from reconnect is obtained
     * @param clearAllPipelinesForReconnect this method should be called to clear all pipelines prior to a reconnect
     * @param intakeEventCounter            keeps track of the number of events in the intake pipeline from each peer
     * @param statusActionSubmitter         for submitting updates to the platform status manager
     */
    public SyncGossip(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final AddressBook addressBook,
            @NonNull final NodeId selfId,
            @NonNull final SoftwareVersion appVersion,
            @NonNull final LongSupplier intakeQueueSizeSupplier,
            @NonNull final SwirldStateManager swirldStateManager,
            @NonNull final Supplier<ReservedSignedState> latestCompleteState,
            @NonNull final StatusActionSubmitter statusActionSubmitter,
            @NonNull final Consumer<SignedState> loadReconnectState,
            @NonNull final Runnable clearAllPipelinesForReconnect,
            @NonNull final IntakeEventCounter intakeEventCounter) {

        this.platformContext = Objects.requireNonNull(platformContext);

        this.threadManager = Objects.requireNonNull(threadManager);

        inOrderLinker = new GossipLinker(platformContext, intakeEventCounter);
        shadowgraph = new Shadowgraph(platformContext, addressBook, intakeEventCounter);

        this.statusActionSubmitter = Objects.requireNonNull(statusActionSubmitter);

        final ThreadConfig threadConfig = platformContext.getConfiguration().getConfigData(ThreadConfig.class);

        final BasicConfig basicConfig = platformContext.getConfiguration().getConfigData(BasicConfig.class);
        final List<PeerInfo> peers = Utilities.createPeerInfoList(addressBook, selfId);

        topology = new StaticTopology(peers, selfId);
        final NetworkPeerIdentifier peerIdentifier = new NetworkPeerIdentifier(platformContext, peers);
        final SocketFactory socketFactory =
                NetworkUtils.createSocketFactory(selfId, peers, keysAndCerts, platformContext.getConfiguration());
        // create an instance that can create new outbound connections
        final OutboundConnectionCreator connectionCreator =
                new OutboundConnectionCreator(platformContext, selfId, this, socketFactory, addressBook);
        connectionManagers = new StaticConnectionManagers(topology, connectionCreator);
        final InboundConnectionHandler inboundConnectionHandler = new InboundConnectionHandler(
                platformContext,
                this,
                peerIdentifier,
                selfId,
                connectionManagers::newConnection,
                platformContext.getTime());
        // allow other members to create connections to me
        final Address address = addressBook.getAddress(selfId);
        final ConnectionServer connectionServer = new ConnectionServer(
                threadManager, address.getListenPort(), socketFactory, inboundConnectionHandler::handle);
        thingsToStart.add(new StoppableThreadConfiguration<>(threadManager)
                .setPriority(threadConfig.threadPrioritySync())
                .setNodeId(selfId)
                .setComponent(PLATFORM_THREAD_POOL_NAME)
                .setThreadName("connectionServer")
                .setWork(connectionServer)
                .build());

        fallenBehindManager = new FallenBehindManagerImpl(
                addressBook,
                selfId,
                topology,
                statusActionSubmitter,
                () -> getReconnectController().start(),
                platformContext.getConfiguration().getConfigData(ReconnectConfig.class));

        syncManager = new SyncManagerImpl(
                platformContext,
                intakeQueueSizeSupplier,
                fallenBehindManager,
                platformContext.getConfiguration().getConfigData(EventConfig.class));

        final ReconnectConfig reconnectConfig =
                platformContext.getConfiguration().getConfigData(ReconnectConfig.class);

        reconnectThrottle = new ReconnectThrottle(reconnectConfig, platformContext.getTime());

        networkMetrics = new NetworkMetrics(platformContext.getMetrics(), selfId, addressBook);
        platformContext.getMetrics().addUpdater(networkMetrics::update);

        reconnectMetrics = new ReconnectMetrics(platformContext.getMetrics(), addressBook);

        final StateConfig stateConfig = platformContext.getConfiguration().getConfigData(StateConfig.class);

        final LongSupplier getRoundSupplier = () -> {
            try (final ReservedSignedState reservedState = latestCompleteState.get()) {
                if (reservedState == null || reservedState.isNull()) {
                    return ROUND_UNDEFINED;
                }

                return reservedState.get().getRound();
            }
        };

        reconnectHelper = new ReconnectHelper(
                this::pause,
                clearAllPipelinesForReconnect::run,
                swirldStateManager::getConsensusState,
                getRoundSupplier,
                new ReconnectLearnerThrottle(platformContext.getTime(), selfId, reconnectConfig),
                state -> {
                    loadReconnectState.accept(state);
                    syncManager.resetFallenBehind();
                },
                new ReconnectLearnerFactory(
                        platformContext,
                        threadManager,
                        addressBook,
                        reconnectConfig.asyncStreamTimeout(),
                        reconnectMetrics),
                stateConfig);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);

        final EventConfig eventConfig = platformContext.getConfiguration().getConfigData(EventConfig.class);

        syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);

        final ParallelExecutor shadowgraphExecutor = new CachedPoolParallelExecutor(threadManager, "node-sync");
        thingsToStart.add(shadowgraphExecutor);
        final SyncMetrics syncMetrics = new SyncMetrics(platformContext.getMetrics());
        syncShadowgraphSynchronizer = new ShadowgraphSynchronizer(
                platformContext,
                shadowgraph,
                addressBook.getSize(),
                syncMetrics,
                event -> receivedEventHandler.accept(event),
                syncManager,
                intakeEventCounter,
                shadowgraphExecutor);

        reconnectController = new ReconnectController(reconnectConfig, threadManager, reconnectHelper, this::resume);

        final ProtocolConfig protocolConfig = platformContext.getConfiguration().getConfigData(ProtocolConfig.class);

        final Duration hangingThreadDuration = basicConfig.hangingThreadDuration();

        final int permitCount;
        if (syncConfig.onePermitPerPeer()) {
            permitCount = addressBook.getSize() - 1;
        } else {
            permitCount = syncConfig.syncProtocolPermitCount();
        }

        syncPermitProvider = new SyncPermitProvider(platformContext, permitCount);

        buildSyncProtocolThreads(
                platformContext,
                threadManager,
                selfId,
                appVersion,
                intakeQueueSizeSupplier,
                latestCompleteState,
                syncMetrics,
                currentPlatformStatus::get,
                hangingThreadDuration,
                protocolConfig,
                reconnectConfig,
                eventConfig);

        thingsToStart.add(() -> syncProtocolThreads.forEach(StoppableThread::start));
    }

    private void buildSyncProtocolThreads(
            final PlatformContext platformContext,
            final ThreadManager threadManager,
            final NodeId selfId,
            final SoftwareVersion appVersion,
            final LongSupplier intakeQueueSizeSupplier,
            final Supplier<ReservedSignedState> getLatestCompleteState,
            final SyncMetrics syncMetrics,
            final Supplier<PlatformStatus> platformStatusSupplier,
            final Duration hangingThreadDuration,
            final ProtocolConfig protocolConfig,
            final ReconnectConfig reconnectConfig,
            final EventConfig eventConfig) {

        final ProtocolFactory syncProtocolFactory = new SyncProtocolFactory(
                platformContext,
                syncShadowgraphSynchronizer,
                fallenBehindManager,
                syncPermitProvider,
                intakeEventCounter,
                gossipHalted::get,
                () -> intakeQueueSizeSupplier.getAsLong() >= eventConfig.eventIntakeQueueThrottleSize(),
                Duration.ZERO,
                syncMetrics,
                platformStatusSupplier);

        final ProtocolFactory reconnectProtocolFactory = new ReconnectProtocolFactory(
                platformContext,
                threadManager,
                reconnectThrottle,
                getLatestCompleteState,
                reconnectConfig.asyncStreamTimeout(),
                reconnectMetrics,
                reconnectController,
                new DefaultSignedStateValidator(platformContext),
                fallenBehindManager,
                platformStatusSupplier,
                platformContext.getConfiguration());

        final ProtocolFactory heartbeatProtocolFactory = new HeartbeatProtocolFactory(
                Duration.ofMillis(syncConfig.syncProtocolHeartbeatPeriod()), networkMetrics, platformContext.getTime());
        final VersionCompareHandshake versionCompareHandshake =
                new VersionCompareHandshake(appVersion, !protocolConfig.tolerateMismatchedVersion());
        final List<ProtocolRunnable> handshakeProtocols = List.of(versionCompareHandshake);
        for (final NodeId otherId : topology.getNeighbors()) {
            syncProtocolThreads.add(new StoppableThreadConfiguration<>(threadManager)
                    .setPriority(Thread.NORM_PRIORITY)
                    .setNodeId(selfId)
                    .setComponent(PLATFORM_THREAD_POOL_NAME)
                    .setOtherNodeId(otherId)
                    .setThreadName("SyncProtocolWith" + otherId)
                    .setHangingThreadPeriod(hangingThreadDuration)
                    .setWork(new ProtocolNegotiatorThread(
                            connectionManagers.getManager(otherId, topology.shouldConnectTo(otherId)),
                            syncConfig.syncSleepAfterFailedNegotiation(),
                            handshakeProtocols,
                            new NegotiationProtocols(List.of(
                                    heartbeatProtocolFactory.build(otherId),
                                    reconnectProtocolFactory.build(otherId),
                                    syncProtocolFactory.build(otherId)))))
                    .build());
        }
    }

    /**
     * Get the reconnect controller. This method is needed to break a circular dependency.
     */
    private ReconnectController getReconnectController() {
        return reconnectController;
    }

    /**
     * Start gossiping.
     */
    private void start() {
        if (started) {
            throw new IllegalStateException("Gossip already started");
        }
        started = true;
        thingsToStart.forEach(Startable::start);
    }

    /**
     * Stop gossiping.
     */
    private void stop() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        syncManager.haltRequestedObserver("stopping gossip");
        gossipHalted.set(true);
        // wait for all existing syncs to stop. no new ones will be started, since gossip has been halted, and
        // we've fallen behind
        syncPermitProvider.waitForAllPermitsToBeReleased();
        for (final StoppableThread thread : syncProtocolThreads) {
            thread.stop();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void newConnectionOpened(@NonNull final Connection sc) {
        Objects.requireNonNull(sc);
        networkMetrics.connectionEstablished(sc);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void connectionClosed(final boolean outbound, @NonNull final Connection conn) {
        Objects.requireNonNull(conn);
        networkMetrics.recordDisconnect(conn);
    }

    /**
     * Stop gossiping until {@link #resume()} is called. If called when already paused then this has no effect.
     */
    private void pause() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        gossipHalted.set(true);
        syncPermitProvider.waitForAllPermitsToBeReleased();
    }

    /**
     * Resume gossiping. Undoes the effect of {@link #pause()}. Should be called exactly once after each call to
     * {@link #pause()}.
     */
    private void resume() {
        if (!started) {
            throw new IllegalStateException("Gossip not started");
        }
        intakeEventCounter.reset();
        gossipHalted.set(false);

        // Revoke all permits when we begin gossiping again. Presumably we are behind the pack,
        // and so we want to avoid talking to too many peers at once until we've had a chance
        // to properly catch up.
        syncPermitProvider.revokeAll();
    }

    /**
     * Clear the internal state of the gossip engine.
     */
    private void clear() {
        inOrderLinker.clear();
        shadowgraph.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bind(
            @NonNull final WiringModel model,
            @NonNull final BindableInputWire<GossipEvent, Void> eventInput,
            @NonNull final BindableInputWire<EventWindow, Void> eventWindowInput,
            @NonNull final StandardOutputWire<GossipEvent> eventOutput,
            @NonNull final BindableInputWire<NoInput, Void> startInput,
            @NonNull final BindableInputWire<NoInput, Void> stopInput,
            @NonNull final BindableInputWire<NoInput, Void> clearInput,
            @NonNull final BindableInputWire<Duration, Void> systemHealthInput,
            @NonNull final BindableInputWire<PlatformStatus, Void> platformStatusInput) {

        startInput.bindConsumer(ignored -> start());
        stopInput.bindConsumer(ignored -> stop());
        clearInput.bindConsumer(ignored -> clear());

        eventInput.bindConsumer(event -> {
            final EventImpl linkedEvent = inOrderLinker.linkEvent(event);
            if (linkedEvent != null) {
                shadowgraph.addEvent(linkedEvent);
            }
        });

        eventWindowInput.bindConsumer(eventWindow -> {
            inOrderLinker.setEventWindow(eventWindow);
            shadowgraph.updateEventWindow(eventWindow);
        });

        systemHealthInput.bindConsumer(syncPermitProvider::reportUnhealthyDuration);
        platformStatusInput.bindConsumer(currentPlatformStatus::set);

        final boolean useOldStyleIntakeQueue = platformContext
                .getConfiguration()
                .getConfigData(EventConfig.class)
                .useOldStyleIntakeQueue();

        if (useOldStyleIntakeQueue) {
            oldStyleIntakeQueue = new QueueThreadConfiguration<GossipEvent>(threadManager)
                    .setCapacity(10_000)
                    .setThreadName("old_style_intake_queue")
                    .setComponent("platform")
                    .setHandler(eventOutput::forward)
                    .setMetricsConfiguration(
                            new QueueThreadMetricsConfiguration(platformContext.getMetrics()).enableMaxSizeMetric())
                    .build();
            thingsToStart.add(oldStyleIntakeQueue);

            receivedEventHandler = event -> {
                try {
                    oldStyleIntakeQueue.put(event);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted while attempting to enqueue event from gossip", e);
                }
            };

        } else {
            receivedEventHandler = eventOutput::forward;
        }
    }

    /**
     * Get the size of the old style intake queue.
     *
     * @return the size of the old style intake queue
     */
    public int getOldStyleIntakeQueueSize() {
        return oldStyleIntakeQueue.size();
    }
}
