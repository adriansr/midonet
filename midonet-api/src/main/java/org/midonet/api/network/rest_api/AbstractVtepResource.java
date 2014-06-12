/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.network.rest_api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import javax.validation.Validator;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.api.network.VtepBinding;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.vtep.VtepClusterClient;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.api.validation.MessageProperty.VTEP_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;

abstract public class AbstractVtepResource extends AbstractResource {

    protected final VtepClusterClient vtepClient;
    protected final ResourceFactory factory;

    @Inject
    public AbstractVtepResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context, Validator validator,
                               DataClient dataClient, ResourceFactory factory,
                               VtepClusterClient vtepClient) {
        super(config, uriInfo, context, dataClient, validator);
        this.vtepClient = vtepClient;
        this.factory = factory;
    }

    protected org.midonet.cluster.data.VTEP getVtepOrThrow(
            String ipAddrStr, boolean badRequest)
            throws StateAccessException, SerializationException
    {
        return vtepClient.getVtepOrThrow(parseIPv4Addr(ipAddrStr), badRequest);
    }

    protected final List<VtepBinding> listVtepBindings(String ipAddrStr,
                                                       UUID bridgeId)
            throws SerializationException, StateAccessException {

        List<VtepBinding> bindings;
        try {
            bindings = vtepClient.listVtepBindings(
                    parseIPv4Addr(ipAddrStr), bridgeId);
        } catch (NoStatePathException ex) {
            throw new NotFoundHttpException(getMessage(
                    VTEP_NOT_FOUND, ipAddrStr));
        }

        URI baseUri = getBaseUri();
        for (VtepBinding binding : bindings) {
            binding.setBaseUri(baseUri);
        }
        return bindings;
    }


}
