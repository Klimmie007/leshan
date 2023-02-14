/*******************************************************************************
 * Copyright (c) 2016 Sierra Wireless and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 *
 * Contributors:
 *     Sierra Wireless - initial API and implementation
 *     Achim Kraus (Bosch Software Innovations GmbH) - replace serialize/parse in
 *                                                     unsafeGetObservation() with
 *                                                     ObservationUtil.shallowClone.
 *                                                     Reuse already created Key in
 *                                                     setContext().
 *     Achim Kraus (Bosch Software Innovations GmbH) - rename CorrelationContext to
 *                                                     EndpointContext
 *     Achim Kraus (Bosch Software Innovations GmbH) - update to modified
 *                                                     ObservationStore API
 *     Michał Wadowski (Orange)                      - Add Observe-Composite feature.
 *******************************************************************************/
package org.eclipse.leshan.server.californium.registration;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.Token;
import org.eclipse.californium.core.observe.ObservationUtil;
import org.eclipse.californium.elements.EndpointContext;
import org.eclipse.leshan.core.Destroyable;
import org.eclipse.leshan.core.Startable;
import org.eclipse.leshan.core.Stoppable;
import org.eclipse.leshan.core.californium.ObserveUtil;
import org.eclipse.leshan.core.observation.CompositeObservation;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.observation.SingleObservation;
import org.eclipse.leshan.core.request.Identity;
import org.eclipse.leshan.core.util.NamedThreadFactory;
import org.eclipse.leshan.server.registration.Deregistration;
import org.eclipse.leshan.server.registration.ExpirationListener;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.registration.RegistrationUpdate;
import org.eclipse.leshan.server.registration.UpdatedRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An in memory store for registration and observation.
 */
public class InMemoryRegistrationStore implements CaliforniumRegistrationStore, Startable, Stoppable, Destroyable {
    private final Logger LOG = LoggerFactory.getLogger(InMemoryRegistrationStore.class);

    // Data structure
    private final Map<String /* end-point */, Registration> regsByEp = new HashMap<>();
    private final Map<InetSocketAddress, Registration> regsByAddr = new HashMap<>();
    private final Map<String /* reg-id */, Registration> regsByRegId = new HashMap<>();
    private final Map<Identity, Registration> regsByIdentity = new HashMap<>();
    private Map<Token, org.eclipse.californium.core.observe.Observation> obsByToken = new HashMap<>();
    private Map<String /* end-point */, Set<Token>> tokensByEp = new HashMap<>();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Listener use to notify when a registration expires
    private ExpirationListener expirationListener;

    private final ScheduledExecutorService schedExecutor;
    private ScheduledFuture<?> cleanerTask;
    private boolean started = false;
    private final long cleanPeriod; // in seconds

    public InMemoryRegistrationStore() {
        this(2); // default clean period : 2s
    }

    public InMemoryRegistrationStore(long cleanPeriodInSec) {
        this(Executors.newScheduledThreadPool(1,
                new NamedThreadFactory(String.format("InMemoryRegistrationStore Cleaner (%ds)", cleanPeriodInSec))),
                cleanPeriodInSec);
    }

    public InMemoryRegistrationStore(ScheduledExecutorService schedExecutor, long cleanPeriodInSec) {
        this.schedExecutor = schedExecutor;
        this.cleanPeriod = cleanPeriodInSec;
    }

    /* *************** Saving observations to file ************ */

//    public void saveToFile(String filePath)
//    {
//
//    }

    /* *************** Leshan Registration API **************** */

    @Override
    public Deregistration addRegistration(Registration registration) {
        try {
            lock.writeLock().lock();

            Registration registrationRemoved = regsByEp.put(registration.getEndpoint(), registration);
            regsByRegId.put(registration.getId(), registration);
            regsByIdentity.put(registration.getIdentity(), registration);
            // If a registration is already associated to this address we don't care as we only want to keep the most
            // recent binding.
            regsByAddr.put(registration.getSocketAddress(), registration);
            if (registrationRemoved != null) {
                Collection<Observation> observationsRemoved = unsafeRemoveAllObservations(
                        registrationRemoved.getEndpoint());
                if (!registrationRemoved.getSocketAddress().equals(registration.getSocketAddress())) {
                    removeFromMap(regsByAddr, registrationRemoved.getSocketAddress(), registrationRemoved);
                }
                if (!registrationRemoved.getId().equals(registration.getId())) {
                    removeFromMap(regsByRegId, registrationRemoved.getId(), registrationRemoved);
                }
                if (!registrationRemoved.getIdentity().equals(registration.getIdentity())) {
                    removeFromMap(regsByIdentity, registrationRemoved.getIdentity(), registrationRemoved);
                }
                return new Deregistration(registrationRemoved, observationsRemoved);
            }
        } finally {
            lock.writeLock().unlock();
        }
        return null;
    }

    @Override
    public UpdatedRegistration updateRegistration(RegistrationUpdate update) {
        try {
            lock.writeLock().lock();

            Registration registration = getRegistration(update.getRegistrationId());
            if (registration == null) {
                return null;
            } else {
                Registration updatedRegistration = update.update(registration);
                regsByEp.put(updatedRegistration.getEndpoint(), updatedRegistration);
                // If registration is already associated to this address we don't care as we only want to keep the most
                // recent binding.
                regsByAddr.put(updatedRegistration.getSocketAddress(), updatedRegistration);
                if (!registration.getSocketAddress().equals(updatedRegistration.getSocketAddress())) {
                    removeFromMap(regsByAddr, registration.getSocketAddress(), registration);
                }
                regsByIdentity.put(updatedRegistration.getIdentity(), updatedRegistration);
                if (!registration.getIdentity().equals(updatedRegistration.getIdentity())) {
                    removeFromMap(regsByIdentity, registration.getIdentity(), registration);
                }

                regsByRegId.put(updatedRegistration.getId(), updatedRegistration);

                return new UpdatedRegistration(registration, updatedRegistration);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Registration getRegistration(String registrationId) {
        try {
            lock.readLock().lock();
            return regsByRegId.get(registrationId);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Registration getRegistrationByEndpoint(String endpoint) {
        try {
            lock.readLock().lock();
            return regsByEp.get(endpoint);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Registration getRegistrationByAdress(InetSocketAddress address) {
        try {
            lock.readLock().lock();
            return regsByAddr.get(address);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Registration getRegistrationByIdentity(Identity identity) {
        try {
            lock.readLock().lock();
            return regsByIdentity.get(identity);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Iterator<Registration> getAllRegistrations() {
        try {
            lock.readLock().lock();
            return new ArrayList<>(regsByEp.values()).iterator();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Deregistration removeRegistration(String registrationId, boolean expired) {
        try {
            lock.writeLock().lock();

            Registration registration = getRegistration(registrationId);
            if (registration != null) {
                Collection<Observation> observationsRemoved;
                // TODO potentially also don't remove them on deregistration
                if (expired) {
                    observationsRemoved = unsafeGetObservations(registration.getEndpoint());
                } else {
                    observationsRemoved = unsafeRemoveAllObservations(registration.getEndpoint());
                    regsByEp.remove(registration.getEndpoint());
                }
                removeFromMap(regsByAddr, registration.getSocketAddress(), registration);
                removeFromMap(regsByRegId, registration.getId(), registration);
                removeFromMap(regsByIdentity, registration.getIdentity(), registration);
                return new Deregistration(registration, observationsRemoved);
            }
            return null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /* *************** Leshan Observation API **************** */

    /*
     * The observation is not persisted here, it is done by the Californium layer (in the implementation of the
     * org.eclipse.californium.core.observe.ObservationStore#add method)
     */
    @Override
    public Collection<Observation> addObservation(String endpoint, Observation observation) {

        List<Observation> removed = new ArrayList<>();

        try {
            lock.writeLock().lock();
            // cancel existing observations for the same path and endpoint
            for (Observation obs : unsafeGetObservations(endpoint)) {
                if (!Arrays.equals(observation.getId(), obs.getId()) && areTheSamePaths(observation, obs)) {
                    unsafeRemoveObservation(new Token(obs.getId()));
                    removed.add(obs);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        return removed;
    }

    private boolean areTheSamePaths(Observation observation, Observation obs) {
        if (observation instanceof SingleObservation && obs instanceof SingleObservation) {
            return ((SingleObservation) observation).getPath().equals(((SingleObservation) obs).getPath());
        }
        if (observation instanceof CompositeObservation && obs instanceof CompositeObservation) {
            return ((CompositeObservation) observation).getPaths().equals(((CompositeObservation) obs).getPaths());
        }
        return false;
    }

    @Override
    public Observation removeObservation(String endpoint, byte[] observationId) {
        try {
            lock.writeLock().lock();
            Token token = new Token(observationId);
            Observation observation = build(unsafeGetObservation(token));
            if (observation != null && endpoint.equals(observation.getEndpoint())) {
                unsafeRemoveObservation(token);
                return observation;
            }
            return null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Observation getObservation(String endpoint, byte[] observationId) {
        try {
            lock.readLock().lock();
            Observation observation = build(unsafeGetObservation(new Token(observationId)));
            if (observation != null && endpoint.equals(observation.getEndpoint())) {
                return observation;
            }
            LOG.error("Didn't find observation [{}] for endpoint [{}]", new String(observationId), endpoint);
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Collection<Observation> getObservations(String endpoint) {
        try {
            lock.readLock().lock();
            return unsafeGetObservations(endpoint);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Collection<Observation> removeObservations(String endpoint) {
        try {
            lock.writeLock().lock();
            return unsafeRemoveAllObservations(endpoint);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /* *************** Californium ObservationStore API **************** */

    @Override
    public org.eclipse.californium.core.observe.Observation putIfAbsent(Token token,
            org.eclipse.californium.core.observe.Observation obs) {
        return add(token, obs, true);
    }

    @Override
    public org.eclipse.californium.core.observe.Observation put(Token token,
            org.eclipse.californium.core.observe.Observation obs) {
        return add(token, obs, false);
    }

    private org.eclipse.californium.core.observe.Observation add(Token token,
            org.eclipse.californium.core.observe.Observation obs, boolean ifAbsent) {
        org.eclipse.californium.core.observe.Observation previousObservation = null;
        if (obs != null) {
            try {
                lock.writeLock().lock();

                String endpoint = ObserveUtil.extractEndpoint(obs);
                if (ifAbsent) {
                    if (!obsByToken.containsKey(token))
                        previousObservation = obsByToken.put(token, obs);
                    else
                        return obsByToken.get(token);
                } else {
                    previousObservation = obsByToken.put(token, obs);
                }
                if (!tokensByEp.containsKey(endpoint)) {
                    tokensByEp.put(endpoint, new HashSet<Token>());
                }
                tokensByEp.get(endpoint).add(token);

                // log any collisions
                if (previousObservation != null) {
                    LOG.warn(
                            "Token collision ? observation from request [{}] will be replaced by observation from request [{}] ",
                            previousObservation.getRequest(), obs.getRequest());
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
        return previousObservation;
    }

    @Override
    public org.eclipse.californium.core.observe.Observation get(Token token) {
        try {
            lock.readLock().lock();
            return unsafeGetObservation(token);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void setContext(Token token, EndpointContext ctx) {
        try {
            lock.writeLock().lock();
            org.eclipse.californium.core.observe.Observation obs = obsByToken.get(token);
            if (obs != null) {
                obsByToken.put(token, new org.eclipse.californium.core.observe.Observation(obs.getRequest(), ctx));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(Token token) {
        try {
            lock.writeLock().lock();
            unsafeRemoveObservation(token);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /* *************** Observation utility functions **************** */

    private org.eclipse.californium.core.observe.Observation unsafeGetObservation(Token token) {
        org.eclipse.californium.core.observe.Observation obs = obsByToken.get(token);
        return ObservationUtil.shallowClone(obs);
    }

    private void unsafeRemoveObservation(Token observationId) {
        org.eclipse.californium.core.observe.Observation removed = obsByToken.remove(observationId);

        if (removed != null) {
            String endpoint = ObserveUtil.extractEndpoint(removed);
            Set<Token> tokens = tokensByEp.get(endpoint);
            tokens.remove(observationId);
            if (tokens.isEmpty()) {
                tokensByEp.remove(endpoint);
            }
        }
    }

    private Collection<Observation> unsafeRemoveAllObservations(String endpoint) {
        Collection<Observation> removed = new ArrayList<>();
        Set<Token> tokens = tokensByEp.get(endpoint);
        if (tokens != null) {
            for (Token token : tokens) {
                Observation observationRemoved = build(obsByToken.remove(token));
                if (observationRemoved != null) {
                    removed.add(observationRemoved);
                }
            }
        }
        tokensByEp.remove(endpoint);
        return removed;
    }

    private Collection<Observation> unsafeGetObservations(String endpoint) {
        Collection<Observation> result = new ArrayList<>();
        Set<Token> tokens = tokensByEp.get(endpoint);
        if (tokens != null) {
            for (Token token : tokens) {
                Observation obs = build(unsafeGetObservation(token));
                if (obs != null) {
                    result.add(obs);
                }
            }
        }
        return result;
    }

    private Observation build(org.eclipse.californium.core.observe.Observation cfObs) {
        if (cfObs == null)
            return null;

        if (cfObs.getRequest().getCode() == CoAP.Code.GET) {
            return ObserveUtil.createLwM2mObservation(cfObs.getRequest());
        } else if (cfObs.getRequest().getCode() == CoAP.Code.FETCH) {
            return ObserveUtil.createLwM2mCompositeObservation(cfObs.getRequest());
        } else {
            throw new IllegalStateException("Observation request can be GET or FETCH only");
        }
    }

    /* *************** Observation fetch ****************** */

    @Override
    public List<Observation> getAllObservations() {
        List<Observation> observations = new ArrayList<>();
        obsByToken.values().forEach(new Consumer<org.eclipse.californium.core.observe.Observation>() {
            @Override
            public void accept(org.eclipse.californium.core.observe.Observation observation) {
                observations.add(build(observation));
            }
        });
        return observations;
    }

    /* *************** Expiration handling **************** */

    @Override
    public void setExpirationListener(ExpirationListener listener) {
        this.expirationListener = listener;
    }

    /**
     * start the registration store, will start regular cleanup of dead registrations.
     */
    @Override
    public synchronized void start() {
        if (!started) {
            started = true;
            cleanerTask = schedExecutor.scheduleAtFixedRate(new Cleaner(), cleanPeriod, cleanPeriod, TimeUnit.SECONDS);
        }
    }

    /**
     * Stop the underlying cleanup of the registrations.
     */
    @Override
    public synchronized void stop() {
        if (started) {
            started = false;
            if (cleanerTask != null) {
                cleanerTask.cancel(false);
                cleanerTask = null;
            }
        }
    }

    /**
     * Destroy "cleanup" scheduler.
     */
    @Override
    public synchronized void destroy() {
        started = false;
        schedExecutor.shutdownNow();
        try {
            schedExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.warn("Destroying InMemoryRegistrationStore was interrupted.", e);
        }
    }

    private class Cleaner implements Runnable {

        @Override
        public void run() {
            try {
                Collection<Registration> allRegs = new ArrayList<>();
                try {
                    lock.readLock().lock();
                    allRegs.addAll(regsByEp.values());
                } finally {
                    lock.readLock().unlock();
                }

                for (Registration reg : allRegs) {
                    if (!reg.isAlive()) {
                        // force de-registration
                        Deregistration removedRegistration = removeRegistration(reg.getId(), true);
                        expirationListener.registrationExpired(removedRegistration.getRegistration(),
                                removedRegistration.getObservations());
                    }
                }
            } catch (Exception e) {
                LOG.warn("Unexpected Exception while registration cleaning", e);
            }
        }
    }

    // boolean remove(Object key, Object value) exist only since java8
    // So this method is here only while we want to support java 7
    protected <K, V> boolean removeFromMap(Map<K, V> map, K key, V value) {
        if (map.containsKey(key) && Objects.equals(map.get(key), value)) {
            map.remove(key);
            return true;
        } else
            return false;
    }

    @Override
    public void setExecutor(ScheduledExecutorService executor) {
        // TODO sould we reuse californium executor ?
    }
}
