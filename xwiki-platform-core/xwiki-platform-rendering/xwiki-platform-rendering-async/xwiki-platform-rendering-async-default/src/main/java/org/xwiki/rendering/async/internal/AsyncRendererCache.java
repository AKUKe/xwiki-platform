/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.rendering.async.internal;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.xwiki.cache.Cache;
import org.xwiki.cache.CacheEntry;
import org.xwiki.cache.CacheException;
import org.xwiki.cache.CacheManager;
import org.xwiki.cache.config.LRUCacheConfiguration;
import org.xwiki.cache.event.CacheEntryEvent;
import org.xwiki.cache.event.CacheEntryListener;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.descriptor.ComponentRole;
import org.xwiki.component.descriptor.DefaultComponentRole;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.model.reference.EntityReference;

/**
 * Share cache containing the results of the {@link AsyncRenderer} executions.
 * 
 * @version $Id$
 * @since 10.10RC1
 */
@Component(roles = AsyncRendererCache.class)
@Singleton
public class AsyncRendererCache implements Initializable, CacheEntryListener<AsyncRendererJobStatus>
{
    @Inject
    private CacheManager cacheManager;

    private Cache<AsyncRendererJobStatus> asyncCache;

    private Cache<AsyncRendererJobStatus> longCache;

    private Map<EntityReference, Set<String>> referenceMapping = new ConcurrentHashMap<>();

    private Map<Type, Set<String>> roleTypeMapping = new ConcurrentHashMap<>();

    private Map<ComponentRole<?>, Set<String>> roleMapping = new ConcurrentHashMap<>();

    /**
     * @param jobId the job identifier
     * @return the cache key
     */
    public static String toCacheKey(List<String> jobId)
    {
        StringBuilder builder = new StringBuilder();

        for (String element : jobId) {
            builder.append(element.length()).append(':').append(element);
        }

        return builder.toString();
    }

    @Override
    public void initialize() throws InitializationException
    {
        try {
            // Standard cache (long lived but small by default)
            this.longCache =
                this.cacheManager.createNewCache(new LRUCacheConfiguration("rendering.asyncrenderer", 100, 86400));

            // Cache to store asynchronous result kept only for the small period between which the job is finished
            // but it was not been asked yet by the client (short live but big size)
            this.asyncCache = this.cacheManager
                .createNewCache(new LRUCacheConfiguration("rendering.asyncrenderer.nocache", 10000, 600));
        } catch (CacheException e) {
            throw new InitializationException("Failed to initialize cache", e);
        }

        this.longCache.addCacheEntryListener(this);
    }

    /**
     * @param id the if of the job.
     * @return the status associated with the provided key, or {@code null} if there is no value.
     */
    public AsyncRendererJobStatus get(List<String> id)
    {
        String cacheKey = toCacheKey(id);

        // Try standard cache
        AsyncRendererJobStatus status = this.longCache.get(cacheKey);

        if (status != null) {
            return status;
        }

        // Try async cache
        status = this.asyncCache.get(cacheKey);

        if (status != null) {
            // Not removing the entry from the cache because several clients might count on it at the same time so it's
            // a bit dangerous. It will be removed from the cache after 600s (by default).
            // TODO: store an id for each client waiting for the same result so that we can invalidate the cache a bit
            // earlier instead of letting it die of old age (even if it means 600s here)

            return status;
        }

        return null;
    }

    /**
     * @param status the job status to add to the cache
     */
    public void put(AsyncRendererJobStatus status)
    {
        boolean cacheAllowed = status.getRequest().getRenderer().isCacheAllowed();

        // Avoid storing useless stuff in the RAM
        status.dispose();

        String cacheKey = toCacheKey(status.getRequest().getId());

        // If cache is enabled, store the status in the long cache
        if (cacheAllowed) {
            this.longCache.set(cacheKey, status);
        }

        // Asynchronous statuses are stored in the big cache to avoid race condition (result invalidated before it get a
        // chance of being requested)
        if (status.isAsync()) {
            this.asyncCache.set(cacheKey, status);
        }
    }

    /**
     * Remove all the entries the cache contains.
     */
    public void flush()
    {
        this.longCache.removeAll();
        this.asyncCache.removeAll();
    }

    @Override
    public void cacheEntryAdded(CacheEntryEvent<AsyncRendererJobStatus> event)
    {
        CacheEntry<AsyncRendererJobStatus> entry = event.getEntry();
        AsyncRendererJobStatus status = entry.getValue();
        String key = entry.getKey();

        for (EntityReference reference : status.getReferences()) {
            this.referenceMapping.computeIfAbsent(reference, k -> ConcurrentHashMap.newKeySet()).add(key);
        }

        for (Type role : status.getRoleTypes()) {
            this.roleTypeMapping.computeIfAbsent(role, k -> ConcurrentHashMap.newKeySet()).add(key);
        }

        for (ComponentRole<?> role : status.getRoles()) {
            this.roleMapping.computeIfAbsent(role, k -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }

    @Override
    public void cacheEntryRemoved(CacheEntryEvent<AsyncRendererJobStatus> event)
    {
        CacheEntry<AsyncRendererJobStatus> entry = event.getEntry();
        AsyncRendererJobStatus status = entry.getValue();
        String key = entry.getKey();

        remove(key, status.getReferences(), this.referenceMapping);
        remove(key, status.getRoleTypes(), this.roleTypeMapping);
        remove(key, status.getRoles(), this.roleMapping);
    }

    private <T> void remove(String key, Set<T> values, Map<T, Set<String>> mapping)
    {
        for (T value : values) {
            Set<String> keys = mapping.get(value);

            if (keys != null) {
                keys.remove(key);

                if (keys.isEmpty()) {
                    mapping.remove(value);
                }
            }
        }
    }

    @Override
    public void cacheEntryModified(CacheEntryEvent<AsyncRendererJobStatus> event)
    {
        cacheEntryAdded(event);
    }

    /**
     * @param reference the reference for which to clean the cache entries
     */
    public void cleanCache(EntityReference reference)
    {
        if (reference != null) {
            clean(this.referenceMapping.remove(reference));

            // Also clean entries associated to one of the reference parents
            cleanCache(reference.getParent());
        }
    }

    /**
     * @param wiki the wiki for which to clean the cache entries
     */
    public void cleanCache(String wiki)
    {
        for (Map.Entry<EntityReference, Set<String>> entry : this.referenceMapping.entrySet()) {
            EntityReference reference = entry.getKey();

            if (reference.getRoot().getName().equals(wiki)) {
                cleanCache(reference);
            }
        }
    }

    /**
     * @param roleType the type of the component
     * @param roleHint the hint of the component
     */
    public void cleanCache(Type roleType, String roleHint)
    {
        clean(this.roleTypeMapping.remove(roleType));
        clean(this.roleMapping.remove(new DefaultComponentRole<>(roleType, roleHint)));
    }

    /**
     * @param keys the keys of the cache entries to remove
     */
    private void clean(Set<String> keys)
    {
        if (keys != null) {
            for (String key : keys) {
                this.longCache.remove(key);

                // Not cleaning the async cache to avoid race condition (cache invalidated between the moment it was
                // stored and the moment is was used for the first time)
            }
        }
    }
}
