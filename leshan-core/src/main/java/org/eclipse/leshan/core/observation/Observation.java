/*******************************************************************************
 * Copyright (c) 2013-2015 Sierra Wireless and others.
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
 *     Michał Wadowski (Orange) - Add Observe-Composite feature.
 *     Michał Wadowski (Orange) - Add Cancel Composite-Observation feature.
 *******************************************************************************/
package org.eclipse.leshan.core.observation;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.leshan.core.node.LwM2mPath;

/**
 * An abstract class for observation of a resource provided by a LWM2M Client.
 */
public abstract class Observation {

    protected final byte[] id;
    protected final String endpoint;
    protected final Map<String, String> context;

    /**
     * An abstract constructor for {@link Observation}.
     *
     * @param id token identifier of the observation
     * @param endpoint client's endpoint (public name treated like IMEI).
     * @param context additional information relative to this observation.
     */
    public Observation(byte[] id, String endpoint, Map<String, String> context) {
        this.id = id;
        this.endpoint = endpoint;
        if (context != null)
            this.context = Collections.unmodifiableMap(new HashMap<>(context));
        else
            this.context = Collections.emptyMap();
    }

    /**
     * Get the id of this observation.
     *
     */
    public byte[] getId() {
        return id;
    }

    /**
     * Get the registration ID link to this observation.
     *
     * @return the registration ID
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * @return the contextual information relative to this observation.
     */
    public Map<String, String> getContext() {
        return context;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((context == null) ? 0 : context.hashCode());
        result = prime * result + Arrays.hashCode(id);
        result = prime * result + ((endpoint == null) ? 0 : endpoint.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Observation other = (Observation) obj;
        if (context == null) {
            if (other.context != null)
                return false;
        } else if (!context.equals(other.context))
            return false;
        if (!Arrays.equals(id, other.id))
            return false;
        if (endpoint == null) {
            if (other.endpoint != null)
                return false;
        } else if (!endpoint.equals(other.endpoint))
            return false;
        return true;
    }

    public abstract List<LwM2mPath> getPaths();

    public abstract boolean removeIfIncluded(LwM2mPath path);
}
