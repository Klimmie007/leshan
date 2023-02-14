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
 *     Kamil Milewski @ PLUM sp. z o.o - saving observations to file
 *******************************************************************************/

package org.eclipse.leshan.core.model;

import java.util.List;

import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.observation.Observation;

public class ObservationModel {

    public final String ep;

    public final List<LwM2mPath> paths;

    public ObservationModel(String endpoint, List<LwM2mPath> paths) {
        this.ep = endpoint;
        this.paths = paths;
    }

    public ObservationModel(Observation observation) {
        this(observation.getEndpoint(), observation.getPaths());
    }

    @Override
    public String toString() {
        return String.format("ObservationModel [ep=%s, paths=%s]", ep, paths.toString());
    }
}
