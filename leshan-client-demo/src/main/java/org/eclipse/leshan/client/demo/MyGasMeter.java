/*******************************************************************************
 * Copyright (c) 2022    Sierra Wireless and others.
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
 *     Kamil Milewski @ PLUM sp. z o.o. - THE WHOLE FUCKING FILE
 *******************************************************************************/
package org.eclipse.leshan.client.demo;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.eclipse.leshan.client.resource.BaseInstanceEnabler;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.response.ReadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyGasMeter extends BaseInstanceEnabler {

    private static final Logger LOG = LoggerFactory.getLogger(MyGasMeter.class);
    private static final List<Integer> supportedResources = Arrays.asList(1);
    private static final Random RANDOM = new Random();

    @Override
    public ReadResponse read(ServerIdentity identity, int resourceId) {
        LOG.info("Read on GasMeter resource /{}/{}/{}", getModel().id, getId(), resourceId);
        switch (resourceId) {
        case 1:
            return ReadResponse.success(resourceId, RANDOM.nextFloat(0, 1000));
        default:
            return super.read(identity, resourceId);
        }
    }

    @Override
    public List<Integer> getAvailableResourceIds(ObjectModel objectModel) {
        return supportedResources;
    }
}
