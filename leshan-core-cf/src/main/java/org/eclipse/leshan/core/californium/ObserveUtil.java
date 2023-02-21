/*******************************************************************************
 * Copyright (c) 2017 Sierra Wireless and others.
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
 *     Kamil Milewski @ PLUM sp. z o.o. - Endpoint context extraction
 *******************************************************************************/
package org.eclipse.leshan.core.californium;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.observation.CompositeObservation;
import org.eclipse.leshan.core.observation.SingleObservation;
import org.eclipse.leshan.core.request.ContentFormat;
import org.eclipse.leshan.core.request.ObserveCompositeRequest;
import org.eclipse.leshan.core.request.ObserveRequest;

/**
 * Utility functions to help to handle observation in Leshan.
 */
public class ObserveUtil {

    /* keys used to populate the request context */
    public static final String CTX_ENDPOINT = "leshan-endpoint";
    public static final String CTX_REGID = "leshan-regId";
    public static final String CTX_LWM2M_PATH = "leshan-path";

    /**
     * Create a LWM2M observation from a CoAP request.
     */
    public static SingleObservation createLwM2mObservation(Request request) {
        ObserveCommon observeCommon = new ObserveCommon(request);

        if (observeCommon.lwm2mPaths.size() != 1) {
            throw new IllegalStateException(
                    "1 path is expected in observe request context but was " + observeCommon.lwm2mPaths);
        }

        return new SingleObservation(request.getToken().getBytes(), observeCommon.ep, observeCommon.lwm2mPaths.get(0),
                observeCommon.responseContentFormat, observeCommon.context);
    }

    public static CompositeObservation createLwM2mCompositeObservation(Request request) {
        ObserveCommon observeCommon = new ObserveCommon(request);

        return new CompositeObservation(request.getToken().getBytes(), observeCommon.ep, observeCommon.lwm2mPaths,
                observeCommon.requestContentFormat, observeCommon.responseContentFormat, observeCommon.context);
    }

    private static class ObserveCommon {
        String regId, ep;
        Map<String, String> context;
        List<LwM2mPath> lwm2mPaths;
        ContentFormat requestContentFormat;
        ContentFormat responseContentFormat;

        public ObserveCommon(Request request) {
            if (request.getUserContext() == null) {
                throw new IllegalStateException("missing request context");
            }

            context = new HashMap<>();

            for (Entry<String, String> ctx : request.getUserContext().entrySet()) {
                switch (ctx.getKey()) {
                case CTX_REGID:
                    regId = ctx.getValue();
                    break;
                case CTX_LWM2M_PATH:
                    lwm2mPaths = getPathsFromContext(request.getUserContext());
                    break;
                case CTX_ENDPOINT:
                    ep = ctx.getValue();
                    break;
                default:
                    context.put(ctx.getKey(), ctx.getValue());
                }
            }

            if (lwm2mPaths == null || lwm2mPaths.size() == 0) {
                throw new IllegalStateException("missing path in request context");
            }

            if (request.getOptions().hasContentFormat()) {
                requestContentFormat = ContentFormat.fromCode(request.getOptions().getContentFormat());
            }

            if (request.getOptions().hasAccept()) {
                responseContentFormat = ContentFormat.fromCode(request.getOptions().getAccept());
            }
        }
    }

    /**
     * Extract {@link LwM2mPath} list from encoded information in user context.
     *
     * @param userContext user context
     * @return the list of {@link LwM2mPath}
     */
    public static List<LwM2mPath> getPathsFromContext(Map<String, String> userContext) {
        if (userContext.containsKey(CTX_LWM2M_PATH)) {
            List<LwM2mPath> lwm2mPaths = new ArrayList<>();
            String pathsEncoded = userContext.get(CTX_LWM2M_PATH);

            for (String path : pathsEncoded.split("\n")) {
                lwm2mPaths.add(new LwM2mPath(path));
            }
            return lwm2mPaths;
        }
        return null;
    }

    /**
     * Create a CoAP observe request context with specific keys needed for internal Leshan working.
     */
    public static Map<String, String> createCoapObserveRequestContext(String endpoint, String registrationId,
            ObserveRequest request) {
        Map<String, String> context = new HashMap<>();
        context.put(CTX_ENDPOINT, endpoint);
        context.put(CTX_REGID, registrationId);

        addPathsIntoContext(context, Collections.singletonList(request.getPath()));

        context.putAll(request.getContext());
        return context;
    }

    public static Map<String, String> createCoapObserveCompositeRequestContext(String endpoint, String registrationId,
            ObserveCompositeRequest request) {
        Map<String, String> context = new HashMap<>();
        context.put(CTX_ENDPOINT, endpoint);
        context.put(CTX_REGID, registrationId);

        addPathsIntoContext(context, request.getPaths());

        context.putAll(request.getContext());
        return context;
    }

    /**
     * Update user context with encoded list of {@link LwM2mPath}.
     *
     * @param context user context
     * @param paths the list of {@link LwM2mPath}
     */
    public static void addPathsIntoContext(Map<String, String> context, List<LwM2mPath> paths) {
        StringBuilder sb = new StringBuilder();
        for (LwM2mPath path : paths) {
            sb.append(path.toString());
            sb.append("\n");
        }
        context.put(CTX_LWM2M_PATH, sb.toString());
    }

    public static String extractRegistrationId(org.eclipse.californium.core.observe.Observation observation) {
        return observation.getRequest().getUserContext().get(CTX_REGID);
    }

    public static LwM2mPath extractLwm2mPath(org.eclipse.californium.core.observe.Observation observation) {
        if (observation.getRequest().getCode() == CoAP.Code.GET) {
            return new LwM2mPath(observation.getRequest().getUserContext().get(CTX_LWM2M_PATH));
        } else {
            throw new IllegalStateException(
                    "Observation targeting only ont path must be a GET but was " + observation.getRequest().getCode());
        }
    }

    public static List<LwM2mPath> extractLwm2mPaths(org.eclipse.californium.core.observe.Observation observation) {
        if (observation.getRequest().getCode() == CoAP.Code.FETCH) {
            List<LwM2mPath> lwm2mPath = new ArrayList<>();
            String pathsAsString = observation.getRequest().getUserContext().get(CTX_LWM2M_PATH);
            for (String path : pathsAsString.split("\n")) {
                lwm2mPath.add(new LwM2mPath(path));
            }

            if (lwm2mPath.size() == 0) {
                throw new IllegalStateException("missing paths in request context");
            }
            return lwm2mPath;
        } else {
            throw new IllegalStateException(
                    "Observation targeting several path must be a FETCH but was " + observation.getRequest().getCode());
        }
    }

    public static String extractEndpoint(org.eclipse.californium.core.observe.Observation observation) {
        return observation.getRequest().getUserContext().get(CTX_ENDPOINT);
    }

    /**
     * Validate the Californium observation. It is valid if it contains all necessary context for Leshan.
     */
    public static String validateCoapObservation(org.eclipse.californium.core.observe.Observation observation) {
        if (!observation.getRequest().getUserContext().containsKey(CTX_REGID))
            throw new IllegalStateException("missing registrationId info in the request context");
        if (!observation.getRequest().getUserContext().containsKey(CTX_LWM2M_PATH))
            throw new IllegalStateException("missing lwm2m path info in the request context");

        String endpoint = observation.getRequest().getUserContext().get(CTX_ENDPOINT);
        if (endpoint == null)
            throw new IllegalStateException("missing endpoint info in the request context");

        return endpoint;
    }
}
