package ai.labs.resources.rest.botmanagement;

import ai.labs.models.BotTriggerConfiguration;
import io.swagger.annotations.Api;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Api(value = "configurations")
@Path("/bottriggerstore/bottriggers")
public interface IRestBotTriggerStore {
    String resourceURI = "eddi://ai.labs.bottrigger/bottriggerstore/bottriggers/";

    @GET
    @Path("/{intent}")
    @Produces(MediaType.APPLICATION_JSON)
    BotTriggerConfiguration readBotTrigger(@PathParam("intent") String intent);

    @PUT
    @Path("/{intent}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response updateBotTrigger(@PathParam("intent") String intent, BotTriggerConfiguration botTriggerConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createBotTrigger(BotTriggerConfiguration botTriggerConfiguration);

    @DELETE
    @Path("/{intent}")
    Response deleteBotTrigger(@PathParam("intent") String intent);
}
