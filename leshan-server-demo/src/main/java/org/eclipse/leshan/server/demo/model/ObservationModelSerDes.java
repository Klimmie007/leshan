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
 *     Kamil Milewski @ PLUM sp. z o.o - saving observations to file
 *******************************************************************************/

package org.eclipse.leshan.server.demo.model;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.leshan.core.model.ObservationModel;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.util.json.JacksonJsonSerDes;
import org.eclipse.leshan.core.util.json.JsonException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ObservationModelSerDes extends JacksonJsonSerDes<ObservationModel> {

    @Override
    public JsonNode jSerialize(ObservationModel observationModel) {
        final ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("ep", observationModel.ep);
        final ArrayNode a = JsonNodeFactory.instance.arrayNode(observationModel.paths.size());
        for (int i = 0; i < observationModel.paths.size(); i++) {
            a.add(observationModel.paths.get(i).toString());
        }
        o.put("paths", a);
        return o;
    }

    @Override
    public ObservationModel deserialize(JsonNode o) throws JsonException {
        if (o == null)
            return null;

        if (!o.isObject())
            return null;

        JsonNode tmp = o.get("paths");
        if (!tmp.isArray())
            return null;

        List<LwM2mPath> paths = new ArrayList<>();

        for (final JsonNode objNode : tmp) {
            paths.add(new LwM2mPath(objNode.asText()));
        }

        return new ObservationModel(o.get("ep").asText(), paths);
    }
}
