/*
 * Copyright (C) 2018-2022. Huawei Technologies Co., Ltd. All rights reserved.
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
package io.prestosql.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.cache.Weigher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.prestosql.Session;
import io.prestosql.cache.elements.CachedDataKey;
import io.prestosql.cache.elements.CachedDataStorage;
import io.prestosql.execution.QueryIdGenerator;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.SessionPropertyManager;
import io.prestosql.spi.connector.QualifiedObjectName;
import io.prestosql.spi.metadata.TableHandle;
import io.prestosql.spi.security.Identity;
import io.prestosql.utils.HetuConfig;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public class CachedDataManager
{
    private static final Logger LOG = Logger.get(CachedDataManager.class);

    private final Optional<Cache<CachedDataKey, CachedDataStorage>> dataCache;
    private final Optional<Map<CachedDataKey, Map<Long, CachedDataStorage>>> waitingDelete;
    private final CacheStorageMonitor monitor;
    private final Metadata metadata;
    private final AtomicLong currentSize = new AtomicLong();
    private final long cachedDataMaxSize;
    private final String userName;
    private final AtomicBoolean isReady = new AtomicBoolean();

    @Inject
    public CachedDataManager(HetuConfig hetuConfig,
                             CacheStorageMonitor monitor,
                             Metadata metadata,
                             QueryIdGenerator queryIdGenerator,
                             SessionPropertyManager sessionPropertyManager)
    {
        if (hetuConfig.isCteMaterializationEnabled()) {
            Session.SessionBuilder sessionBuilder = Session.builder(sessionPropertyManager)
                    .setIdentity(new Identity(hetuConfig.getCachingUserName(), Optional.empty()))
                    .setSource("cache-manager");
            RemovalListener<CachedDataKey, CachedDataStorage> listener = new RemovalListener<CachedDataKey, CachedDataStorage>()
            {
                @Override
                public void onRemoval(RemovalNotification<CachedDataKey, CachedDataStorage> notification)
                {
                    if (notification.wasEvicted()) {
                        LOG.info("CTE Materialized entry evicted, Cause: %s", notification.getCause().name());
                        if (notification.getValue().getRefCount() <= 0) {
                            Session session = sessionBuilder.setQueryId(queryIdGenerator.createNextQueryId()).build();
                            monitor.stopTableMonitorForModification(notification.getValue(), session);
                            cacheWalk(ImmutableSet.of(notification.getKey()), ((key, cds) -> {
                                Optional<TableHandle> tableHandle = metadata.getTableHandle(session, QualifiedObjectName.valueOf(cds.getDataTable()));
                                if (tableHandle.isPresent()) {
                                    metadata.dropTable(session, tableHandle.get());
                                }
                                return null;
                            }));
                        }
                        else {
                            // mark for dropping when reference count reduce
                            waitingDelete.get()
                                    .computeIfAbsent(notification.getKey(), k -> new ConcurrentHashMap<>())
                                    .put(notification.getValue().getCreateTime(), notification.getValue());
                        }
                    }
                }
            };

            this.dataCache = Optional.of(CacheBuilder.newBuilder()
                    .maximumWeight(hetuConfig.getExecutionDataCacheMaxSize().toBytes())
                    .weigher(new Weigher<CachedDataKey, CachedDataStorage>() {
                        @Override
                        public int weigh(CachedDataKey key, CachedDataStorage value)
                        {
                            return (int) value.getDataSize();
                        }
                    })
                    .removalListener(listener)
                    .build());

            this.waitingDelete = Optional.of(new ConcurrentHashMap<>());
        }
        else {
            this.dataCache = Optional.empty();
            this.waitingDelete = Optional.empty();
        }

        this.monitor = requireNonNull(monitor, "monitor is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.cachedDataMaxSize = hetuConfig.getExecutionDataCacheMaxSize().toBytes();
        this.userName = requireNonNull(hetuConfig, "hetuConfig is null").getCachingUserName();
    }

    public boolean isDataCachedEnabled()
    {
        return dataCache.isPresent() && isReady.get();
    }

    public CachedDataStorage validateAndGet(CachedDataKey dataKey, Session session)
    {
        if (!dataCache.isPresent()) {
            return null;
        }

        CachedDataStorage object = get(dataKey);
        if (object != null && validateCacheEntry(dataKey, object, session)) {
            /* Increment listener count */
            object.grab();
            return object;
        }

        return null;
    }

    public CachedDataStorage done(CachedDataKey dataKey, Session session, long cdsTime)
    {
        if (!dataCache.isPresent()) {
            return null;
        }

        CachedDataStorage object = get(dataKey);
        if (object != null && object.getCreateTime() == cdsTime) {
            /* decrement listener count */
            object.release();
        }

        if (object == null && waitingDelete.get().containsKey(dataKey)) {
            CachedDataStorage deletedCds = waitingDelete.get().get(dataKey).get(cdsTime);
            if (deletedCds != null) {
                deletedCds.release();
                /* Drop the cached data table */
                if (deletedCds.getRefCount() <= 0) {
                    // mark for dropping when reference count reduce
                    monitor.stopTableMonitorForModification(deletedCds, session);
                    Optional<TableHandle> tableHandle = metadata.getTableHandle(session, QualifiedObjectName.valueOf(deletedCds.getDataTable()));
                    if (tableHandle.isPresent()) {
                        metadata.dropTable(session, tableHandle.get());
                    }
                    waitingDelete.get().get(dataKey).remove(cdsTime);
                    if (waitingDelete.get().get(dataKey).isEmpty()) {
                        waitingDelete.get().remove(dataKey);
                    }
                }
            }
        }

        return object;
    }

    public void commit(CachedDataKey dataKey, Session session, long cdsTime)
    {
        if (!dataCache.isPresent()) {
            return;
        }

        LOG.debug("Cache materialization completed for key: %s", dataKey.toString());
        CachedDataStorage data = done(dataKey, session, cdsTime);

        /* Prune the cache if needed */
        if (!isSizeAvailableForCacheLimits(data.getDataSize())) {
            pruneCacheForStaleEntries(data.getDataSize(), session);
        }

        currentSize.addAndGet(data.getDataSize());
    }

    private boolean isSizeAvailableForCacheLimits(long requiredSize)
    {
        return cachedDataMaxSize >= (currentSize.get() + requiredSize);
    }

    private void pruneCacheForStaleEntries(long requiredSize, Session session)
    {
        /* get candidate keys for elimination */
        List<CachedDataStorage> evictionCandidates = ImmutableList.copyOf(dataCache.get().asMap().values())
                .stream()
                .filter(cds -> cds.isCommitted() && cds.getRefCount() <= 0)
                .sorted(Comparator.comparing(CachedDataStorage::getRuntime)
                        .thenComparing(CachedDataStorage::getAccessCount)
                        .thenComparing(CachedDataStorage::getDataSize)
                        .thenComparing(CachedDataStorage::getLastAccessTime)
                        .reversed())
                .collect(toImmutableList());

        /* invalidate the required number of keys only... */
        ImmutableSet.Builder<CachedDataKey> toDelete = ImmutableSet.builder();
        long sizeToFree = requiredSize - (cachedDataMaxSize - currentSize.get());
        for (CachedDataStorage cds : evictionCandidates) {
            if (sizeToFree <= 0) {
                break;
            }

            toDelete.add(cds.getIdentifier());
            sizeToFree -= cds.getDataSize();
        }

        invalidate(toDelete.build(), session);
    }

    public CachedDataStorage get(CachedDataKey dataKey)
    {
        if (!dataCache.isPresent()) {
            return null;
        }

        return dataCache.get().getIfPresent(dataKey);
    }

    private boolean validateCacheEntry(CachedDataKey key, CachedDataStorage object, Session session)
    {
        if (monitor.checkTableValidity(object, session)) {
            return true;
        }

        /* Drop the cached data table */
        if (object.getRefCount() <= 0) {
            // mark for dropping when reference count reduce
            invalidate(ImmutableSet.of(key), session);
        }
        else {
            dataCache.get().invalidate(key);
            waitingDelete.get().computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(object.getCreateTime(), object);
        }
        return false;
    }

    public void put(CachedDataKey key, CachedDataStorage value, Session session)
    {
        if (dataCache.isPresent()) {
            monitor.monitorTableForModification(value, session);
            value.grab();
            dataCache.get().put(key, value);
        }
    }

    public void invalidate(Set<CachedDataKey> keySet, Session session)
    {
        if (dataCache.isPresent()) { /* Add invalidators for storage as cacheWalker */
            cacheWalk(keySet, ((key, cds) -> {
                monitor.stopTableMonitorForModification(cds, session);
                Optional<TableHandle> tableHandle = metadata.getTableHandle(session, QualifiedObjectName.valueOf(cds.getDataTable()));
                if (tableHandle.isPresent()) {
                    metadata.dropTable(session, tableHandle.get());
                }

                return null;
            }));
            dataCache.get().invalidateAll(keySet);
        }
    }

    public void invalidateAll(Session session)
    {
        if (dataCache.isPresent()) { /* Add invalidators for storage as cacheWalker */
            cacheWalkAll(((key, cds) -> {
                monitor.stopTableMonitorForModification(cds, session);
                Identity identity = session.getIdentity();
                identity = new Identity(userName, identity.getGroups(), identity.getPrincipal(), identity.getRoles(), identity.getExtraCredentials());
                Session newSession = session.withUpdatedIdentity(identity);
                Optional<TableHandle> tableHandle = metadata.getTableHandle(newSession, QualifiedObjectName.valueOf(cds.getDataTable()));
                if (tableHandle.isPresent()) {
                    metadata.dropTable(newSession, tableHandle.get());
                }

                return null;
            }));
            dataCache.get().invalidateAll();
        }
    }

    public void cacheWalkAll(BiFunction<CachedDataKey, CachedDataStorage, Void> walker)
    {
        if (dataCache.isPresent() && walker != null) {
            dataCache.get().asMap().forEach((key, value) -> walker.apply(key, value));
        }
    }

    public void cacheWalk(Iterable<CachedDataKey> keySet, BiFunction<CachedDataKey, CachedDataStorage, Void> walker)
    {
        if (dataCache.isPresent() && walker != null) {
            dataCache.get().getAllPresent(keySet).forEach((key, value) -> walker.apply(key, value));
        }
    }

    public void setReady()
    {
        this.isReady.set(true);
    }
}
