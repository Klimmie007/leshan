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
 *     Kamil Milewski @ PLUM sp. z o.o. - TCP support
 *******************************************************************************/

package org.eclipse.leshan.core.californium;

import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;

import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.elements.EndpointContextMatcher;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.tcp.netty.TcpClientConnector;
import org.eclipse.californium.elements.tcp.netty.TcpServerConnector;
import org.eclipse.californium.elements.tcp.netty.TlsServerConnector;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TCPEndpointFactory extends DefaultEndpointFactory {
    private static final Logger LOG = LoggerFactory.getLogger(TCPEndpointFactory.class);

    protected EndpointContextMatcher securedContextMatcher;
    protected EndpointContextMatcher unsecuredContextMatcher;
    protected String loggingTag;
    private boolean isClient;

    public TCPEndpointFactory() {
        this(null);
    }

    public TCPEndpointFactory(String loggingTag) {
        this(loggingTag, false);
    }

    public TCPEndpointFactory(String loggingTag, boolean isClient) {
        this.isClient = isClient;
        securedContextMatcher = createSecuredContextMatcher(isClient);
        unsecuredContextMatcher = createUnsecuredContextMatcher();
        if (loggingTag != null) {
            this.loggingTag = loggingTag;
        }
    }

    @Override
    protected Connector createUnsecuredConnector(InetSocketAddress address, Configuration coapConfig) {
        if (coapConfig != null) {
            if (isClient) {
                return new TcpClientConnector(coapConfig);
            } else {
                return new TcpServerConnector(address, coapConfig);
            }
        } else {
            LOG.error("Cannot create TCP Connector for address [{}] with null coapConfig!", address);
            return null;
        }
    }

    @Override
    protected Connector createSecuredConnector(DtlsConnectorConfig dtlsConnectorConfig) {
        try {
            if (isClient) {
                return new TcpClientConnector(dtlsConnectorConfig.getConfiguration());
            } else {
                return new TlsServerConnector(SSLContext.getInstance("TLS"), dtlsConnectorConfig.getAddress(),
                        dtlsConnectorConfig.getConfiguration());
            }
        } catch (NoSuchAlgorithmException e) {
            LOG.error("Creation of server endpoint with address [{}] failed - no protocol TLS support",
                    dtlsConnectorConfig.getAddress());
            throw new RuntimeException(e);
        }
    }
}
