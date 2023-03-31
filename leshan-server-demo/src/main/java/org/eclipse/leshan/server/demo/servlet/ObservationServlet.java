/****************************************
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
 *      Kamil Milewski @ PLUM sp. z o.o - API
 */
package org.eclipse.leshan.server.demo.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.leshan.core.californium.ObserveUtil;
import org.eclipse.leshan.core.model.ObservationModel;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.request.ContentFormat;
import org.eclipse.leshan.core.request.ObserveCompositeRequest;
import org.eclipse.leshan.core.request.ObserveRequest;
import org.eclipse.leshan.core.request.exception.InvalidRequestException;
import org.eclipse.leshan.core.util.json.JsonException;
import org.eclipse.leshan.server.californium.LeshanServer;
import org.eclipse.leshan.server.californium.observation.ObservationServiceImpl;
import org.eclipse.leshan.server.californium.registration.CaliforniumRegistrationStore;
import org.eclipse.leshan.server.demo.model.ObservationModelSerDes;
import org.eclipse.leshan.server.registration.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ObservationServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(ObservationServlet.class);
    private ObservationModelSerDes serDes = new ObservationModelSerDes();
    private CaliforniumRegistrationStore store;
    private ObservationServiceImpl service;
    private LeshanServer server;

    public ObservationServlet(LeshanServer server) {
        this.server = server;
        this.store = server.getRegStore();
        this.service = (ObservationServiceImpl) server.getObservationService();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String content = IOUtils.toString(req.getInputStream(), req.getCharacterEncoding());
        JsonNode node = new ObjectMapper().readTree(content);
        if (!node.isArray()) {
            resp.getOutputStream().print("File sent does not contain an array of observations");
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        List<ObservationModel> models;
        try {
            models = serDes.deserialize(node.elements());
        } catch (JsonException e) {
            resp.getOutputStream().print("There was an issue with JSON parsing");
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        } catch (Error e) {
            return;
        }
        Random random = ThreadLocalRandom.current();
        byte[] token;
        token = new byte[random.nextInt(8) + 1];
        // random value
        random.nextBytes(token);
        for (ObservationModel observationModel : models) {
            Registration registration = store.getRegistrationByEndpoint(observationModel.ep);
            if (observationModel.paths.size() > 1) {
                if (registration == null) {
                    Request coapRequest = Request.newFetch();
                    coapRequest.setToken(token);
                    coapRequest.setObserve();
                    coapRequest.setUserContext(ObserveUtil.createCoapObserveCompositeRequestContext(observationModel.ep,
                            null, new ObserveCompositeRequest(null, null, observationModel.paths)));
                    store.put(coapRequest.getToken(),
                            new org.eclipse.californium.core.observe.Observation(coapRequest, null));
                    service.addObservationWithoutRegistration(observationModel.ep,
                            ObserveUtil.createLwM2mCompositeObservation(coapRequest));
                } else
                    try {
                        server.send(registration, new ObserveCompositeRequest(ContentFormat.SENML_CBOR,
                                ContentFormat.SENML_CBOR, observationModel.paths));
                    } catch (InterruptedException e) {
                        LOG.error("Couldn't send composite request - interrupted", e);
                        resp.getOutputStream().print("There was an issue with JSON parsing");
                        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        return;
                    } catch (InvalidRequestException e) {
                        LOG.error("Couldn't send composite request - paths are overlapping", e);
                        resp.getOutputStream().print("File sent includes overlapping paths");
                        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        return;
                    } catch (Error e) {
                        return;
                    }
            } else {
                if (registration == null) {
                    Request coapRequest = Request.newGet();
                    coapRequest.setToken(token);
                    coapRequest.setObserve();
                    coapRequest.setUserContext(ObserveUtil.createCoapObserveRequestContext(observationModel.ep, null,
                            new ObserveRequest(observationModel.paths.get(0))));
                    store.put(coapRequest.getToken(),
                            new org.eclipse.californium.core.observe.Observation(coapRequest, null));
                    service.addObservationWithoutRegistration(observationModel.ep,
                            ObserveUtil.createLwM2mObservation(coapRequest));
                } else
                    try {
                        server.send(registration, new ObserveRequest(observationModel.paths.get(0)));
                    } catch (InterruptedException e) {
                        LOG.error("Couldn't send simple request - interrupted", e);
                    }

            }
        }
        resp.getOutputStream().print("Observations added");
        resp.setStatus(HttpServletResponse.SC_ACCEPTED);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        List<Observation> observations = store.getAllObservations();
        List<ObservationModel> models = new ArrayList<ObservationModel>();
        observations.forEach(new Consumer<Observation>() {
            @Override
            public void accept(Observation observation) {
                models.add(new ObservationModel(observation));
            }
        });
        resp.getOutputStream().write(serDes.bSerialize(models));
        resp.setStatus(HttpServletResponse.SC_OK);
    }
}
