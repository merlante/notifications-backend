package com.redhat.cloud.notifications.routers.handlers.endpoint;

import com.redhat.cloud.notifications.Constants;
import com.redhat.cloud.notifications.auth.ConsoleIdentityProvider;
import com.redhat.cloud.notifications.auth.kessel.permission.IntegrationPermission;
import com.redhat.cloud.notifications.db.Query;
import com.redhat.cloud.notifications.models.NotificationHistory;
import com.redhat.cloud.notifications.models.dto.v1.NotificationHistoryDTO;
import com.redhat.cloud.notifications.models.dto.v1.endpoint.EndpointDTO;
import com.redhat.cloud.notifications.routers.models.EndpointPage;
import com.redhat.cloud.notifications.routers.models.Meta;
import com.redhat.cloud.notifications.routers.models.Page;
import com.redhat.cloud.notifications.routers.models.PageLinksBuilder;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameters;
import org.project_kessel.api.inventory.v1beta1.resources.ListNotificationsIntegrationsResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.redhat.cloud.notifications.db.repositories.NotificationRepository.MAX_NOTIFICATION_HISTORY_RESULTS;
import static com.redhat.cloud.notifications.routers.SecurityContextUtil.getOrgId;
import static com.redhat.cloud.notifications.routers.SecurityContextUtil.getUsername;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path(Constants.API_INTEGRATIONS_V_2_0 + "/endpoints")
public class EndpointResourceV2 extends EndpointResource {
    @GET
    @Path("/{id}/history")
    @Produces(APPLICATION_JSON)
    @Parameters(
        {
            @Parameter(
                name = "limit",
                in = ParameterIn.QUERY,
                description = "Number of items per page, if not specified or 0 is used, returns a maximum of " + MAX_NOTIFICATION_HISTORY_RESULTS + " elements.",
                schema = @Schema(type = SchemaType.INTEGER)
            ),
            @Parameter(
                name = "pageNumber",
                in = ParameterIn.QUERY,
                description = "Page number. Starts at first page (0), if not specified starts at first page.",
                schema = @Schema(type = SchemaType.INTEGER)
            ),
            @Parameter(
                name = "includeDetail",
                description = "Include the detail in the reply",
                schema = @Schema(type = SchemaType.BOOLEAN)
            )
        }
    )
    public Page<NotificationHistoryDTO> getEndpointHistory(
        @Context SecurityContext sec,
        @Context UriInfo uriInfo,
        @PathParam("id") UUID id,
        @QueryParam("includeDetail") Boolean includeDetail,
        @BeanParam Query query
    ) {
        if (this.backendConfig.isKesselRelationsEnabled(getOrgId(sec))) {
            this.kesselAuthorization.hasPermissionOnIntegration(sec, IntegrationPermission.VIEW_HISTORY, id);

            return this.internalGetEndpointHistory(sec, uriInfo, id, includeDetail, query);
        } else {
            return this.legacyRBACGetEndpointHistory(sec, uriInfo, id, includeDetail, query);
        }
    }

    @RolesAllowed(ConsoleIdentityProvider.RBAC_READ_INTEGRATIONS_ENDPOINTS)
    protected Page<NotificationHistoryDTO> legacyRBACGetEndpointHistory(final SecurityContext securityContext, final UriInfo uriInfo, final UUID id, final Boolean includeDetail, final Query query) {
        return this.internalGetEndpointHistory(securityContext, uriInfo, id, includeDetail, query);
    }

    protected Page<NotificationHistoryDTO> internalGetEndpointHistory(final SecurityContext securityContext, final UriInfo uriInfo, final UUID id, final Boolean includeDetail, @Valid final Query query) {
        if (!this.endpointRepository.existsByUuidAndOrgId(id, getOrgId(securityContext))) {
            throw new NotFoundException("Endpoint not found");
        }

        String orgId = getOrgId(securityContext);
        boolean doDetail = includeDetail != null && includeDetail;

        final List<NotificationHistory> notificationHistory = this.notificationRepository.getNotificationHistory(orgId, id, doDetail, query);
        final long notificationHistoryCount = this.notificationRepository.countNotificationHistoryElements(id, orgId);

        return new Page<>(
            commonMapper.notificationHistoryListToNotificationHistoryDTOList(notificationHistory),
            PageLinksBuilder.build(uriInfo.getPath(), notificationHistoryCount, query.getLimit().getLimit(), query.getLimit().getOffset()),
            new Meta(notificationHistoryCount)
        );
    }

    @GET
    @Path("/{id}")
    @Produces(APPLICATION_JSON)
    @Operation(summary = "Retrieve an endpoint", description = "Retrieves the public information associated with an endpoint such as its description, name, and properties.")
    public EndpointDTO getEndpoint(@Context SecurityContext sec, @PathParam("id") UUID id) {
        if (this.backendConfig.isKesselRelationsEnabled(getOrgId(sec))) {
            this.kesselAuthorization.hasPermissionOnIntegration(sec, IntegrationPermission.VIEW, id);

            return this.internalGetEndpoint(sec, id, true);
        } else {
            return legacyGetEndpoint(sec, id, true);
        }
    }

    @GET
    @Produces(APPLICATION_JSON)
    @Operation(summary = "List endpoints", description = "Provides a list of endpoints. Use this endpoint to find specific endpoints.")
    @Parameters(
        {
            @Parameter(
                name = "limit",
                in = ParameterIn.QUERY,
                description = "Number of items per page. If the value is 0, it will return all elements",
                schema = @Schema(type = SchemaType.INTEGER)
            ),
            @Parameter(
                name = "pageNumber",
                in = ParameterIn.QUERY,
                description = "Page number. Starts at first page (0), if not specified starts at first page.",
                schema = @Schema(type = SchemaType.INTEGER)
            )
        }
    )
    public EndpointPage getEndpoints(
        @Context                SecurityContext sec,
        @BeanParam @Valid       Query query,
        @QueryParam("type")     List<String> targetType,
        @QueryParam("active")   Boolean activeOnly,
        @QueryParam("name")     String name
    ) {
        if (this.backendConfig.isKesselRelationsEnabled(getOrgId(sec))) {
            // Fetch the set of integration IDs the user is authorized to view.
            final UUID workspaceId = this.workspaceUtils.getDefaultWorkspaceId(getOrgId(sec));

            Log.errorf("[org_id: %s][username: %s] Kessel did not return any integration IDs for the request", getOrgId(sec), getUsername(sec));

            // add permission as argument -- rather than assuming it underneath
            final Multi<ListNotificationsIntegrationsResponse> responseMulti = this.kesselAssets.listIntegrations(sec, workspaceId.toString());
            Set<UUID> authorizedIds = responseMulti.map(ListNotificationsIntegrationsResponse::getIntegrations)
                    .map(i -> i.getReporterData().getLocalResourceId())
                    .map(UUID::fromString)
                    .collect()
                    .asSet()
                    .await().indefinitely();

            //final Set<UUID> authorizedIds = this.kesselAuthorization.lookupAuthorizedIntegrations(sec, IntegrationPermission.VIEW);
            if (authorizedIds.isEmpty()) {
                Log.infof("[org_id: %s][username: %s] Kessel did not return any integration IDs for the request", getOrgId(sec), getUsername(sec));

                return new EndpointPage(new ArrayList<>(), new HashMap<>(), new Meta(0L));
            }

            return internalGetEndpoints(sec, query, targetType, activeOnly, name, authorizedIds, true);
        }

        return getEndpointsLegacyRBACRoles(sec, query, targetType, activeOnly, name, true);
    }
}
