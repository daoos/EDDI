package ai.labs.output.impl;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.models.Context;
import ai.labs.output.IOutputFilter;
import ai.labs.output.IOutputGeneration;
import ai.labs.output.model.OutputEntry;
import ai.labs.output.model.OutputValue;
import ai.labs.output.model.QuickReply;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.ConfigValue;
import ai.labs.resources.rest.output.model.OutputConfiguration;
import ai.labs.resources.rest.output.model.OutputConfigurationSet;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.StringUtilities;

import javax.inject.Inject;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ai.labs.memory.IConversationMemory.*;

/**
 * @author ginccc
 */
public class OutputGenerationTask implements ILifecycleTask {
    private static final String ID = "ai.labs.output";
    private static final String ACTION_KEY = "action";
    private static final String MEMORY_OUTPUT_IDENTIFIER = "output";
    private static final String MEMORY_QUICK_REPLIES_IDENTIFIER = "quickReplies";
    private static final String CONTEXT_IDENTIFIER = "context";
    private static final String QUICK_REPLIES_IDENTIFIER = "quickReplies";
    private static final String OUTPUTSET_CONFIG_URI = "uri";
    private static final String KEY_VALUE = "value";
    private static final String KEY_EXPRESSIONS = "expressions";
    private static final String KEY_IS_DEFAULT = "isDefault";
    private final IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final IOutputGeneration outputGeneration;

    @Inject
    public OutputGenerationTask(IResourceClientLibrary resourceClientLibrary,
                                IDataFactory dataFactory,
                                IOutputGeneration outputGeneration) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.outputGeneration = outputGeneration;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Object getComponent() {
        return outputGeneration;
    }

    @Override
    public void executeTask(IConversationMemory memory) {
        List<IData<Context>> contextDataList = memory.getCurrentStep().getAllData("context");
        storeContextQuickReplies(memory, contextDataList);

        IData<List<String>> latestData = memory.getCurrentStep().getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }
        List<String> actions = latestData.getResult();
        List<IOutputFilter> outputFilters = createOutputFilters(memory, actions);

        Map<String, List<OutputEntry>> outputs = outputGeneration.getOutputs(outputFilters);
        outputs.forEach((action, outputEntries) ->
                outputEntries.forEach(outputEntry -> {
                    List<OutputValue> outputValues = outputEntry.getOutputs();
                    selectAndStoreOutput(memory, action, outputValues);

                    storeQuickReplies(memory, outputEntry.getQuickReplies(), outputEntry.getAction());
                }));
    }

    private void storeContextQuickReplies(IConversationMemory memory, List<IData<Context>> contextDataList) {
        contextDataList.forEach(contextData -> {
            String contextKey = contextData.getKey();
            Context context = contextData.getResult();
            String key = contextKey.substring((CONTEXT_IDENTIFIER + ":").length());
            if (key.startsWith(QUICK_REPLIES_IDENTIFIER) && context.getType().equals(Context.ContextType.object)) {
                String contextQuickReplyKey = CONTEXT_IDENTIFIER + ":" + QUICK_REPLIES_IDENTIFIER + ":";
                String quickRepliesKey = "context";
                if (contextKey.contains(contextQuickReplyKey)) {
                    quickRepliesKey = contextKey.substring(contextQuickReplyKey.length());
                }

                if (context.getType().equals(Context.ContextType.object)) {
                    List<QuickReply> quickReplies = convertMapToObjects((List<Map<String, String>>) context.getValue());
                    storeQuickReplies(memory, quickReplies, quickRepliesKey);
                }
            }
        });
    }

    private List<QuickReply> convertMapToObjects(List<Map<String, String>> quickRepliesMapList) {
        return quickRepliesMapList.stream().map(map ->
                new QuickReply(map.get(KEY_VALUE), map.get(KEY_EXPRESSIONS),
                        Boolean.parseBoolean(map.getOrDefault(KEY_IS_DEFAULT, "false")))).
                collect(Collectors.toCollection(LinkedList::new));
    }

    private void selectAndStoreOutput(IConversationMemory memory, String action, List<OutputValue> outputValues) {
        List<QuickReply> quickReplies = new LinkedList<>();
        IntStream.range(0, outputValues.size()).forEach(index -> {
            OutputValue outputValue = outputValues.get(index);
            List<Object> possibleValueAlternatives = outputValue.getValueAlternatives();
            Object randomValue = chooseRandomly(possibleValueAlternatives);
            if (randomValue instanceof Map) {
                ((Map) randomValue).put("type", outputValue.getType());
            }
            if (outputValue.getType().equals(OutputValue.Type.quickReply)) {
                Map<String, String> randomValueMap = (Map) randomValue;
                quickReplies.add(new QuickReply(randomValueMap.get(KEY_VALUE), randomValueMap.get(KEY_EXPRESSIONS),
                        Boolean.parseBoolean(randomValueMap.getOrDefault(KEY_IS_DEFAULT, "false"))));
            }

            String outputKey = createOutputKey(action, outputValues, outputValue, index);
            IData<Object> outputData = dataFactory.createData(outputKey, randomValue, possibleValueAlternatives);
            outputData.setPublic(true);
            IWritableConversationStep currentStep = memory.getCurrentStep();
            currentStep.storeData(outputData);
            currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, Collections.singletonList(randomValue));
        });

        if (!quickReplies.isEmpty()) {
            storeQuickReplies(memory, quickReplies, action);
        }
    }

    private void storeQuickReplies(IConversationMemory memory, List<QuickReply> quickReplies, String action) {
        if (!quickReplies.isEmpty()) {
            String outputQuickReplyKey = StringUtilities.
                    joinStrings(":", MEMORY_QUICK_REPLIES_IDENTIFIER, action);
            IData outputQuickReplies = dataFactory.createData(outputQuickReplyKey, quickReplies);
            outputQuickReplies.setPublic(true);
            IWritableConversationStep currentStep = memory.getCurrentStep();
            currentStep.storeData(outputQuickReplies);
            currentStep.addConversationOutputList(MEMORY_QUICK_REPLIES_IDENTIFIER, quickReplies);
        }
    }

    private LinkedList<IOutputFilter> createOutputFilters(IConversationMemory memory, List<String> actions) {
        return actions.stream().map(action ->
                new OutputFilter(action, countActionOccurrences(memory.getPreviousSteps(), action))).
                collect(Collectors.toCollection(LinkedList::new));
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        Object uriObj = configuration.get(OUTPUTSET_CONFIG_URI);
        URI uri = URI.create(uriObj.toString());

        try {
            OutputConfigurationSet outputConfigurationSet =
                    resourceClientLibrary.getResource(uri, OutputConfigurationSet.class);

            outputConfigurationSet.getOutputSet().forEach(outputConfig -> outputGeneration.addOutputEntry(
                    new OutputEntry(outputConfig.getAction(),
                            outputConfig.getTimesOccurred(),
                            convertOutputTypesConfig(outputConfig.getOutputs()),
                            convertQuickRepliesConfig(outputConfig.getQuickReplies()))));
        } catch (ServiceException e) {
            String message = "Error while fetching OutputConfigurationSet!\n" + e.getLocalizedMessage();
            throw new PackageConfigurationException(message, e);
        }
    }

    private String createOutputKey(String action, List<OutputValue> outputValues, OutputValue outputValue, int idx) {
        if (outputValues.size() > 1) {
            return StringUtilities.joinStrings(":", MEMORY_OUTPUT_IDENTIFIER,
                    outputValue.getType(), action, idx);
        } else {
            return StringUtilities.joinStrings(":", MEMORY_OUTPUT_IDENTIFIER,
                    outputValue.getType(), action);
        }
    }

    private Object chooseRandomly(List<Object> possibleValues) {
        return possibleValues.get(new Random().nextInt(possibleValues.size()));
    }

    private int countActionOccurrences(IConversationStepStack conversationStepStack,
                                       String action) {

        int count = 0;
        for (int i = 0; i < conversationStepStack.size(); i++) {
            IConversationStep conversationStep = conversationStepStack.get(i);
            IData<List<String>> latestData = conversationStep.getLatestData(ACTION_KEY);
            if (latestData != null) {
                List<String> actions = latestData.getResult();
                if (actions.contains(action)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * helper method to convert from OutputConfiguration to internal Output Value
     *
     * @param configOutputs List<OutputConfiguration.OutputType> as it comes from the configuration repository
     * @return List<OutputValue> as it is used in the internal system
     */
    private List<OutputValue> convertOutputTypesConfig(List<OutputConfiguration.OutputType> configOutputs) {
        return configOutputs.stream().map(configOutput -> {
            OutputValue outputValue = new OutputValue();
            outputValue.setType(OutputValue.Type.valueOf(configOutput.getType()));
            outputValue.setValueAlternatives(configOutput.getValueAlternatives());
            return outputValue;
        }).collect(Collectors.toCollection(LinkedList::new));
    }

    /**
     * helper method to convert from OutputConfiguration to internal QuickReply
     *
     * @param configQuickReplies List<OutputConfiguration.QuickReply> as it comes from the configuration repository
     * @return List<QuickReply> as it is used in the internal system
     */
    private List<QuickReply> convertQuickRepliesConfig(List<OutputConfiguration.QuickReply> configQuickReplies) {
        return configQuickReplies.stream().map(configQuickReply -> {
            QuickReply quickReply = new QuickReply();
            quickReply.setValue(configQuickReply.getValue());
            quickReply.setExpressions(configQuickReply.getExpressions());
            return quickReply;
        }).collect(Collectors.toCollection(LinkedList::new));
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Output Generation");

        ConfigValue configValue = new ConfigValue("Resource URI", ExtensionDescriptor.FieldType.URI, false, null);
        extensionDescriptor.getConfigs().put(OUTPUTSET_CONFIG_URI, configValue);
        return extensionDescriptor;
    }
}
