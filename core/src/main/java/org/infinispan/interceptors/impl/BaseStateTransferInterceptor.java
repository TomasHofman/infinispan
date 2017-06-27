package org.infinispan.interceptors.impl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;

import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.TopologyAffectedCommand;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.read.GetAllCommand;
import org.infinispan.commands.read.GetCacheEntryCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.remote.GetKeysInGroupCommand;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.FlagBitSets;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.factories.KnownComponentNames;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.interceptors.DDAsyncInterceptor;
import org.infinispan.interceptors.InvocationFinallyFunction;
import org.infinispan.remoting.RemoteException;
import org.infinispan.remoting.transport.jgroups.SuspectException;
import org.infinispan.statetransfer.OutdatedTopologyException;
import org.infinispan.statetransfer.StateTransferLock;
import org.infinispan.statetransfer.StateTransferManager;
import org.infinispan.topology.CacheTopology;
import org.infinispan.util.concurrent.CompletableFutures;
import org.infinispan.util.concurrent.TimeoutException;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * A base class for a state transfer interceptor. It contains the base code to avoid duplicating in the two current
 * different implementations.
 * <p/>
 * Also, it has some utilities methods with the most common logic.
 *
 * @author Pedro Ruivo
 * @since 9.0
 */
public abstract class BaseStateTransferInterceptor extends DDAsyncInterceptor {
   private final boolean trace = getLog().isTraceEnabled();
   private final InvocationFinallyFunction handleReadCommandReturn = this::handleReadCommandReturn;

   private StateTransferManager stateTransferManager;
   protected StateTransferLock stateTransferLock;
   private Executor remoteExecutor;
   private DistributionManager distributionManager;
   private ScheduledExecutorService timeoutExecutor;

   private long transactionDataTimeout;

   private final InvocationFinallyFunction handleLocalGetKeysInGroupReturn = this::handleLocalGetKeysInGroupReturn;

   @Inject
   public void init(StateTransferLock stateTransferLock, Configuration configuration,
                    StateTransferManager stateTransferManager, DistributionManager distributionManager,
                    @ComponentName(KnownComponentNames.TIMEOUT_SCHEDULE_EXECUTOR) ScheduledExecutorService timeoutExecutor,
                    @ComponentName(KnownComponentNames.REMOTE_COMMAND_EXECUTOR) Executor remoteExecutor) {
      this.stateTransferLock = stateTransferLock;
      this.stateTransferManager = stateTransferManager;
      this.distributionManager = distributionManager;
      this.timeoutExecutor = timeoutExecutor;
      this.remoteExecutor = remoteExecutor;
      transactionDataTimeout = configuration.clustering().remoteTimeout();
   }

   @Override
   public Object visitGetKeysInGroupCommand(InvocationContext ctx, GetKeysInGroupCommand command) throws Throwable {
      updateTopologyId(command);

      if (ctx.isOriginLocal()) {
         return invokeNextAndHandle(ctx, command, handleLocalGetKeysInGroupReturn);
      } else {
         return invokeNextThenAccept(ctx, command, (rCtx, rCommand, rv) -> {
            GetKeysInGroupCommand cmd = (GetKeysInGroupCommand) rCommand;
            final int commandTopologyId = cmd.getTopologyId();
            String groupName = cmd.getGroupName();
            if (currentTopologyId() != commandTopologyId &&
                  distributionManager.getCacheTopology().isWriteOwner(groupName)) {
               throw new OutdatedTopologyException(
                     "Cache topology changed while the command was executing: expected " +
                           commandTopologyId + ", got " + currentTopologyId());
            }
         });
      }
   }

   private Object handleLocalGetKeysInGroupReturn(InvocationContext ctx, VisitableCommand command, Object rv,
                                                  Throwable throwable) throws Throwable {
      GetKeysInGroupCommand cmd = (GetKeysInGroupCommand) command;
      final int commandTopologyId = cmd.getTopologyId();
      boolean shouldRetry;
      if (throwable != null) {
         // Must retry if we got an OutdatedTopologyException or SuspectException
         Throwable ce = throwable;
         while (ce instanceof RemoteException) {
            ce = ce.getCause();
         }
         shouldRetry = ce instanceof OutdatedTopologyException || ce instanceof SuspectException;
      } else {
         // Only check the topology id if if we are an owner
         shouldRetry = currentTopologyId() != commandTopologyId &&
               distributionManager.getCacheTopology().isWriteOwner(cmd.getGroupName());
      }
      if (shouldRetry) {
         logRetry(cmd);
         // We increment the topology id so that updateTopologyIdAndWaitForTransactionData waits for the next topology.
         // Without this, we could retry the command too fast and we could get the OutdatedTopologyException again.
         int newTopologyId = Math.max(currentTopologyId(), commandTopologyId + 1);
         cmd.setTopologyId(newTopologyId);
         CompletableFuture<Void> transactionDataFuture = stateTransferLock.transactionDataFuture(newTopologyId);
         return retryWhenDone(transactionDataFuture, newTopologyId, ctx, command, handleLocalGetKeysInGroupReturn);
      } else {
         return valueOrException(rv, throwable);
      }

   }

   protected final void logRetry(VisitableCommand command) {
      if (trace) {
         getLog().tracef("Retrying command because of topology change: %s", command);
      }
   }

   protected final int currentTopologyId() {
      final CacheTopology cacheTopology = stateTransferManager.getCacheTopology();
      return cacheTopology == null ? -1 : cacheTopology.getTopologyId();
   }

   protected final void updateTopologyId(TopologyAffectedCommand command) {
      // set the topology id if it was not set before (ie. this is local command)
      // TODO Make tx commands extend FlagAffectedCommand so we can use CACHE_MODE_LOCAL in TransactionTable.cleanupStaleTransactions
      if (command.getTopologyId() == -1) {
         CacheTopology cacheTopology = stateTransferManager.getCacheTopology();
         if (cacheTopology != null) {
            command.setTopologyId(cacheTopology.getTopologyId());
         }
      }
   }

   protected <T extends VisitableCommand> Object retryWhenDone(CompletableFuture<Void> future, int topologyId,
                                                               InvocationContext ctx, T command,
                                                               InvocationFinallyFunction callback) {
      if (future.isDone()) {
         getLog().tracef("Retrying command %s for topology %d", command, topologyId);
         return invokeNextAndHandle(ctx, command, callback);
      } else {
         CancellableRetry<T> cancellableRetry = new CancellableRetry<>(command, topologyId);
         // We have to use handleAsync and rethrow the exception in the handler, rather than
         // thenComposeAsync(), because if `future` completes with an exception we want to continue in remoteExecutor
         CompletableFuture<Void> retryFuture = future.handleAsync(cancellableRetry, remoteExecutor);
         cancellableRetry.setRetryFuture(retryFuture);
         // We want to time out the current command future, not the main topology-waiting future,
         // but the command future can take longer time to finish.
         ScheduledFuture<?> timeoutFuture = timeoutExecutor.schedule(cancellableRetry, transactionDataTimeout, TimeUnit.MILLISECONDS);
         cancellableRetry.setTimeoutFuture(timeoutFuture);
         return makeStage(asyncInvokeNext(ctx, command, retryFuture)).andHandle(ctx, command, callback);
      }
   }

   @Override
   public Object visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command) throws Throwable {
      return handleReadCommand(ctx, command);
   }

   @Override
   public Object visitGetCacheEntryCommand(InvocationContext ctx, GetCacheEntryCommand command)
         throws Throwable {
      return handleReadCommand(ctx, command);
   }

   @Override
   public Object visitGetAllCommand(InvocationContext ctx, GetAllCommand command) throws Throwable {
      return handleReadCommand(ctx, command);
   }

   protected <C extends VisitableCommand & TopologyAffectedCommand & FlagAffectedCommand> Object handleReadCommand(
         InvocationContext ctx, C command) {
      return isLocalOnly(command) ? invokeNext(ctx, command) :
            updateAndInvokeNextRead(ctx, command);
   }

   private <C extends VisitableCommand & TopologyAffectedCommand> Object updateAndInvokeNextRead(InvocationContext ctx, C command) {
      updateTopologyId(command);
      return invokeNextAndHandle(ctx, command,handleReadCommandReturn);
   }

   private Object handleReadCommandReturn(InvocationContext rCtx, VisitableCommand rCommand, Object rv, Throwable t)
         throws Throwable {
      if (t == null)
         return rv;

      Throwable ce = t;
      while (ce instanceof RemoteException) {
         ce = ce.getCause();
      }
      final CacheTopology cacheTopology = stateTransferManager.getCacheTopology();
      int currentTopologyId = cacheTopology == null ? -1 : cacheTopology.getTopologyId();
      TopologyAffectedCommand cmd = (TopologyAffectedCommand) rCommand;
      if (ce instanceof SuspectException) {
         if (trace)
            getLog().tracef("Retrying command because of suspected node, current topology is %d: %s",
                  currentTopologyId, rCommand);
         // It is possible that current topology is actual but the view still contains a node that's about to leave;
         // a broadcast to all nodes then can end with suspect exception, but we won't get any new topology.
         // An example of this situation is when a node sends leave - topology can be installed before the new view.
         // To prevent suspect exceptions use SYNCHRONOUS_IGNORE_LEAVERS response mode.
         if (cacheTopology != null && currentTopologyId == cmd.getTopologyId() && !cacheTopology.getActualMembers().contains(((SuspectException) ce).getSuspect())) {
            // TODO: provide a test case
            throw new IllegalStateException("Command was not sent with SYNCHRONOUS_IGNORE_LEAVERS?");
         }
      } else if (ce instanceof OutdatedTopologyException) {
         if (trace)
            getLog().tracef("Retrying command because of topology change, current topology is %d: %s",
                  currentTopologyId, cmd);
      } else {
         throw t;
      }
      // We increment the topology to wait for the next topology.
      // Without this, we could retry the command too fast and we could get the OutdatedTopologyException again.
      int newTopologyId = getNewTopologyId(ce, currentTopologyId, cmd);
      cmd.setTopologyId(newTopologyId);
      ((FlagAffectedCommand)rCommand).addFlags(FlagBitSets.COMMAND_RETRY);
      CompletableFuture<Void> topologyFuture = stateTransferLock.topologyFuture(newTopologyId);
      return retryWhenDone(topologyFuture, newTopologyId, rCtx, rCommand, handleReadCommandReturn);
   }

   protected int getNewTopologyId(Throwable ce, int currentTopologyId, TopologyAffectedCommand command) {
      int requestedTopologyId = command.getTopologyId() + 1;
      if (ce instanceof OutdatedTopologyException) {
         OutdatedTopologyException ote = (OutdatedTopologyException) ce;
         if (ote.requestedTopologyId >= 0) {
            requestedTopologyId = ote.requestedTopologyId;
         }
      }
      return Math.max(currentTopologyId, requestedTopologyId);
   }

   protected boolean isLocalOnly(FlagAffectedCommand command) {
      return command.hasAnyFlag(FlagBitSets.CACHE_MODE_LOCAL);
   }

   protected abstract Log getLog();

   private static class CancellableRetry<T extends VisitableCommand> implements BiFunction<Void, Throwable, Void>, Runnable {
      private static final AtomicReferenceFieldUpdater<CancellableRetry, Throwable> cancellableRetryUpdater
            = AtomicReferenceFieldUpdater.newUpdater(CancellableRetry.class, Throwable.class, "cancelled");
      private static final AtomicReferenceFieldUpdater<CancellableRetry, Object> timeoutFutureUpdater
            = AtomicReferenceFieldUpdater.newUpdater(CancellableRetry.class, Object.class, "timeoutFuture");

      private static final Log log = LogFactory.getLog(CancellableRetry.class);
      private static final Throwable DUMMY = new Throwable("Command is retried"); // should not be ever thrown

      private final T command;
      private final int topologyId;
      private volatile Throwable cancelled = null;
      // retryFuture is not volatile because it is used only in the timeout handler = run()
      // and that is scheduled after retryFuture is set
      private CompletableFuture<Void> retryFuture;
      // ScheduledFuture does not have any dummy implementations, so we'll use plain Object as the field
      @SuppressWarnings("unused")
      private volatile Object timeoutFuture;

      CancellableRetry(T command, int topologyId) {
         this.command = command;
         this.topologyId = topologyId;
      }

      /**
       * This is called when the topology future completes (successfully or exceptionally)
       */
      @Override
      public Void apply(Void nil, Throwable throwable) {
         if (!timeoutFutureUpdater.compareAndSet(this, null, DUMMY)) {
            ((ScheduledFuture) timeoutFuture).cancel(false);
         }

         if (throwable != null) {
            throw CompletableFutures.asCompletionException(throwable);
         }
         if (!cancellableRetryUpdater.compareAndSet(this, null, DUMMY)) {
            log.tracef("Not retrying command %s as it has been cancelled.", command);
            throw CompletableFutures.asCompletionException(cancelled);
         }
         log.tracef("Retrying command %s for topology %d", command, topologyId);
         return null;
      }

      /**
       * This is called when the timeout elapses.
       */
      @Override
      public void run() {
         TimeoutException timeoutException = new TimeoutException("Timed out waiting for topology " + topologyId);
         if (cancellableRetryUpdater.compareAndSet(this, null, timeoutException)) {
            retryFuture.completeExceptionally(timeoutException);
         }
      }

      void setRetryFuture(CompletableFuture<Void> retryFuture) {
         this.retryFuture = retryFuture;
      }

      void setTimeoutFuture(ScheduledFuture<?> timeoutFuture) {
         if (!timeoutFutureUpdater.compareAndSet(this, null, timeoutFuture)) {
            timeoutFuture.cancel(false);
         }
      }
   }
}