package ai.labs.core.rest.internal;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.lifecycle.IConversation;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IConversationMemoryStore;
import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.memory.model.ConversationOutput;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.models.Context;
import ai.labs.models.ConversationState;
import ai.labs.models.Deployment;
import ai.labs.models.InputData;
import ai.labs.persistence.IResourceStore;
import ai.labs.rest.rest.IRestBotEngine;
import ai.labs.runtime.IBot;
import ai.labs.runtime.IBotFactory;
import ai.labs.runtime.IConversationCoordinator;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.SystemRuntime.IRuntime.IFinishedExecution;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.RuntimeUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static ai.labs.memory.ConversationMemoryUtilities.*;

/**
 * @author ginccc
 */
@Slf4j
public class RestBotEngine implements IRestBotEngine {
    private static final String resourceURI = "eddi://ai.labs.conversation/conversationstore/conversations/";
    private static final String CACHE_NAME_CONVERSATION_STATE = "conversationState";
    private final IBotFactory botFactory;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IConversationCoordinator conversationCoordinator;
    private final SystemRuntime.IRuntime runtime;
    private final int botTimeout;
    private final ICache<String, ConversationState> conversationStateCache;

    @Inject
    public RestBotEngine(IBotFactory botFactory,
                         IConversationMemoryStore conversationMemoryStore,
                         IConversationCoordinator conversationCoordinator,
                         ICacheFactory cacheFactory,
                         SystemRuntime.IRuntime runtime,
                         @Named("system.botTimeoutInSeconds") int botTimeout) {
        this.botFactory = botFactory;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationCoordinator = conversationCoordinator;
        this.conversationStateCache = cacheFactory.getCache(CACHE_NAME_CONVERSATION_STATE);
        this.runtime = runtime;
        this.botTimeout = botTimeout;
    }


    @Override
    public Response startConversation(Deployment.Environment environment, String botId) {
        return startConversationWithContext(environment, botId, Collections.emptyMap());
    }

    @Override
    public Response startConversationWithContext(Deployment.Environment environment, String botId, Map<String, Context> context) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(context, "context");
        try {
            IBot latestBot = botFactory.getLatestBot(environment, botId);
            if (latestBot == null) {
                String message = "No instance of bot (botId=%s) deployed in environment (environment=%s)!";
                message = String.format(message, botId, environment);
                return Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_PLAIN).entity(message).build();
            }

            IConversation conversation = latestBot.startConversation(context, null);
            String conversationId = storeConversationMemory(conversation.getConversationMemory(), environment);
            cacheConversationState(conversationId, ConversationState.READY);
            URI createdUri = RestUtilities.createURI(resourceURI, conversationId);
            return Response.created(createdUri).build();
        } catch (ServiceException |
                IResourceStore.ResourceStoreException |
                InstantiationException |
                LifecycleException |
                IllegalAccessException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response endConversation(String conversationId) {
        setConversationState(conversationId, ConversationState.ENDED);
        return Response.ok().build();
    }

    @Override
    public SimpleConversationMemorySnapshot readConversation(Deployment.Environment environment,
                                                             String botId,
                                                             String conversationId,
                                                             Boolean returnDetailed,
                                                             Boolean returnCurrentStepOnly,
                                                             List<String> returningFields) {

        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        try {
            ConversationMemorySnapshot conversationMemorySnapshot =
                    conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (!botId.equals(conversationMemorySnapshot.getBotId())) {
                String message = "conversationId: '%s' does not belong to bot with conversationId: '%s'. " +
                        "(provided botId='%s', botId in ConversationMemory='%s')";
                message = String.format(message, conversationId, botId, botId, conversationMemorySnapshot.getBotId());
                throw new IllegalAccessException(message);
            }
            return getSimpleConversationMemorySnapshot(conversationMemorySnapshot,
                    returnDetailed,
                    returnCurrentStepOnly,
                    returningFields);
        } catch (IResourceStore.ResourceStoreException | IllegalAccessException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public ConversationState getConversationState(Deployment.Environment environment, String conversationId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");

        ConversationState conversationState = conversationStateCache.get(conversationId);
        if (conversationState == null) {
            conversationState = conversationMemoryStore.getConversationState(conversationId);
            cacheConversationState(conversationId, conversationState);
        }

        if (conversationState == null) {
            String message = "No conversation found! (conversationId=%s)";
            message = String.format(message, conversationId);
            throw new NoLogWebApplicationException(new Throwable(message), Response.Status.NOT_FOUND);
        }

        return conversationState;
    }

    @Override
    public void say(final Deployment.Environment environment,
                    final String botId, final String conversationId,
                    final Boolean returnDetailed, final Boolean returnCurrentStepOnly,
                    final List<String> returningFields, final String message, final AsyncResponse response) {

        sayWithinContext(environment, botId, conversationId, returnDetailed, returnCurrentStepOnly,
                returningFields, new InputData(message, new HashMap<>()), response);
    }

    @Override
    public void sayWithinContext(final Deployment.Environment environment,
                                 final String botId, final String conversationId,
                                 final Boolean returnDetailed, final Boolean returnCurrentStepOnly,
                                 final List<String> returningFields, final InputData inputData,
                                 final AsyncResponse response) {

        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        RuntimeUtilities.checkNotNull(inputData, "inputData");
        RuntimeUtilities.checkNotNull(inputData.getInput(), "inputData.input");

        response.setTimeout(botTimeout, TimeUnit.SECONDS);
        response.setTimeoutHandler((asyncResp) ->
                asyncResp.resume(Response.status(Response.Status.REQUEST_TIMEOUT).build()));
        try {
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            checkConversationMemoryNotNull(conversationMemory, conversationId);
            if (!botId.equals(conversationMemory.getBotId())) {
                String message = "Supplied botId (%s) is incompatible with conversationId (%s)";
                message = String.format(message, botId, conversationId);
                response.resume(Response.status(Response.Status.CONFLICT).type(MediaType.TEXT_PLAIN).
                        entity(message).build());
                return;
            }

            IBot bot = botFactory.getBot(environment,
                    conversationMemory.getBotId(), conversationMemory.getBotVersion());
            if (bot == null) {
                String msg = "Bot not deployed (environment=%s, conversationId=%s, version=%s)";
                msg = String.format(msg, environment, conversationMemory.getBotId(), conversationMemory.getBotVersion());
                response.resume(new NotFoundException(msg));
                return;
            }
            final IConversation conversation = bot.continueConversation(conversationMemory,
                    returnConversationMemory -> {
                        SimpleConversationMemorySnapshot memorySnapshot =
                                getSimpleConversationMemorySnapshot(returnConversationMemory,
                                        returnDetailed,
                                        returnCurrentStepOnly,
                                        returningFields);
                        memorySnapshot.setEnvironment(environment);
                        cacheConversationState(conversationId, memorySnapshot.getConversationState());
                        response.resume(memorySnapshot);
                    });

            if (conversation.isEnded()) {
                response.resume(Response.status(Response.Status.GONE).entity("Conversation has ended!").build());
                return;
            }

            Callable<Void> processUserInput =
                    processUserInput(environment,
                            conversationId,
                            inputData.getInput(),
                            inputData.getContext(),
                            conversationMemory,
                            conversation);

            conversationCoordinator.submitInOrder(conversationId, processUserInput);
        } catch (InstantiationException | IllegalAccessException e) {
            String errorMsg = "Error while processing message!";
            log.error(errorMsg, e);
            throw new InternalServerErrorException(errorMsg, e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private Callable<Void> processUserInput(Deployment.Environment environment,
                                            String conversationId, String message,
                                            Map<String, Context> inputDataContext,
                                            IConversationMemory conversationMemory,
                                            IConversation conversation) {
        return () -> {
            waitForExecutionFinishOrTimeout(conversationId, runtime.submitCallable(() -> {
                        conversation.say(message, convertContext(inputDataContext));
                        return null;
                    },
                    new IFinishedExecution<>() {
                        @Override
                        public void onComplete(Void result) {
                            try {
                                storeConversationMemory(conversationMemory, environment);
                            } catch (IResourceStore.ResourceStoreException e) {
                                logConversationError(conversationId, e);
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            if (t instanceof LifecycleException.LifecycleInterruptedException) {
                                String errorMessage = "Conversation processing got interrupted! (conversationId=%s)";
                                errorMessage = String.format(errorMessage, conversationId);
                                log.warn(errorMessage, t);
                            } else if (t instanceof IConversation.ConversationNotReadyException) {
                                String msg = "Conversation not ready! (conversationId=%s)";
                                msg = String.format(msg, conversationId);
                                log.error(msg + "\n" + t.getLocalizedMessage(), t);
                            } else {
                                logConversationError(conversationId, t);
                            }
                        }
                    }, null));
            return null;
        };
    }

    private void waitForExecutionFinishOrTimeout(String conversationId, Future<Void> future) {
        try {
            future.get(botTimeout, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            setConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
            String errorMessage = "Execution of Packages interrupted or timed out.";
            log.error(errorMessage, e);
            future.cancel(true);
        } catch (ExecutionException e) {
            logConversationError(conversationId, e);
        }
    }

    private void logConversationError(String conversationId, Throwable t) {
        setConversationState(conversationId, ConversationState.ERROR);
        String msg = "Error while processing user input (conversationId=%s , conversationState=%s)";
        msg = String.format(msg, conversationId, ConversationState.ERROR);
        log.error(msg, t);
    }

    private Map<String, Context> convertContext(Map<String, Context> inputDataContext) {
        if (inputDataContext == null) {
            return new HashMap<>();
        } else {
            return inputDataContext.entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            e -> {
                                Context context = e.getValue();
                                return new Context(
                                        Context.ContextType.valueOf(context.getType().toString()),
                                        context.getValue());
                            }));
        }

    }

    @Override
    public Boolean isUndoAvailable(Deployment.Environment environment, String botId, String conversationId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        final IConversationMemory conversationMemory;
        try {
            conversationMemory = loadConversationMemory(conversationId);
            return conversationMemory.isUndoAvailable();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public Response undo(final Deployment.Environment environment, String botId, final String conversationId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");

        try {
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            checkConversationMemoryNotNull(conversationMemory, conversationId);

            if (!botId.equals(conversationMemory.getBotId())) {
                throw new IllegalAccessException("Supplied botId is incompatible to conversationId");
            }

            Callable<Void> processUserInput = () -> {

                try {
                    if (conversationMemory.isUndoAvailable()) {
                        conversationMemory.undoLastStep();
                        storeConversationMemory(conversationMemory, environment);
                    }
                } catch (Exception e) {
                    log.error("Error while Undo!", e);
                    throw e;
                }

                return null;
            };

            SystemRuntime.getRuntime().submitCallable(processUserInput, null);

            return Response.accepted().build();
        } catch (IllegalAccessException e) {
            String errorMsg = "Error while processing message!";
            log.error(errorMsg, e);
            throw new InternalServerErrorException(errorMsg, e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Boolean isRedoAvailable(final Deployment.Environment environment, String botId, String conversationId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        final IConversationMemory conversationMemory;
        try {
            conversationMemory = loadConversationMemory(conversationId);
            return conversationMemory.isRedoAvailable();
        } catch (IResourceStore.ResourceStoreException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Response redo(final Deployment.Environment environment, String botId, final String conversationId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");

        try {
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            checkConversationMemoryNotNull(conversationMemory, conversationId);

            if (!botId.equals(conversationMemory.getBotId())) {
                throw new IllegalAccessException("Supplied botId is incompatible to conversationId");
            }

            Callable<Void> processUserInput = () -> {
                try {
                    if (conversationMemory.isRedoAvailable()) {
                        conversationMemory.redoLastStep();
                        storeConversationMemory(conversationMemory, environment);
                    }
                } catch (Exception e) {
                    log.error("Error while Redo!", e);
                    throw e;
                }

                return null;
            };

            SystemRuntime.getRuntime().submitCallable(processUserInput, null);
            return Response.accepted().build();
        } catch (IllegalAccessException e) {
            String errorMsg = "Error while processing message!";
            log.error(errorMsg, e);
            throw new InternalServerErrorException(errorMsg, e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private IConversationMemory loadConversationMemory(String conversationId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        ConversationMemorySnapshot conversationMemorySnapshot =
                conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        return convertConversationMemorySnapshot(conversationMemorySnapshot);
    }

    private void setConversationState(String conversationId, ConversationState conversationState) {
        conversationMemoryStore.setConversationState(conversationId, conversationState);
        cacheConversationState(conversationId, conversationState);
    }

    private void cacheConversationState(String conversationId, ConversationState conversationState) {
        conversationStateCache.put(conversationId, conversationState);
    }

    private String storeConversationMemory(IConversationMemory conversationMemory, Deployment.Environment environment)
            throws IResourceStore.ResourceStoreException {
        ConversationMemorySnapshot memorySnapshot = convertConversationMemory(conversationMemory);
        memorySnapshot.setEnvironment(environment);
        return conversationMemoryStore.storeConversationMemorySnapshot(memorySnapshot);
    }

    private SimpleConversationMemorySnapshot getSimpleConversationMemorySnapshot(
            IConversationMemory returnConversationMemory,
            Boolean returnDetailed,
            Boolean returnCurrentStepOnly,
            List<String> returningFields) {

        return getSimpleConversationMemorySnapshot(
                convertConversationMemory(returnConversationMemory),
                returnDetailed,
                returnCurrentStepOnly, returningFields);
    }

    private SimpleConversationMemorySnapshot getSimpleConversationMemorySnapshot(
            ConversationMemorySnapshot conversationMemorySnapshot,
            Boolean returnDetailed,
            Boolean returnCurrentStepOnly,
            List<String> returningFields) {

        SimpleConversationMemorySnapshot memorySnapshot = convertSimpleConversationMemory(
                conversationMemorySnapshot, returnDetailed);
        if (returnCurrentStepOnly) {
            if (returningFields.isEmpty() || returningFields.contains("conversationSteps")) {
                List<SimpleConversationMemorySnapshot.SimpleConversationStep> conversationSteps =
                        memorySnapshot.getConversationSteps();
                SimpleConversationMemorySnapshot.SimpleConversationStep currentConversationStep =
                        conversationSteps.get(conversationSteps.size() - 1);
                conversationSteps.clear();
                conversationSteps.add(currentConversationStep);
            } else {
                memorySnapshot.setConversationSteps(null);
            }


            if (returningFields.isEmpty() || returningFields.contains("conversationOutputs")) {
                List<ConversationOutput> conversationOutputs = memorySnapshot.getConversationOutputs();

                ConversationOutput conversationOutput = conversationOutputs.get(0);
                conversationOutputs.clear();
                conversationOutputs.add(conversationOutput);
            } else {
                memorySnapshot.setConversationOutputs(null);
            }
        }
        return memorySnapshot;
    }

    private static void checkConversationMemoryNotNull(IConversationMemory conversationMemory, String conversationId)
            throws IllegalAccessException {
        if (conversationMemory == null) {
            String message = "No conversation found with conversationId: %s";
            message = String.format(message, conversationId);
            throw new IllegalAccessException(message);
        }
    }
}
