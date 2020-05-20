/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.io.rest.core.internal.extensions;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Stream;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.auth.Role;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.extension.Extension;
import org.openhab.core.extension.ExtensionEventFactory;
import org.openhab.core.extension.ExtensionService;
import org.openhab.core.extension.ExtensionType;
import org.openhab.core.io.rest.JSONResponse;
import org.openhab.core.io.rest.LocaleService;
import org.openhab.core.io.rest.RESTConstants;
import org.openhab.core.io.rest.RESTResource;
import org.openhab.core.io.rest.Stream2JSONInputStream;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.jaxrs.whiteboard.JaxrsWhiteboardConstants;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JSONRequired;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsApplicationSelect;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsName;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * This class acts as a REST resource for extensions and provides methods to install and uninstall them.
 *
 * @author Kai Kreuzer - Initial contribution
 * @author Franck Dechavanne - Added DTOs to ApiResponses
 * @author Markus Rathgeb - Migrated to JAX-RS Whiteboard Specification
 */
@Component
@JaxrsResource
@JaxrsName(ExtensionResource.PATH_EXTENSIONS)
@JaxrsApplicationSelect("(" + JaxrsWhiteboardConstants.JAX_RS_NAME + "=" + RESTConstants.JAX_RS_NAME + ")")
@JSONRequired
@Path(ExtensionResource.PATH_EXTENSIONS)
@RolesAllowed({ Role.ADMIN })
@Api(ExtensionResource.PATH_EXTENSIONS)
@NonNullByDefault
public class ExtensionResource implements RESTResource {

    private static final String THREAD_POOL_NAME = "extensionService";

    public static final String PATH_EXTENSIONS = "extensions";

    private final Logger logger = LoggerFactory.getLogger(ExtensionResource.class);
    private final Set<ExtensionService> extensionServices = new CopyOnWriteArraySet<>();
    private final EventPublisher eventPublisher;
    private final LocaleService localeService;

    private @Context @NonNullByDefault({}) UriInfo uriInfo;

    @Activate
    public ExtensionResource(final @Reference EventPublisher eventPublisher,
            final @Reference LocaleService localeService) {
        this.eventPublisher = eventPublisher;
        this.localeService = localeService;
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    protected void addExtensionService(ExtensionService featureService) {
        this.extensionServices.add(featureService);
    }

    protected void removeExtensionService(ExtensionService featureService) {
        this.extensionServices.remove(featureService);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get all extensions.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK", response = String.class) })
    public Response getExtensions(
            @HeaderParam("Accept-Language") @ApiParam(value = "language") @Nullable String language) {
        logger.debug("Received HTTP GET request at '{}'", uriInfo.getPath());
        Locale locale = localeService.getLocale(language);
        return Response.ok(new Stream2JSONInputStream(getAllExtensions(locale))).build();
    }

    @GET
    @Path("/types")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get all extension types.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK", response = String.class) })
    public Response getTypes(@HeaderParam("Accept-Language") @ApiParam(value = "language") @Nullable String language) {
        logger.debug("Received HTTP GET request at '{}'", uriInfo.getPath());
        Locale locale = localeService.getLocale(language);
        Stream<ExtensionType> extensionTypeStream = getAllExtensionTypes(locale).stream().distinct();
        return Response.ok(new Stream2JSONInputStream(extensionTypeStream)).build();
    }

    @GET
    @Path("/{extensionId: [a-zA-Z_0-9-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get extension with given ID.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK", response = String.class),
            @ApiResponse(code = 404, message = "Not found") })
    public Response getById(@HeaderParam("Accept-Language") @ApiParam(value = "language") @Nullable String language,
            @PathParam("extensionId") @ApiParam(value = "extension ID") String extensionId) {
        logger.debug("Received HTTP GET request at '{}'.", uriInfo.getPath());
        Locale locale = localeService.getLocale(language);
        ExtensionService extensionService = getExtensionService(extensionId);
        Extension responseObject = extensionService.getExtension(extensionId, locale);
        if (responseObject != null) {
            return Response.ok(responseObject).build();
        }

        return Response.status(404).build();
    }

    @POST
    @Path("/{extensionId: [a-zA-Z_0-9-:]+}/install")
    @ApiOperation(value = "Installs the extension with the given ID.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK") })
    public Response installExtension(
            final @PathParam("extensionId") @ApiParam(value = "extension ID") String extensionId) {
        ThreadPoolManager.getPool(THREAD_POOL_NAME).submit(() -> {
            try {
                ExtensionService extensionService = getExtensionService(extensionId);
                extensionService.install(extensionId);
            } catch (Exception e) {
                logger.error("Exception while installing extension: {}", e.getMessage());
                postFailureEvent(extensionId, e.getMessage());
            }
        });
        return Response.ok(null, MediaType.TEXT_PLAIN).build();
    }

    @POST
    @Path("/url/{url}/install")
    @ApiOperation(value = "Installs the extension from the given URL.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK"),
            @ApiResponse(code = 400, message = "The given URL is malformed or not valid.") })
    public Response installExtensionByURL(
            final @PathParam("url") @ApiParam(value = "extension install URL") String url) {
        try {
            URI extensionURI = new URI(url);
            String extensionId = getExtensionId(extensionURI);
            installExtension(extensionId);
        } catch (URISyntaxException | IllegalArgumentException e) {
            logger.error("Exception while parsing the extension URL '{}': {}", url, e.getMessage());
            return JSONResponse.createErrorResponse(Status.BAD_REQUEST, "The given URL is malformed or not valid.");
        }

        return Response.ok(null, MediaType.TEXT_PLAIN).build();
    }

    @POST
    @Path("/{extensionId: [a-zA-Z_0-9-:]+}/uninstall")
    @ApiOperation(value = "Uninstalls the extension with the given ID.")
    @ApiResponses(value = { @ApiResponse(code = 200, message = "OK") })
    public Response uninstallExtension(
            final @PathParam("extensionId") @ApiParam(value = "extension ID") String extensionId) {
        ThreadPoolManager.getPool(THREAD_POOL_NAME).submit(() -> {
            try {
                ExtensionService extensionService = getExtensionService(extensionId);
                extensionService.uninstall(extensionId);
            } catch (Exception e) {
                logger.error("Exception while uninstalling extension: {}", e.getMessage());
                postFailureEvent(extensionId, e.getMessage());
            }
        });
        return Response.ok(null, MediaType.TEXT_PLAIN).build();
    }

    private void postFailureEvent(String extensionId, String msg) {
        Event event = ExtensionEventFactory.createExtensionFailureEvent(extensionId, msg);
        eventPublisher.post(event);
    }

    private Stream<Extension> getAllExtensions(Locale locale) {
        return extensionServices.stream().map(s -> s.getExtensions(locale)).flatMap(l -> l.stream());
    }

    private Set<ExtensionType> getAllExtensionTypes(Locale locale) {
        final Collator coll = Collator.getInstance(locale);
        coll.setStrength(Collator.PRIMARY);
        Set<ExtensionType> ret = new TreeSet<>(new Comparator<ExtensionType>() {
            @Override
            public int compare(ExtensionType o1, ExtensionType o2) {
                return coll.compare(o1.getLabel(), o2.getLabel());
            }
        });
        for (ExtensionService extensionService : extensionServices) {
            ret.addAll(extensionService.getTypes(locale));
        }
        return ret;
    }

    private ExtensionService getExtensionService(final String extensionId) {
        for (ExtensionService extensionService : extensionServices) {
            for (Extension extension : extensionService.getExtensions(Locale.getDefault())) {
                if (extensionId.equals(extension.getId())) {
                    return extensionService;
                }
            }
        }
        throw new IllegalArgumentException("No extension service registered for " + extensionId);
    }

    private String getExtensionId(URI extensionURI) {
        for (ExtensionService extensionService : extensionServices) {
            String extensionId = extensionService.getExtensionId(extensionURI);
            if (extensionId != null && !extensionId.isBlank()) {
                return extensionId;
            }
        }

        throw new IllegalArgumentException("No extension service registered for URI " + extensionURI);
    }
}